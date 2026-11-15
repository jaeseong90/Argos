/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.core.transport;

import com.google.protobuf.Message;
import io.argos.agent.bootstrap.ArgosThread;
import io.argos.proto.v1.Ack;
import io.argos.proto.v1.AgentMessage;
import io.argos.proto.v1.AgentServiceGrpc;
import io.argos.proto.v1.ServerMessage;
import io.grpc.CallCredentials;
import io.grpc.CallOptions;
import io.grpc.ClientCall;
import io.grpc.Grpc;
import io.grpc.ManagedChannel;
import io.grpc.Metadata;
import io.grpc.MethodDescriptor;
import io.grpc.ProxiedSocketAddress;
import io.grpc.ProxyDetector;
import io.grpc.Status;
import io.grpc.netty.GrpcSslContexts;
import io.grpc.netty.NettyChannelBuilder;
import io.netty.buffer.ByteBufAllocator;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoop;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.ssl.SslProvider;
import io.netty.handler.ssl.util.InsecureTrustManagerFactory;
import io.netty.util.internal.PlatformDependent;
import io.netty.util.internal.logging.InternalLoggerFactory;
import io.netty.util.internal.logging.JdkLoggerFactory;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.net.SocketAddress;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.util.Collection;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.SSLSession;
import javax.net.ssl.TrustManagerFactory;

/**
 * The gRPC channel of the agent with the fixed settings of 10 §10.3 (ADR-0005 decision 1, ADR-0009
 * C-72/C-73): {@code NettyChannelBuilder.forTarget(server.address)}, NIO only, one event loop
 * thread {@code argos-io} ({@link ArgosThread}), {@code directExecutor} and the event loop as
 * offload executor (no gRPC shared executor or timer thread), inbound messages up to 4 MiB,
 * keepalive 60 s / 20 s also without calls, no idle timer, connect timeout {@code
 * server.connect-timeout-ms}, no gRPC retry buffer and no DNS service-config lookup (the sender
 * retries itself, 11 §6). Netty runs without {@code Unsafe}, with unpooled heap buffers (also for
 * gRPC's own buffers) and the direct memory settings of {@link NettyProps}, which are set before
 * the first Netty class initialises. Netty logs through its JDK logger, which the agent jar
 * relocates to the agent log (ADR-0005 decision 4).
 *
 * <p>Destination (DSN-02-22, N-17): the channel connects to {@code server.address} only. It has a
 * proxy detector that never proxies ({@link #NO_PROXY}), so the application JVM's {@code
 * ProxySelector}, {@code Authenticator}, {@code http(s).proxyHost} and {@code
 * java.net.useSystemProxies}, and gRPC's {@code GRPC_PROXY_EXP}, are not followed: a proxy would be
 * a destination the operator did not configure for the agent, and in plaintext mode a third party
 * that sees the token. There is no proxy setting.
 *
 * <p>Connections follow the session (10 §10.2 "재접속"): the channel's idle timer is off, and whenever
 * the session's next attempt waits for its {@code Backoff} the session calls {@link #idle()}, which
 * replaces the channel with a new one from the same settings and ends the old one with {@code
 * shutdownNow()}. The new channel holds no connection and has not resolved the name yet; it
 * connects when the session opens its next stream. The old channel's own reconnects and name
 * lookups stop at once ({@code enterIdle()} would leave its subchannel reconnecting for gRPC's 5 s
 * subchannel shutdown delay). So TCP connects and DNS lookups happen once per session attempt, 1 s
 * to 30 s apart.
 *
 * <p>TLS (16 §3.4, SEC-07, SEC-08): {@code GrpcSslContexts} with {@link SslProvider#JDK} only;
 * trust is {@code server.tls.ca-file} (PEM) or the JDK default trust store; gRPC verifies the host
 * name of every connection. {@code server.tls.insecure-skip-verify} trusts any certificate (the
 * session warns on every connect); {@code server.tls: false} is plaintext (the session warns too).
 * On Java 8 before 8u252 the JDK has no ALPN and building the TLS context fails: {@link #create}
 * throws {@link UnsupportedTlsException} and the transport stays off (ADR-0005 decision 6).
 *
 * <p>Authentication: the token is attached to every call as {@code authorization: Bearer <token>}
 * through {@link CallCredentials} (11 §2, §11); data calls also carry {@code argos-instance-id} and
 * {@code argos-session-id}. Nothing here logs.
 */
final class GrpcTransport implements BatchTransport, SessionStream.Opener {
  static final int MAX_INBOUND_MESSAGE_BYTES = 4 * 1024 * 1024;
  static final long KEEPALIVE_SECONDS = 60;
  static final long KEEPALIVE_TIMEOUT_SECONDS = 20;
  static final String IO_THREAD = "argos-io";

  static final Metadata.Key<String> AUTHORIZATION =
      Metadata.Key.of("authorization", Metadata.ASCII_STRING_MARSHALLER);
  static final Metadata.Key<String> INSTANCE_ID =
      Metadata.Key.of("argos-instance-id", Metadata.ASCII_STRING_MARSHALLER);
  static final Metadata.Key<String> SESSION_ID =
      Metadata.Key.of("argos-session-id", Metadata.ASCII_STRING_MARSHALLER);

  /** TLS could not be set up on this JVM (Java 8 before 8u252: no ALPN). */
  static final class UnsupportedTlsException extends Exception {
    private static final long serialVersionUID = 1L;

    UnsupportedTlsException(Throwable cause) {
      super("TLS with ALPN is not available on this JVM", cause);
    }
  }

  /**
   * The proxy detector of the channel: never a proxy (DSN-02-22; see the class comment). Replaces
   * gRPC's default detector, which follows {@code java.net.ProxySelector}, {@code
   * java.net.Authenticator} and {@code GRPC_PROXY_EXP}.
   */
  static final ProxyDetector NO_PROXY = new NoProxy();

  /** {@link #NO_PROXY}. */
  private static final class NoProxy implements ProxyDetector {
    @Override
    public ProxiedSocketAddress proxyFor(SocketAddress targetServerAddress) {
      return null;
    }
  }

  /** The ids of the current session for the data call metadata. */
  interface SessionIds {
    long instanceId();

    String sessionId();
  }

  private final EventLoopGroup group;
  private final EventLoop loop;
  private final NettyChannelBuilder builder;
  private final CallCredentials credentials;
  private final SessionIds ids;

  /** The current channel; {@link #idle()} replaces it (event loop), any thread reads it. */
  private volatile ManagedChannel channel;

  /** {@link #shutdownNow()} was called: no channel is built any more. */
  private volatile boolean closed;

  private GrpcTransport(
      EventLoopGroup group,
      NettyChannelBuilder builder,
      CallCredentials credentials,
      SessionIds ids) {
    this.group = group;
    this.loop = group.next();
    this.builder = builder;
    this.channel = builder.build();
    this.credentials = credentials;
    this.ids = ids;
  }

  /**
   * Builds the channel of {@code settings} (no connection yet: the session opens it). Runs on
   * {@code argos-sender}, never on the premain thread.
   *
   * @throws UnsupportedTlsException TLS without ALPN (Java 8 before 8u252)
   * @throws Exception an unreadable {@code server.tls.ca-file} or another set-up failure
   */
  // NioEventLoopGroup is the 10 §10.3 event loop; Netty 4.1 has no replacement for it.
  @SuppressWarnings("deprecation")
  static GrpcTransport create(TransportSettings settings, String userAgent, SessionIds ids)
      throws Exception {
    NettyProps.apply();
    InternalLoggerFactory.setDefaultFactory(JdkLoggerFactory.INSTANCE);
    SslContext ssl = null;
    if (settings.tls) {
      ssl = sslContext(settings);
    }
    EventLoopGroup group =
        new io.netty.channel.nio.NioEventLoopGroup(1, ArgosThread.factory(IO_THREAD));
    try {
      NettyChannelBuilder builder =
          NettyChannelBuilder.forTarget(settings.target)
              .channelType(NioSocketChannel.class)
              .eventLoopGroup(group)
              .directExecutor()
              .offloadExecutor(group)
              .maxInboundMessageSize(MAX_INBOUND_MESSAGE_BYTES)
              .keepAliveTime(KEEPALIVE_SECONDS, TimeUnit.SECONDS)
              .keepAliveTimeout(KEEPALIVE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
              .keepAliveWithoutCalls(true)
              .idleTimeout(Long.MAX_VALUE, TimeUnit.DAYS)
              .disableRetry()
              .disableServiceConfigLookUp()
              .proxyDetector(NO_PROXY)
              .withOption(ChannelOption.ALLOCATOR, ByteBufAllocator.DEFAULT)
              .withOption(
                  ChannelOption.CONNECT_TIMEOUT_MILLIS,
                  (int) Math.min(Integer.MAX_VALUE, settings.connectTimeoutMillis))
              .userAgent(userAgent);
      if (ssl != null) {
        builder.sslContext(ssl);
      } else {
        builder.usePlaintext();
      }
      CallCredentials credentials =
          settings.token.isPresent() ? new TokenCredentials(settings.token.reveal()) : null;
      return new GrpcTransport(group, builder, credentials, ids);
    } catch (Exception | Error e) {
      io.netty.util.concurrent.Future<?> unused =
          group.shutdownGracefully(0, 0, TimeUnit.MILLISECONDS);
      throw e;
    }
  }

  /** The TLS context of 16 §3.4: JDK provider, CA file or JDK trust, or trust-all (skip-verify). */
  static SslContext sslContext(TransportSettings settings) throws Exception {
    SslContextBuilder builder;
    try {
      builder = GrpcSslContexts.configure(SslContextBuilder.forClient(), SslProvider.JDK);
    } catch (IllegalArgumentException | IllegalStateException | LinkageError e) {
      // GrpcSslContexts: "Could not find Jetty NPN/ALPN or Conscrypt as installed JDK providers".
      throw new UnsupportedTlsException(e);
    }
    if (settings.insecureSkipVerify) {
      builder.trustManager(InsecureTrustManagerFactory.INSTANCE);
    } else if (settings.caFile != null) {
      builder.trustManager(trustManagers(settings.caFile));
    }
    try {
      return builder.build();
    } catch (IllegalArgumentException | IllegalStateException e) {
      throw new UnsupportedTlsException(e);
    }
  }

  /** Trust managers of the PEM certificates in {@code caFile} ({@code server.tls.ca-file}). */
  static TrustManagerFactory trustManagers(File caFile) throws Exception {
    KeyStore store = KeyStore.getInstance(KeyStore.getDefaultType());
    store.load(null, null);
    CertificateFactory certificates = CertificateFactory.getInstance("X.509");
    try (InputStream in = new FileInputStream(caFile)) {
      Collection<? extends Certificate> all = certificates.generateCertificates(in);
      int index = 0;
      for (Certificate certificate : all) {
        store.setCertificateEntry("argos-ca-" + index++, certificate);
      }
      if (index == 0) {
        throw new IllegalArgumentException("server.tls.ca-file holds no certificate");
      }
    }
    TrustManagerFactory factory =
        TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
    factory.init(store);
    return factory;
  }

  /** The event loop of the session ({@code argos-io}). */
  Loop loop() {
    return new NettyLoop(loop);
  }

  /** True when Netty found {@code sun.misc.Unsafe} usable (expected false, 10 §10.3, C-74). */
  static boolean nettyUsesUnsafe() {
    return PlatformDependent.hasUnsafe();
  }

  /** Netty's direct memory limit (from {@link NettyProps}, 8 MiB). */
  static long nettyMaxDirectMemory() {
    return PlatformDependent.maxDirectMemory();
  }

  // --- data RPCs ---------------------------------------------------------------------------------

  @Override
  @SuppressWarnings("unchecked")
  public void send(
      BatchKind kind,
      Message batch,
      boolean gzip,
      long deadlineMillis,
      final BatchTransport.Callback callback) {
    MethodDescriptor<Message, Ack> method = (MethodDescriptor<Message, Ack>) method(kind);
    CallOptions options =
        CallOptions.DEFAULT.withDeadlineAfter(deadlineMillis, TimeUnit.MILLISECONDS);
    if (credentials != null) {
      options = options.withCallCredentials(credentials);
    }
    if (gzip) {
      options = options.withCompression("gzip");
    }
    final ClientCall<Message, Ack> call = channel.newCall(method, options);
    Metadata headers = new Metadata();
    headers.put(INSTANCE_ID, Long.toString(ids.instanceId()));
    headers.put(SESSION_ID, ids.sessionId());
    call.start(
        new ClientCall.Listener<Ack>() {
          private Ack ack;

          @Override
          public void onMessage(Ack message) {
            ack = message;
          }

          @Override
          public void onClose(Status status, Metadata trailers) {
            if (status.isOk() && ack != null) {
              callback.acknowledged(ack);
            } else if (status.isOk()) {
              callback.failed(Status.INTERNAL.withDescription("no Ack in the response"), trailers);
            } else {
              callback.failed(status, trailers);
            }
          }
        },
        headers);
    call.request(1);
    call.sendMessage(batch);
    call.halfClose();
  }

  private static MethodDescriptor<?, Ack> method(BatchKind kind) {
    switch (kind) {
      case TRANSACTIONS:
        return AgentServiceGrpc.getSendTransactionsMethod();
      case PROFILES:
        return AgentServiceGrpc.getSendProfilesMethod();
      case METRICS:
        return AgentServiceGrpc.getSendMetricsMethod();
      case STATS:
        return AgentServiceGrpc.getSendStatsMethod();
      default:
        return AgentServiceGrpc.getSendActiveSnapshotMethod();
    }
  }

  // --- session stream ----------------------------------------------------------------------------

  /**
   * No connection until the next {@link #open} (see the class comment): a new channel replaces the
   * current one, which ends with {@code shutdownNow()}, so its connection, its reconnects and its
   * name resolution stop at once. Calls still running on it fail (10 §10.2: the in-flight batches
   * of a lost session are failed; the sender retries them on the next session).
   *
   * <p>The replacement runs as a task of its own on the event loop, after the task that reported
   * the session's end: that report usually comes from inside gRPC's handling of the failed
   * connection, which is not the place to end the channel. The session's next attempt waits at
   * least its {@code Backoff} (800 ms), so this task always runs before it.
   */
  @Override
  public void idle() {
    try {
      loop.execute(
          new Runnable() {
            @Override
            public void run() {
              replaceChannel();
            }
          });
    } catch (java.util.concurrent.RejectedExecutionException e) {
      // The loop is shutting down: so is the channel.
    }
  }

  private void replaceChannel() {
    if (closed) {
      return;
    }
    ManagedChannel old = channel;
    ManagedChannel fresh = builder.build();
    channel = fresh;
    old.shutdownNow();
    if (closed) {
      // shutdownNow() ran meanwhile and may have seen the old channel only.
      fresh.shutdownNow();
    }
  }

  @Override
  public SessionStream open(final SessionStream.Listener listener) {
    // An idle channel (idle()) connects for this call: the session's Backoff (1 s to 30 s,
    // 10 §10.2) decides when that happens, not the channel's own connection back-off.
    CallOptions options = CallOptions.DEFAULT;
    if (credentials != null) {
      options = options.withCallCredentials(credentials);
    }
    final ClientCall<AgentMessage, ServerMessage> call =
        channel.newCall(AgentServiceGrpc.getSessionMethod(), options);
    final GrpcSessionStream stream = new GrpcSessionStream(call);
    call.start(
        new ClientCall.Listener<ServerMessage>() {
          @Override
          public void onHeaders(Metadata headers) {
            stream.headersReceived();
          }

          @Override
          public void onMessage(ServerMessage message) {
            listener.message(message);
            call.request(1);
          }

          @Override
          public void onReady() {
            listener.ready();
          }

          @Override
          public void onClose(Status status, Metadata trailers) {
            listener.closed(status, trailers);
          }
        },
        new Metadata());
    call.request(1);
    return stream;
  }

  /** One session call. */
  private static final class GrpcSessionStream implements SessionStream {
    private final ClientCall<AgentMessage, ServerMessage> call;
    private volatile String security = SessionClient.UNKNOWN;

    GrpcSessionStream(ClientCall<AgentMessage, ServerMessage> call) {
      this.call = call;
    }

    void headersReceived() {
      SSLSession ssl = call.getAttributes().get(Grpc.TRANSPORT_ATTR_SSL_SESSION);
      if (ssl == null) {
        security = SessionClient.PLAINTEXT;
      } else if (SessionClient.TLS_1_3.equals(ssl.getProtocol())) {
        security = SessionClient.TLS_1_3;
      } else if (SessionClient.TLS_1_2.equals(ssl.getProtocol())) {
        security = SessionClient.TLS_1_2;
      } else {
        security = SessionClient.TLS_OTHER;
      }
    }

    @Override
    public boolean isReady() {
      return call.isReady();
    }

    @Override
    public void send(AgentMessage message) {
      call.sendMessage(message);
    }

    @Override
    public void halfClose() {
      call.halfClose();
    }

    @Override
    public void cancel(String reason) {
      call.cancel(reason, null);
    }

    @Override
    public String security() {
      return security;
    }
  }

  // --- shutdown ----------------------------------------------------------------------------------

  /** Ends every call and the connection now, then the event loop (shutdown hook, 10 §10.2). */
  @SuppressWarnings("FutureReturnValueIgnored") // the JVM may exit before the loop ends
  void shutdownNow() {
    closed = true;
    channel.shutdownNow();
    group.shutdownGracefully(0, 200, TimeUnit.MILLISECONDS);
  }

  /** Waits at most {@code millis} for the channel to terminate (tests). */
  boolean awaitTermination(long millis) throws InterruptedException {
    return channel.awaitTermination(millis, TimeUnit.MILLISECONDS);
  }

  /** The {@link Loop} on Netty's event loop. */
  private static final class NettyLoop implements Loop {
    private final EventLoop loop;

    NettyLoop(EventLoop loop) {
      this.loop = loop;
    }

    @Override
    public void execute(Runnable task) {
      try {
        loop.execute(task);
      } catch (java.util.concurrent.RejectedExecutionException e) {
        // The loop is shutting down: the task is dropped.
      }
    }

    @Override
    public Task schedule(Runnable task, long delayMillis) {
      try {
        final ScheduledFuture<?> future =
            loop.schedule(task, Math.max(0L, delayMillis), TimeUnit.MILLISECONDS);
        return new Task() {
          @Override
          public void cancel() {
            future.cancel(false);
          }
        };
      } catch (java.util.concurrent.RejectedExecutionException e) {
        return new Task() {
          @Override
          public void cancel() {}
        };
      }
    }

    @Override
    public boolean inLoop() {
      return loop.inEventLoop();
    }
  }
}
