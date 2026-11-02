/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.transportpoc.collector;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsExchange;
import com.sun.net.httpserver.HttpsServer;
import io.argos.agent.transportpoc.proto.Ack;
import io.argos.agent.transportpoc.proto.AckStatus;
import io.argos.agent.transportpoc.proto.AgentServiceGrpc;
import io.argos.agent.transportpoc.proto.TransactionBatch;
import io.grpc.ForwardingServerCallListener.SimpleForwardingServerCallListener;
import io.grpc.Grpc;
import io.grpc.Metadata;
import io.grpc.Server;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import io.grpc.ServerInterceptors;
import io.grpc.netty.GrpcSslContexts;
import io.grpc.netty.NettyServerBuilder;
import io.grpc.stub.StreamObserver;
import io.netty.handler.ssl.SslProvider;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.util.Properties;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSession;

/**
 * Local fake collector of the P-3 comparison (runs on JDK 21, never shipped): a grpc-netty server
 * with {@code AgentService.SendTransactions} and an HTTPS (HTTP/1.1) endpoint {@code POST
 * /argos.v1.AgentService/SendTransactions}, both on 127.0.0.1 with the certificate of a PKCS12 key
 * store. Every received batch becomes one JSON line in {@code <dir>/events.jsonl}; the ports go to
 * {@code <dir>/ready.properties} once both servers listen.
 *
 * <p>Usage: {@code FakeCollector <dir> <keystore.p12> <password>}. Stops when the process is
 * destroyed or {@code <dir>/stop} appears.
 */
public final class FakeCollector {
  private final Writer events;

  private FakeCollector(Writer events) {
    this.events = events;
  }

  public static void main(String[] args) throws Exception {
    File dir = new File(args[0]);
    if (!dir.isDirectory() && !dir.mkdirs()) {
      throw new IOException("cannot create " + dir);
    }
    char[] password = args[2].toCharArray();
    KeyStore store = KeyStore.getInstance("PKCS12");
    try (InputStream in = new FileInputStream(args[1])) {
      store.load(in, password);
    }
    KeyManagerFactory keys = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
    keys.init(store, password);

    FakeCollector collector =
        new FakeCollector(
            new OutputStreamWriter(
                new FileOutputStream(new File(dir, "events.jsonl"), true), StandardCharsets.UTF_8));
    // 127.0.0.1 (IPv4 is preferred by default); the agents connect to "localhost".
    InetAddress loopback = InetAddress.getLoopbackAddress();

    Server grpc =
        NettyServerBuilder.forAddress(new InetSocketAddress(loopback, 0))
            .sslContext(
                GrpcSslContexts.configure(
                        io.netty.handler.ssl.SslContextBuilder.forServer(keys), SslProvider.JDK)
                    .build())
            .permitKeepAliveTime(30, java.util.concurrent.TimeUnit.SECONDS)
            .permitKeepAliveWithoutCalls(true)
            .addService(ServerInterceptors.intercept(new Service(), collector.new Recorder()))
            .build()
            .start();

    SSLContext tls = SSLContext.getInstance("TLS");
    tls.init(keys.getKeyManagers(), null, null);
    HttpsServer http = HttpsServer.create(new InetSocketAddress(loopback, 0), 16);
    http.setHttpsConfigurator(new HttpsConfigurator(tls));
    http.createContext("/argos.v1.AgentService/SendTransactions", collector::handleHttp);
    http.start();

    Properties ready = new Properties();
    ready.setProperty("grpc.port", Integer.toString(grpc.getPort()));
    ready.setProperty("http.port", Integer.toString(http.getAddress().getPort()));
    File readyFile = new File(dir, "ready.properties");
    File tmp = new File(dir, "ready.properties.tmp");
    try (OutputStream out = new FileOutputStream(tmp)) {
      ready.store(out, "fake collector ports");
    }
    if (!tmp.renameTo(readyFile)) {
      throw new IOException("cannot write " + readyFile);
    }
    File stop = new File(dir, "stop");
    while (!stop.exists()) {
      Thread.sleep(200);
    }
    http.stop(0);
    grpc.shutdownNow();
  }

  private synchronized void record(
      String transport, TransactionBatch batch, SSLSession ssl, int bytes) {
    String line =
        "{\"ms\":"
            + System.currentTimeMillis()
            + ",\"transport\":\""
            + transport
            + "\",\"instance\":"
            + batch.getHeader().getInstanceId()
            + ",\"seq\":"
            + batch.getHeader().getSeq()
            + ",\"records\":"
            + batch.getRecordsCount()
            + ",\"bytes\":"
            + bytes
            + ",\"tls\":\""
            + (ssl == null ? "none" : ssl.getProtocol())
            + "\",\"cipher\":\""
            + (ssl == null ? "none" : ssl.getCipherSuite())
            + "\"}\n";
    try {
      events.write(line);
      events.flush();
    } catch (IOException e) {
      throw new IllegalStateException(e);
    }
  }

  private void handleHttp(HttpExchange exchange) throws IOException {
    try {
      if (!exchange.getRequestMethod().equals("POST")) {
        exchange.sendResponseHeaders(405, -1);
        return;
      }
      ByteArrayOutputStream body = new ByteArrayOutputStream();
      try (InputStream in = exchange.getRequestBody()) {
        byte[] buffer = new byte[8192];
        int n;
        while ((n = in.read(buffer)) >= 0) {
          body.write(buffer, 0, n);
        }
      }
      TransactionBatch batch = TransactionBatch.parseFrom(body.toByteArray());
      SSLSession ssl =
          exchange instanceof HttpsExchange ? ((HttpsExchange) exchange).getSSLSession() : null;
      record("http1", batch, ssl, body.size());
      byte[] ack = ack(batch).toByteArray();
      exchange.getResponseHeaders().set("Content-Type", "application/x-protobuf");
      exchange.sendResponseHeaders(200, ack.length);
      try (OutputStream out = exchange.getResponseBody()) {
        out.write(ack);
      }
    } finally {
      exchange.close();
    }
  }

  private static Ack ack(TransactionBatch batch) {
    return Ack.newBuilder()
        .setLastSeq(batch.getHeader().getSeq())
        .setStatus(AckStatus.ACK_OK)
        .build();
  }

  /** SendTransactions: acknowledges every batch (the Recorder logs it). */
  private static final class Service extends AgentServiceGrpc.AgentServiceImplBase {
    @Override
    public void sendTransactions(TransactionBatch request, StreamObserver<Ack> response) {
      response.onNext(ack(request));
      response.onCompleted();
    }
  }

  /** Logs each received batch with the TLS session of its connection. */
  private final class Recorder implements ServerInterceptor {
    @Override
    public <Q, R> ServerCall.Listener<Q> interceptCall(
        final ServerCall<Q, R> call, Metadata headers, ServerCallHandler<Q, R> next) {
      return new SimpleForwardingServerCallListener<Q>(next.startCall(call, headers)) {
        @Override
        public void onMessage(Q message) {
          if (message instanceof TransactionBatch) {
            TransactionBatch batch = (TransactionBatch) message;
            record(
                "grpc",
                batch,
                call.getAttributes().get(Grpc.TRANSPORT_ATTR_SSL_SESSION),
                batch.getSerializedSize());
          }
          super.onMessage(message);
        }
      };
    }
  }
}
