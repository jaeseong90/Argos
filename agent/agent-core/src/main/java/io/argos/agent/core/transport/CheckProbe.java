/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.core.transport;

import io.argos.agent.bootstrap.ArgosThread;
import io.argos.agent.core.config.AgentConfig;
import io.argos.agent.core.config.TokenHolder;
import io.argos.proto.v1.AgentBye;
import io.argos.proto.v1.AgentHello;
import io.argos.proto.v1.AgentMessage;
import io.argos.proto.v1.AgentServiceGrpc;
import io.argos.proto.v1.ByeReason;
import io.argos.proto.v1.ServerHello;
import io.argos.proto.v1.ServerMessage;
import io.grpc.Attributes;
import io.grpc.CallOptions;
import io.grpc.ClientCall;
import io.grpc.Grpc;
import io.grpc.ManagedChannel;
import io.grpc.Metadata;
import io.grpc.Status;
import io.grpc.netty.NettyChannelBuilder;
import io.netty.buffer.ByteBufAllocator;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.ssl.SslContext;
import io.netty.util.internal.logging.InternalLoggerFactory;
import io.netty.util.internal.logging.JdkLoggerFactory;
import java.security.SecureRandom;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.net.ssl.SSLPeerUnverifiedException;
import javax.net.ssl.SSLSession;

/**
 * One {@code AgentService.Session} round for {@code java -jar argos-agent.jar --check} (10 §13.3
 * items 3 to 5, F-A27): the agent's own gRPC stack with the settings of the transport ({@link
 * TransportSettings}: {@code server.address}, TLS with {@code server.tls.ca-file} or the JDK trust,
 * {@code insecure-skip-verify}), the token in the call credentials only ({@link TokenCredentials}),
 * never a proxy ({@link GrpcTransport#NO_PROXY}), a 5 s TCP connect timeout. It sends one {@code
 * AgentHello} (the instance name given by the caller, {@code <app.instance or host>-check}) with
 * {@code check_only} set (ADR-0010 C-240 (1)) and waits for the {@code ServerHello}. A server that
 * knows {@code check_only} answers it without a session (no {@code session_id}, instance id 0),
 * registers no instance and ends the stream itself; the probe then only half-closes. A server older
 * than this agent ignores the field and opens a normal session for the instance: the probe ends it
 * at once with {@code AgentBye{SHUTDOWN}} and a half-close, so that the server records a clean stop
 * (11 §7, C-214) and no down alert. Nothing else is sent: no batch, no heartbeat.
 *
 * <p>The result keeps what the check reports: the gRPC status and failure cause of a stream that
 * ended without a {@code ServerHello}, the TLS protocol and the server's certificates, the {@code
 * ServerHello} fields and the times for the clock offset. It holds no token. Nothing here prints or
 * logs (the check command prints the result, AGT-02).
 *
 * <p>Network: the configured {@code server.address} only, as in agent mode (DSN-02-22, T-BLD-011);
 * runs on the check command's thread and one {@code argos-io} event loop thread, never inside an
 * application.
 */
public final class CheckProbe {
  /** The TCP connect timeout of 10 §13.3 item 3. */
  public static final long CONNECT_TIMEOUT_MILLIS = 5_000;

  /** Longest wait for the {@code ServerHello} after the connection (the agent waits 30 s). */
  public static final long HELLO_TIMEOUT_MILLIS = 10_000;

  /** Longest wait for the server to end the stream after {@code AgentBye}. */
  static final long BYE_WAIT_MILLIS = 2_000;

  /** Upper bound of the certificates kept from the server's chain. */
  static final int MAX_CERTIFICATES = 10;

  /** What one probe found. Fields are set once by {@link #run}; the check command reads them. */
  public static final class Result {
    /**
     * The transport could not be set up (no request was sent): the reason, else null. {@link
     * #tlsUnsupported} tells the Java 8 before 8u252 case (no ALPN).
     */
    public String setupError;

    /** Java 8 before 8u252: TLS with ALPN is not available (ADR-0005 decision 6). */
    public boolean tlsUnsupported;

    /** The server's response headers (or a trailers-only answer) arrived on the stream. */
    public boolean serverAnswered;

    /**
     * The TLS protocol of the connection ({@code TLSv1.3}, {@code TLSv1.2}, ...), or null for a
     * plaintext connection or one that never reached the server's answer.
     */
    public String security;

    /** The certificates the server presented, leaf first (empty for plaintext or unknown). */
    public List<X509Certificate> certificates = Collections.emptyList();

    /** The {@code ServerHello}, or null. */
    public ServerHello hello;

    /** {@code AgentHello.sent_at_ms} (agent wall clock). */
    public long helloSentAtMs;

    /** Agent wall clock when the {@code ServerHello} arrived. */
    public long helloReceivedAtMs;

    /** True when no {@code ServerHello} and no end of the stream came within the timeouts. */
    public boolean timedOut;

    /** The gRPC status code name of the stream's end (null while it had not ended). */
    public String statusCode;

    /** The status description (English, never the token), or null. */
    public String statusDescription;

    /** The cause of a failed stream (connection, DNS, TLS), or null. */
    public Throwable cause;

    /** Trailer {@code argos-reject-reason} of a refused stream, or null. */
    public String rejectReasonTrailer;

    /**
     * {@code AgentBye{SHUTDOWN}} was written after the accepted {@code ServerHello} (a server that
     * opened a session for the check: one older than {@code check_only}, C-240 (1)).
     */
    public boolean byeSent;

    /** The instance name the hello carried. */
    public String instanceName;
  }

  private static final Metadata.Key<String> REJECT_REASON =
      Metadata.Key.of("argos-reject-reason", Metadata.ASCII_STRING_MARSHALLER);

  private CheckProbe() {}

  /**
   * The Hello of the check: {@code hello} (the agent's own, {@link AgentIdentity}) with {@code
   * AgentHello.check_only} set (ADR-0010 C-240 (1)). Only the check sets it; the agent's sessions
   * never do.
   */
  public static AgentHello checkOnly(AgentHello hello) {
    return hello.toBuilder().setCheckOnly(true).build();
  }

  /**
   * Whether {@code hello} is a server's answer to a check-only Hello: accepted without a session
   * ({@code session_id} empty), so no instance was registered (ADR-0010 C-240 (1)). A server older
   * than {@code check_only} answers with a session instead.
   */
  public static boolean checkOnlyAnswer(ServerHello hello) {
    return hello != null && hello.getAccepted() && hello.getSessionId().isEmpty();
  }

  /**
   * Opens a session to {@code config}'s {@code server.address} with {@code token}, sends one hello
   * for {@code instanceName} and ends an accepted session with {@code AgentBye}; never throws.
   *
   * @param config the loaded configuration ({@code server.address} must be set)
   * @param token the effective token (may be {@link TokenHolder#NONE}; the server then refuses)
   * @param agentVersion {@code AgentHello.agent_version}
   * @param configHash {@code AgentHello.config_hash}
   * @param instanceName {@code AgentHello.instance_name}
   */
  // NioEventLoopGroup is the 10 §10.3 event loop; Netty 4.1 has no replacement for it.
  @SuppressWarnings("deprecation")
  public static Result run(
      AgentConfig config,
      TokenHolder token,
      String agentVersion,
      String configHash,
      String instanceName) {
    Result result = new Result();
    result.instanceName = instanceName;
    TransportSettings settings = TransportSettings.of(config, token);
    if (settings.target == null) {
      result.setupError = "server.address is not set";
      return result;
    }
    SslContext ssl = null;
    try {
      NettyProps.apply();
      InternalLoggerFactory.setDefaultFactory(JdkLoggerFactory.INSTANCE);
      if (settings.tls) {
        ssl = GrpcTransport.sslContext(settings);
      }
    } catch (GrpcTransport.UnsupportedTlsException e) {
      result.tlsUnsupported = true;
      result.setupError = "TLS with ALPN is not available on this JVM";
      return result;
    } catch (Exception | LinkageError e) {
      result.setupError = describe(e);
      return result;
    }
    EventLoopGroup group =
        new io.netty.channel.nio.NioEventLoopGroup(1, ArgosThread.factory(GrpcTransport.IO_THREAD));
    ManagedChannel channel = null;
    try {
      NettyChannelBuilder builder =
          NettyChannelBuilder.forTarget(settings.target)
              .channelType(NioSocketChannel.class)
              .eventLoopGroup(group)
              .directExecutor()
              .offloadExecutor(group)
              .maxInboundMessageSize(GrpcTransport.MAX_INBOUND_MESSAGE_BYTES)
              .disableRetry()
              .disableServiceConfigLookUp()
              .proxyDetector(GrpcTransport.NO_PROXY)
              .withOption(ChannelOption.ALLOCATOR, ByteBufAllocator.DEFAULT)
              .withOption(ChannelOption.CONNECT_TIMEOUT_MILLIS, (int) CONNECT_TIMEOUT_MILLIS)
              .userAgent("argos-agent/" + agentVersion + " check");
      if (ssl != null) {
        builder.sslContext(ssl);
      } else {
        builder.usePlaintext();
      }
      channel = builder.build();
      CallOptions options = CallOptions.DEFAULT;
      if (token != null && token.isPresent()) {
        options = options.withCallCredentials(new TokenCredentials(token.reveal()));
      }
      exchange(channel, options, config, agentVersion, configHash, instanceName, result);
    } catch (RuntimeException | LinkageError e) {
      if (result.statusCode == null) {
        result.setupError = describe(e);
      }
    } finally {
      if (channel != null) {
        channel.shutdownNow();
        try {
          channel.awaitTermination(1, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
        }
      }
      io.netty.util.concurrent.Future<?> unused =
          group.shutdownGracefully(0, 200, TimeUnit.MILLISECONDS);
    }
    return result;
  }

  private static void exchange(
      ManagedChannel channel,
      CallOptions options,
      AgentConfig config,
      String agentVersion,
      String configHash,
      String instanceName,
      final Result result) {
    final ClientCall<AgentMessage, ServerMessage> call =
        channel.newCall(AgentServiceGrpc.getSessionMethod(), options);
    final CountDownLatch answered = new CountDownLatch(1);
    final CountDownLatch closed = new CountDownLatch(1);
    final AgentIdentity identity =
        new AgentIdentity(
            config,
            agentVersion,
            configHash,
            Collections.<String>emptyList(),
            new SecureRandom().nextLong(),
            !config.serverTls());
    identity.instanceName(instanceName);
    final AtomicBoolean helloSent = new AtomicBoolean();
    call.start(
        new ClientCall.Listener<ServerMessage>() {
          @Override
          public void onHeaders(Metadata headers) {
            result.serverAnswered = true;
            readSecurity(call.getAttributes(), result);
          }

          @Override
          public void onReady() {
            // The hello is written once the connection and the stream exist, so that its
            // sent_at_ms does not count the TCP connect and the TLS handshake (clock, item 5).
            if (helloSent.compareAndSet(false, true)) {
              AgentHello hello = checkOnly(identity.hello());
              result.helloSentAtMs = hello.getSentAtMs();
              call.sendMessage(AgentMessage.newBuilder().setHello(hello).build());
            }
          }

          @Override
          public void onMessage(ServerMessage message) {
            if (message.hasHello() && result.hello == null) {
              result.helloReceivedAtMs = System.currentTimeMillis();
              result.hello = message.getHello();
              answered.countDown();
            }
            call.request(1);
          }

          @Override
          public void onClose(Status status, Metadata trailers) {
            if (result.security == null) {
              readSecurity(call.getAttributes(), result);
            }
            result.statusCode = status.getCode().name();
            result.statusDescription = status.getDescription();
            result.cause = status.getCause();
            if (trailers != null) {
              result.rejectReasonTrailer = trailers.get(REJECT_REASON);
            }
            answered.countDown();
            closed.countDown();
          }
        },
        new Metadata());
    call.request(1);
    boolean done = await(answered, CONNECT_TIMEOUT_MILLIS + HELLO_TIMEOUT_MILLIS);
    if (!done) {
      result.timedOut = true;
      call.cancel("check: no ServerHello", null);
      await(closed, BYE_WAIT_MILLIS);
      return;
    }
    if (checkOnlyAnswer(result.hello) && result.statusCode == null) {
      // C-240 (1): no session to end; the server ends the stream after its answer.
      call.halfClose();
      if (!await(closed, BYE_WAIT_MILLIS)) {
        call.cancel("check done", null);
      }
    } else if (result.hello != null && result.hello.getAccepted() && result.statusCode == null) {
      // An older server opened a session for the check (it ignores check_only).
      // 11 §7: end the check's session cleanly, as an agent that shuts down does (C-214).
      call.sendMessage(
          AgentMessage.newBuilder()
              .setBye(AgentBye.newBuilder().setReason(ByeReason.SHUTDOWN).build())
              .build());
      call.halfClose();
      result.byeSent = true;
      if (!await(closed, BYE_WAIT_MILLIS)) {
        call.cancel("check done", null);
      }
    } else if (result.statusCode == null) {
      call.cancel("check done", null);
      await(closed, BYE_WAIT_MILLIS);
    }
  }

  /** The TLS protocol and the server's certificates of the call's connection. */
  static void readSecurity(Attributes attributes, Result result) {
    SSLSession session =
        attributes == null ? null : attributes.get(Grpc.TRANSPORT_ATTR_SSL_SESSION);
    if (session == null) {
      // Plaintext, or not known yet: the check command tells them apart by server.tls.
      return;
    }
    result.security = session.getProtocol();
    List<X509Certificate> certificates = new ArrayList<X509Certificate>();
    try {
      for (Certificate certificate : session.getPeerCertificates()) {
        if (certificate instanceof X509Certificate && certificates.size() < MAX_CERTIFICATES) {
          certificates.add((X509Certificate) certificate);
        }
      }
    } catch (SSLPeerUnverifiedException e) {
      // No certificates to report.
    }
    result.certificates = Collections.unmodifiableList(certificates);
  }

  private static boolean await(CountDownLatch latch, long millis) {
    try {
      return latch.await(millis, TimeUnit.MILLISECONDS);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return false;
    }
  }

  private static String describe(Throwable t) {
    String message = t.getMessage();
    return t.getClass().getSimpleName()
        + (message == null ? "" : ": " + TokenHolder.maskTokens(message));
  }
}
