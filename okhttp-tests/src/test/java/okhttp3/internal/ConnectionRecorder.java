/*
 * Copyright (C) 2016 Square, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package okhttp3.internal;

import java.io.Closeable;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Arrays;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.logging.Logger;
import javax.net.ServerSocketFactory;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import okhttp3.HttpUrl;
import okhttp3.Interceptor;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.internal.platform.Platform;
import okhttp3.mockwebserver.MockWebServer;
import okio.Buffer;
import okio.ByteString;

import static okhttp3.internal.Util.closeQuietly;

/**
 * Serves as a man-in-the-middle between an OkHttpClient and MockWebServer. Records all bytes
 * transferred between a given connection, and makes them available for inspection.
 */
public final class ConnectionRecorder {
  private static final Logger logger = Logger.getLogger(ConnectionRecorder.class.getName());

  private final ExecutorService executorService =
      Executors.newCachedThreadPool(Util.threadFactory("ConnectionRecorder", false));

  /** The client that will be instrumented to have all connections recorded. */
  private final OkHttpClient client;

  /** The target MWS we should forward bytes to and read bytes from. */
  private final MockWebServer mockWebServer;

  private final ServerSocketFactory serverSocketFactory = ServerSocketFactory.getDefault();
  private final SSLSocketFactory sslSocketFactory;
  private ServerSocket serverSocket;
  private int port;
  private List<Protocol> protocols = Arrays.asList(Protocol.HTTP_2, Protocol.HTTP_1_1);

  private final Deque<Conversation> conversations = new LinkedBlockingDeque<>();

  public ConnectionRecorder(OkHttpClient client, MockWebServer mockWebServer,
      SSLSocketFactory sslSocketFactory) {
    this.client = client;
    this.mockWebServer = mockWebServer;
    this.sslSocketFactory = sslSocketFactory;
  }

  /** Returns an OkHttpClient instrumented to use this man-in-the-middle. */
  public OkHttpClient newClient() throws IOException {
    start();
    return client.newBuilder()
        .addInterceptor(new Interceptor() {
          @Override public Response intercept(Chain chain) throws IOException {
            // Rewrite the request URL so that we actually connect to the MITM server.
            Request request = chain.request();
            HttpUrl url = request.url().newBuilder()
                .host(serverSocket.getInetAddress().getHostName())
                .port(serverSocket.getLocalPort())
                .build();
            request = request.newBuilder()
                .url(url)
                .build();
            return chain.proceed(request);
          }
        })
        .build();
  }

  private void start() throws IOException {
    InetSocketAddress inetSocketAddress = new InetSocketAddress("localhost", 0);
    serverSocket = serverSocketFactory.createServerSocket();
    serverSocket.bind(inetSocketAddress);
    port = serverSocket.getLocalPort();

    executorService.submit(new NamedRunnable("ConnectionRecorder %s", port) {
      @Override protected void execute() {
        try {
          while (true) {
            Socket socket = serverSocket.accept();
            logger.info("Intercepted request on [" + port + "] forwarding to ["
                + mockWebServer.getHostName() + ":" + mockWebServer.getPort() + "]");
            handleClientConnection(socket);
          }
        } catch (IOException e) {
          // TODO
        }
      }
    });
  }

  public void shutdown() {
    closeQuietly(serverSocket);
    executorService.shutdown();
  }

  public Conversation takeConversation() {
    Conversation conversation = conversations.remove();
    closeQuietly(conversation);
    return conversation;
  }

  void handleClientConnection(Socket rawClientSocket) throws IOException {
    // Terminate TLS for OkHttpClient.
    SSLSocket sslClientSocket = (SSLSocket) sslSocketFactory.createSocket(rawClientSocket,
        rawClientSocket.getInetAddress().getHostName(), rawClientSocket.getPort(), true);
    sslClientSocket.setUseClientMode(false);

    Platform.get().configureTlsExtensions(sslClientSocket, null, protocols);
    sslClientSocket.startHandshake();

    // Open a TLS connection to the MockWebServer.
    Socket rawMwsSocket = new Socket(mockWebServer.getHostName(), mockWebServer.getPort());
    SSLSocket sslMwsSocket = (SSLSocket) sslSocketFactory.createSocket(
        rawMwsSocket, rawMwsSocket.getInetAddress().getHostName(), rawMwsSocket.getPort(), true);
    sslMwsSocket.setUseClientMode(true);

    Platform.get().configureTlsExtensions(sslMwsSocket, null, protocols);
    sslMwsSocket.startHandshake();

    // We now have a TLS connection from client -> us, and from us -> MWS. Start 2 threads:
    // - One reading bytes from the client and delivering them to the server.
    // - The other reading bytes from the server and delivering them to the client.
    Conversation conversation = new Conversation(sslClientSocket, sslMwsSocket);
    conversations.add(conversation);
    executorService.submit(new ClientToServer(conversation));
    executorService.submit(new ServerToClient(conversation));
  }

  /** Records and copies bytes transmitted from the client to the server. */
  static final class ClientToServer extends NamedRunnable {
    private final Conversation conversation;

    ClientToServer(Conversation conversation) {
      super("ClientToServer %s -> %s", conversation.clientConnection.getPort(),
          conversation.serverConnection.getPort());
      this.conversation = conversation;
    }

    @Override protected void execute() {
      byte[] buffer = new byte[4096];
      int read;
      try {
        while ((read = conversation.clientConnection.getInputStream().read(buffer)) != -1) {
          conversation.readFromClient(buffer, read);
          conversation.serverConnection.getOutputStream().write(buffer, 0, read);
        }
      } catch (IOException e) {
        System.out.println("client exception " + e);
      } finally {
        closeQuietly(conversation.serverConnection);
        closeQuietly(conversation.clientConnection);
      }
    }
  }

  /** Records and copies bytes transmitted from the server to the client. */
  static final class ServerToClient extends NamedRunnable {
    private final Conversation conversation;

    ServerToClient(Conversation conversation) {
      super("ServerToClient %s -> %s", conversation.serverConnection.getPort(),
          conversation.clientConnection.getPort());
      this.conversation = conversation;
    }

    @Override protected void execute() {
      byte[] buffer = new byte[4096];
      int read;
      try {
        while ((read = conversation.serverConnection.getInputStream().read(buffer)) != -1) {
          conversation.readFromServer(buffer, read);
          conversation.clientConnection.getOutputStream().write(buffer, 0, read);
        }
      } catch (IOException e) {
        System.out.println("server exception " + e);
      } finally {
        closeQuietly(conversation.serverConnection);
        closeQuietly(conversation.clientConnection);
      }
    }
  }

  /** A bidirectional transfer of bytes between a client and server. */
  public static final class Conversation implements Closeable {
    final Socket clientConnection;
    final Socket serverConnection;

    // Reads and writes are guarded by this.
    private final Buffer readFromClient = new Buffer();
    private final Buffer readFromServer = new Buffer();

    Conversation(Socket clientConnection, Socket serverConnection) {
      this.clientConnection = clientConnection;
      this.serverConnection = serverConnection;
    }

    synchronized void readFromClient(byte[] bytes, int length) {
      readFromClient.write(bytes, 0, length);
    }

    synchronized void readFromServer(byte[] bytes, int length) {
      readFromServer.write(bytes, 0, length);
    }

    /** Returns all recorded bytes sent from the client. */
    public synchronized ByteString bytesFromClient() {
      return readFromClient.readByteString();
    }

    /** Returns all recorded bytes sent from the server. */
    public synchronized ByteString bytesFromServer() {
      return readFromServer.readByteString();
    }

    @Override public void close() throws IOException {
      closeQuietly(clientConnection);
      closeQuietly(serverConnection);
    }
  }
}
