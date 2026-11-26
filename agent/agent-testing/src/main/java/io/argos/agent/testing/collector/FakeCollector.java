/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.testing.collector;

import com.google.protobuf.ByteString;
import com.google.protobuf.Message;
import io.argos.agent.testing.collector.v1.V1Protocol;
import io.argos.proto.ProtocolVersion;
import io.grpc.Server;
import io.grpc.Status;
import io.grpc.netty.GrpcSslContexts;
import io.grpc.netty.NettyServerBuilder;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.ssl.SslProvider;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * The fake collector of the agent tests (18 §5.3, DSN-18-04): a gRPC {@code AgentService} of
 * argos.v1 ({@code :proto}) inside the test JVM (Netty, 127.0.0.1 by default, so an agent in
 * another JVM can connect) that records every batch as received ({@link ReceivedBatch}: instance,
 * seq, receive time, body) and answers by the 11 §16 contract, through the {@link FaultInjector}.
 * It is the "sent count" reference: no size limits, no quotas (that is the server's job, WO-21).
 *
 * <pre>
 * try (FakeCollector collector = FakeCollector.startTls()) {
 *   collector.faults().latency(Duration.ofMillis(200));
 *   try (RunningApp app = AppLauncher.of(SampleApp.named("shop-boot3"), Jdk.of(21))
 *       .withAgent(agentJar).collector(collector).workDir(dir).start()) {
 *     ...
 *     assertThat(app.collected()).transactions().hasSize(1000);
 *     ConfigApplyOutcome result = collector.pushConfig(Map.of("enabled", "false")).get(10, SECONDS);
 *   }
 * }
 * </pre>
 *
 * <p>Behaviour (each item cites its 11 clause; the contract suite {@code
 * io.argos.agent.testing.contract} and {@code contract/argos-v1-vectors.json} pin it for WO-21):
 *
 * <ul>
 *   <li>11 §5 session: {@code AgentHello} → {@code ServerHello} with an instance id ({@link
 *       Builder#firstInstanceId}, reused for the same {@code (app_name, env, instance_name)} in
 *       every session, also after {@link FaultInjector#serverRestart()}, 11 §5 "재기동 연속성"; an {@code
 *       InstanceUpdate.instance_name} of a session renames the instance and keeps its id, 12 §4.3
 *       name priority, so a later hello with the new name such as {@code host:port} gets the same
 *       id, {@link #onInstanceName}), a new UUID {@code session_id}, {@code server_time_ms}, the 11
 *       §4.2 {@code Limits} defaults ({@code ProtocolLimits.defaults()}), {@code
 *       heartbeat_interval_s} ({@link Builder#heartbeatIntervalS}) and {@code config}. An instance
 *       has one session (12 §4.1): a new hello sends the open session of that instance {@code
 *       SessionClose{REPLACED}} and ends its stream (11 §5 check 7), and the old session id is
 *       refused from then on, also when its stream had already ended. A Hello with {@code
 *       check_only} (ADR-0010 C-240 (1), {@code --check}) is answered accepted with instance id 0
 *       and no session id, recorded ({@link SessionInfo#checkOnly}) and its stream ended at once:
 *       no instance id is assigned and no session registered ({@link Builder#ignoreCheckOnly}
 *       answers it as a server older than the field does).
 *   <li>11 §2, §3 data calls need {@code authorization: Bearer argos_<base64url>}, {@code
 *       argos-instance-id} and {@code argos-session-id} of an accepted session; otherwise {@code
 *       UNAUTHENTICATED} or {@code FAILED_PRECONDITION} ({@code SESSION_REQUIRED}). A session stays
 *       known after its stream ends (the agent's shutdown flush after {@code AgentBye}, 11 §6)
 *       until {@link FaultInjector#serverRestart()}, {@link FaultInjector#sessionClose} or a new
 *       hello of the instance. {@code sessionClose(TOKEN_REVOKED_CLOSE)} also revokes the token (11
 *       §10, 12 §3.1, §4.1): its data calls and hellos are then {@code UNAUTHENTICATED} with {@code
 *       TOKEN_REVOKED}.
 *   <li>11 §6 deduplication ({@link Deduplicator}): {@code ACK_DUPLICATE} with {@code last_seq};
 *       the duplicate is still recorded (flagged), so the sent count stays exact. The windows
 *       survive a {@link FaultInjector#serverRestart()} (12 §3.4: the server rebuilds them from the
 *       WAL), so the reused instance id still finds its window (D-13).
 *   <li>11 §9 clock ({@link ClockEstimator}): every heartbeat is answered with t2/t3 (shifted by
 *       {@link FaultInjector#clockOffset}) and completes the previous round; {@link
 *       #clockOffsetMs(long)} is the instance's estimate.
 *   <li>11 §10 status codes and trailers ({@link FaultInjector}).
 * </ul>
 *
 * <p>Wire contract: {@link CollectorProtocol}, implemented by {@link V1Protocol}.
 */
public final class FakeCollector implements AutoCloseable {
  /** {@code ServerHello.heartbeat_interval_s} default (11 §3: 30 s). */
  public static final int DEFAULT_HEARTBEAT_INTERVAL_S = 30;

  /** {@code ServerHello.server_version}. */
  public static final String SERVER_VERSION = "argos-fake-collector";

  /** Host name of the Docker host as a container sees it ({@link #containerAddress()}). */
  public static final String DOCKER_HOST = "host.docker.internal";

  /** |offset| at or above which {@code HeartbeatAck.state} is {@code WARN} (12 §4.2, F-S28). */
  static final long CLOCK_WARN_MS = 1_000;

  private final CollectorProtocol protocol;
  private final Path tlsDirectory;
  private final FaultInjector faults = new FaultInjector(this);
  private final String bindHost;
  private final int flowControlWindow;
  private final int heartbeatIntervalS;
  private volatile Map<String, String> helloConfig;
  private final ByteString userIdSalt;
  private final boolean retainBatches;
  private final boolean ignoreCheckOnly;
  private final String token;
  private final LongSupplier clock;
  private final Object registry = new Object();
  private final List<ReceivedBatch> batches = Collections.synchronizedList(new ArrayList<>());
  private final Map<BatchKind, KindCounts> counts = new EnumMap<>(BatchKind.class);
  private final List<SessionInfo> sessions = new CopyOnWriteArrayList<>();
  private final List<Message> sessionMessages = Collections.synchronizedList(new ArrayList<>());
  private final List<HeartbeatRecord> heartbeats = Collections.synchronizedList(new ArrayList<>());
  private final List<ByeInfo> byes = new CopyOnWriteArrayList<>();
  private final List<AgentEventInfo> agentEvents = new CopyOnWriteArrayList<>();
  private final List<ConfigHashUpdate> configHashUpdates = new CopyOnWriteArrayList<>();
  private final Map<Long, String> dictionary = new ConcurrentHashMap<>();
  private final List<Collected.DictionaryItem> dictionaryItems =
      Collections.synchronizedList(new ArrayList<>());
  private final Map<CollectorProtocol.SessionChannel, LiveSession> openSessions =
      new ConcurrentHashMap<>();
  private final Set<CollectorProtocol.SessionChannel> streams = ConcurrentHashMap.newKeySet();
  private final Map<String, LiveSession> sessionsById = new ConcurrentHashMap<>();
  private final Map<String, Long> instanceIds = new ConcurrentHashMap<>();
  private final Map<Long, ClockEstimator> clocks = new ConcurrentHashMap<>();
  private final Deduplicator dedup = new Deduplicator();
  private final List<CollectorProtocol.UnaryReply> hung = new CopyOnWriteArrayList<>();
  private final Map<String, CompletableFuture<ConfigApplyOutcome>> configResults =
      new ConcurrentHashMap<>();
  private final Map<String, Long> configSentNanos = new ConcurrentHashMap<>();
  private final Map<String, CommandAccumulator> commandResults = new ConcurrentHashMap<>();
  private final AtomicLong nextInstanceId;
  private final AtomicInteger helloNumbers = new AtomicInteger();
  private final AtomicInteger changeIds = new AtomicInteger();
  private final AtomicInteger unaryInFlight = new AtomicInteger();
  private final AtomicInteger unaryMaxInFlight = new AtomicInteger();
  private final ScheduledExecutorService scheduler =
      Executors.newSingleThreadScheduledExecutor(
          runnable -> {
            Thread thread = new Thread(runnable, "fake-collector-replies");
            thread.setDaemon(true);
            return thread;
          });

  private Server server;
  private int port;
  private boolean tlsActive;
  private TlsMaterial tlsMaterial;

  /** One accepted session (the registry entry of 12 §4.1). */
  private record LiveSession(
      String sessionId,
      long instanceId,
      ReceivedToken token,
      CollectorProtocol.SessionChannel channel,
      String appName,
      String env) {}

  /** The {@link #instanceIds} key of an instance name of one app and environment (11 §5). */
  private static String instanceKey(String appName, String env, String instanceName) {
    return appName + '\u0000' + env + '\u0000' + instanceName;
  }

  private FakeCollector(Builder builder) {
    this.protocol = builder.protocol != null ? builder.protocol : new V1Protocol();
    this.tlsDirectory =
        builder.tlsDirectory != null
            ? builder.tlsDirectory
            : Path.of(System.getProperty("java.io.tmpdir"));
    this.nextInstanceId = new AtomicLong(builder.firstInstanceId);
    this.tlsActive = builder.tls;
    this.bindHost = builder.bindAddress;
    this.flowControlWindow = builder.flowControlWindow;
    this.port = builder.port;
    this.heartbeatIntervalS = builder.heartbeatIntervalS;
    this.helloConfig = Map.copyOf(builder.config);
    this.userIdSalt = ByteString.copyFrom(builder.userIdSalt);
    this.retainBatches = builder.retainBatches;
    this.ignoreCheckOnly = builder.ignoreCheckOnly;
    this.token = builder.token != null ? builder.token : newToken();
    this.clock = builder.clock;
  }

  /** A plaintext collector on a free loopback port. */
  public static FakeCollector start() {
    return builder().start();
  }

  /** A TLS collector (self-signed certificate for {@code localhost}, see {@link #trustPem()}). */
  public static FakeCollector startTls() {
    return builder().tls(true).start();
  }

  public static Builder builder() {
    return new Builder();
  }

  /** Options of {@link #builder()}. */
  public static final class Builder {
    private boolean tls;
    private CollectorProtocol protocol;
    private long firstInstanceId = 1;
    private Path tlsDirectory;
    private String bindAddress;
    private int port;
    private int flowControlWindow;
    private int heartbeatIntervalS = DEFAULT_HEARTBEAT_INTERVAL_S;
    private Map<String, String> config = Map.of();
    private byte[] userIdSalt = new byte[0];
    private boolean retainBatches = true;
    private boolean ignoreCheckOnly;
    private String token;
    private LongSupplier clock = System::currentTimeMillis;

    private Builder() {}

    /**
     * Answer a {@code check_only} Hello like a normal one (default false): a server older than
     * ADR-0010 C-240 (1), which does not know the field, registers the check's instance.
     */
    public Builder ignoreCheckOnly(boolean on) {
      this.ignoreCheckOnly = on;
      return this;
    }

    /** TLS with a throw-away self-signed certificate for {@code localhost}. */
    public Builder tls(boolean on) {
      this.tls = on;
      return this;
    }

    /** The wire contract (default: {@link V1Protocol}). */
    public Builder protocol(CollectorProtocol value) {
      this.protocol = value;
      return this;
    }

    /** The instance id of the first accepted hello (then +1 per new instance). */
    public Builder firstInstanceId(long id) {
      this.firstInstanceId = id;
      return this;
    }

    /** Where the temporary TLS key store goes (default {@code java.io.tmpdir}). */
    public Builder tlsDirectory(Path directory) {
      this.tlsDirectory = directory;
      return this;
    }

    /**
     * The address to listen on (default: the IPv4 loopback). Tests only: {@code "0.0.0.0"} lets an
     * agent in a container reach the collector through {@value #DOCKER_HOST} on a native Linux
     * Docker host (AppLauncher container mode); it exposes the collector on every interface of the
     * machine for the test's duration.
     */
    public Builder bindAddress(String host) {
      this.bindAddress = host;
      return this;
    }

    /** A fixed port (default 0: a free port). */
    public Builder port(int value) {
      this.port = value;
      return this;
    }

    /**
     * A fixed HTTP/2 flow control window in bytes (default: gRPC's 1 MiB, tuned up by BDP). A small
     * one lets {@link FaultInjector#pauseSessionReads()} stop a client's stream after little data.
     */
    public Builder flowControlWindow(int bytes) {
      if (bytes < 1) {
        throw new IllegalArgumentException("flow control window must be >= 1 byte");
      }
      this.flowControlWindow = bytes;
      return this;
    }

    /** {@code ServerHello.heartbeat_interval_s} (default 30; tests use 1 or 2). */
    public Builder heartbeatIntervalS(int seconds) {
      if (seconds < 1) {
        throw new IllegalArgumentException("heartbeat interval must be >= 1 s");
      }
      this.heartbeatIntervalS = seconds;
      return this;
    }

    /** {@code ServerHello.config} of every accepted hello. */
    public Builder config(Map<String, String> values) {
      this.config = Map.copyOf(values);
      return this;
    }

    /** {@code ServerHello.user_id_salt}, sent over TLS only (16 §6.3); default none. */
    public Builder userIdSalt(byte[] salt) {
      this.userIdSalt = salt.clone();
      return this;
    }

    /**
     * Keep every {@link ReceivedBatch} (default true). The standalone collector turns it off and
     * keeps only {@link #counts()} so that a long bench run does not grow the heap.
     */
    public Builder retainBatches(boolean on) {
      this.retainBatches = on;
      return this;
    }

    /** The test token of {@link #token()} (default: random per collector). */
    public Builder token(String value) {
      this.token = value;
      return this;
    }

    /**
     * The collector's wall clock in ms (default {@code System::currentTimeMillis}; 18 §19
     * injectable timestamps): {@code ServerHello.server_time_ms} and {@code HeartbeatAck.t2/t3}
     * read it (plus {@link FaultInjector#clockOffset}), so a test can replay fixed 11 §9 inputs.
     */
    public Builder clock(LongSupplier wallClockMs) {
      this.clock = wallClockMs;
      return this;
    }

    public FakeCollector start() {
      FakeCollector collector = new FakeCollector(this);
      collector.startServer(false);
      return collector;
    }
  }

  // --- server lifecycle -------------------------------------------------------------------------

  /**
   * Inbound message and metadata limits: none in practice. 18 §5.3 says the fake collector checks
   * no receive limit and no quota and records what the agent sent as it is (the "sent count"
   * reference); gRPC's default of 4 MiB per message would reject a larger batch with {@code
   * RESOURCE_EXHAUSTED} before it is recorded. Size limits are the server's job (11 §4).
   */
  static final int NO_INBOUND_LIMIT = Integer.MAX_VALUE;

  private synchronized void startServer(boolean wrongHost) {
    try {
      InetAddress address =
          bindHost == null ? InetAddress.getLoopbackAddress() : InetAddress.getByName(bindHost);
      NettyServerBuilder builder =
          NettyServerBuilder.forAddress(new InetSocketAddress(address, port))
              .permitKeepAliveTime(1, TimeUnit.SECONDS)
              .permitKeepAliveWithoutCalls(true)
              .maxInboundMessageSize(NO_INBOUND_LIMIT)
              .maxInboundMetadataSize(NO_INBOUND_LIMIT)
              .addService(protocol.service(this));
      if (flowControlWindow > 0) {
        builder.flowControlWindow(flowControlWindow);
      }
      if (tlsActive) {
        String host = wrongHost ? "wrong.example" : "localhost";
        if (tlsMaterial == null || !tlsMaterial.host().equals(host)) {
          if (tlsMaterial != null) {
            tlsMaterial.delete();
          }
          tlsMaterial = TlsMaterial.generate(tlsDirectory, host);
        }
        builder.sslContext(
            GrpcSslContexts.configure(
                    SslContextBuilder.forServer(tlsMaterial.keyManagers()), SslProvider.JDK)
                .build());
      }
      server = builder.build().start();
      port = server.getPort();
    } catch (IOException e) {
      throw new UncheckedIOException("cannot start the fake collector on port " + port, e);
    }
  }

  /**
   * {@link FaultInjector#serverRestart()}, {@code plaintextOnly}, {@code wrongHostCertificate}
   * ({@code newInstanceId} -1: the instances and their dedup windows are kept), and {@link
   * FaultInjector#serverRestart(long)} (the instance registry is lost: new ids from {@code
   * newInstanceId}).
   */
  synchronized void restart(long newInstanceId, boolean tls, boolean wrongHost) {
    Status status = Status.UNAVAILABLE.withDescription("FakeCollector restart");
    closeSessions(status);
    releaseHung(status);
    server.shutdownNow();
    try {
      server.awaitTermination(10, TimeUnit.SECONDS);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("interrupted while restarting the fake collector", e);
    }
    synchronized (registry) {
      // A restarted server knows no session (data calls get SESSION_REQUIRED, 11 §3), but it keeps
      // the instance ids of (app, instance_name) (11 §5) and the dedup windows (12 §3.4, D-13).
      sessionsById.clear();
      if (newInstanceId >= 0) {
        // serverRestart(long): the instance registry is lost.
        nextInstanceId.set(newInstanceId);
        instanceIds.clear();
      }
    }
    tlsActive = tls;
    startServer(wrongHost);
  }

  /** Ends every open session stream with {@code status} (the sessions stay known). */
  void closeSessions(Status status) {
    for (CollectorProtocol.SessionChannel channel : new ArrayList<>(openSessions.keySet())) {
      openSessions.remove(channel);
      channel.close(status, Map.of());
    }
  }

  /**
   * {@link FaultInjector#sessionClose}: {@code SessionClose}, end of stream, session forgotten;
   * {@code TOKEN_REVOKED_CLOSE} revokes the session's token first (12 §4.1 {@code closeByToken}:
   * every session of the token leaves the registry, also one whose stream had already ended), so an
   * agent that reacts to the close at once already meets the revocation (11 §5 check 1).
   */
  void sessionCloseAll(String reason, int retryAfterS) {
    Status.Code code = FaultInjector.closeStatusOf(reason);
    boolean revoke = "TOKEN_REVOKED_CLOSE".equals(reason);
    Map<String, String> trailers =
        retryAfterS > 0
            ? Map.of(FaultInjector.RETRY_AFTER_TRAILER, Long.toString(retryAfterS * 1000L))
            : Map.of();
    for (CollectorProtocol.SessionChannel channel : new ArrayList<>(openSessions.keySet())) {
      LiveSession live;
      synchronized (registry) {
        live = openSessions.remove(channel);
        if (live != null) {
          sessionsById.remove(live.sessionId());
          if (revoke) {
            faults.revoke(live.token());
            sessionsById.values().removeIf(old -> faults.revoked(old.token()));
          }
        }
      }
      synchronized (channel) {
        channel.sessionClose(reason, "FakeCollector fault: sessionClose", retryAfterS);
        channel.close(
            code == Status.Code.OK ? Status.OK : Status.fromCode(code).withDescription(reason),
            trailers);
      }
    }
  }

  /** Ends every hung unary call with {@code status}. */
  void releaseHung(Status status) {
    for (CollectorProtocol.UnaryReply reply : new ArrayList<>(hung)) {
      hung.remove(reply);
      reply.fail(status, Map.of());
    }
  }

  /** {@link FaultInjector#resumeSessionReads()}: every stream reads on. */
  void resumeSessionReads() {
    for (CollectorProtocol.SessionChannel stream : streams) {
      stream.resumeReads();
    }
  }

  /** Stops the server, ends open calls and streams, deletes the TLS material. */
  @Override
  public synchronized void close() {
    Status status = Status.UNAVAILABLE.withDescription("FakeCollector closed");
    closeSessions(status);
    releaseHung(status);
    if (server != null) {
      server.shutdownNow();
      try {
        server.awaitTermination(10, TimeUnit.SECONDS);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    }
    scheduler.shutdownNow();
    if (tlsMaterial != null) {
      tlsMaterial.delete();
      tlsMaterial = null;
    }
    for (CompletableFuture<ConfigApplyOutcome> pending : configResults.values()) {
      pending.completeExceptionally(new IllegalStateException("FakeCollector closed"));
    }
    configSentNanos.clear();
    for (CommandAccumulator pending : commandResults.values()) {
      pending.future.completeExceptionally(new IllegalStateException("FakeCollector closed"));
    }
  }

  // --- public view ------------------------------------------------------------------------------

  public synchronized int port() {
    return port;
  }

  /**
   * {@code 127.0.0.1:<port>} for the agent's {@code server.address}, with and without TLS: the
   * server binds the IPv4 loopback by default ({@code localhost} may resolve to {@code ::1} first),
   * and the TLS certificate names {@code localhost} and IP {@code 127.0.0.1}, so host name
   * verification holds for this address.
   */
  public synchronized String address() {
    return "127.0.0.1:" + port;
  }

  /**
   * {@code host.docker.internal:<port>}: the address for an agent in a container (AppLauncher
   * container mode adds {@code --add-host host.docker.internal:host-gateway}). The TLS certificate
   * also names {@value #DOCKER_HOST}. On a native Linux Docker host the collector must listen
   * beyond the loopback ({@link Builder#bindAddress}).
   */
  public synchronized String containerAddress() {
    return DOCKER_HOST + ":" + port;
  }

  /** True while the server speaks TLS. */
  public synchronized boolean tls() {
    return tlsActive;
  }

  /** The PEM certificate a TLS client must trust ({@code server.tls.ca-file}); null without TLS. */
  public synchronized Path trustPem() {
    return tlsActive && tlsMaterial != null ? tlsMaterial.certificate() : null;
  }

  /**
   * The per-collector test token ({@code argos_<base64url>}, random unless {@link Builder#token}).
   * AppLauncher writes it to the run's token file ({@code server.token-file}); it never goes on a
   * command line or into a log. The collector accepts any well-formed token unless {@link
   * FaultInjector#requireToken} narrows it.
   */
  public String token() {
    return token;
  }

  /**
   * The agent settings that point an agent to this collector (10 §15 keys): {@code server.address},
   * {@code server.tls} and, with TLS, {@code server.tls.ca-file}. The token is not among them (it
   * goes into a file, see {@link #token()}).
   */
  public synchronized Map<String, String> agentSettings() {
    Map<String, String> settings = new LinkedHashMap<>();
    settings.put("server.address", address());
    settings.put("server.tls", Boolean.toString(tlsActive));
    if (tlsActive && tlsMaterial != null) {
      settings.put("server.tls.ca-file", tlsMaterial.certificate().toString());
    }
    return settings;
  }

  public FaultInjector faults() {
    return faults;
  }

  /** The wire contract's name ({@link CollectorProtocol#name()}). */
  public String protocolName() {
    return protocol.name();
  }

  /** Every batch received so far, in arrival order (also refused, duplicate and hung ones). */
  public List<ReceivedBatch> batches() {
    synchronized (batches) {
      return List.copyOf(batches);
    }
  }

  /** The batches of one kind. */
  public List<ReceivedBatch> batches(BatchKind kind) {
    return batches().stream().filter(b -> b.kind() == kind).toList();
  }

  /** The seqs of every batch of {@code kind} in arrival order (retries and duplicates included). */
  public List<Long> seqs(BatchKind kind) {
    return batches(kind).stream().map(ReceivedBatch::seq).toList();
  }

  /** Batches that arrived after {@code instant}. */
  public List<ReceivedBatch> batchesAfter(Instant instant) {
    return batches().stream().filter(b -> b.receivedAt().isAfter(instant)).toList();
  }

  /** Waits until {@code count} batches of {@code kind} arrived; returns what is there. */
  public List<ReceivedBatch> awaitBatches(BatchKind kind, int count, Duration timeout) {
    return awaitList(() -> batches(kind), count, timeout);
  }

  /** Running totals per kind (also with {@link Builder#retainBatches} off). */
  public Map<BatchKind, KindCounts> counts() {
    synchronized (counts) {
      Map<BatchKind, KindCounts> copy = new EnumMap<>(BatchKind.class);
      for (BatchKind kind : BatchKind.values()) {
        copy.put(kind, counts.getOrDefault(kind, KindCounts.ZERO));
      }
      return copy;
    }
  }

  /**
   * The most data calls this collector served at the same time (from arrival to answer, a hung or
   * cancelled call until it ended): the agent's in-flight cap of 11 §6 (T-AG-071).
   */
  public int maxConcurrentUnaryCalls() {
    return unaryMaxInFlight.get();
  }

  /** Data calls in progress now. */
  public int concurrentUnaryCalls() {
    return unaryInFlight.get();
  }

  /** Starts a new high-water mark of {@link #maxConcurrentUnaryCalls()} from the current level. */
  public void resetMaxConcurrentUnaryCalls() {
    unaryMaxInFlight.set(unaryInFlight.get());
  }

  /** Every hello received (accepted, rejected or unanswered). */
  public List<SessionInfo> sessions() {
    return List.copyOf(sessions);
  }

  /** Waits until {@code count} hellos arrived. */
  public List<SessionInfo> awaitSessions(int count, Duration timeout) {
    return awaitList(this::sessions, count, timeout);
  }

  /** Accepted hellos. */
  public long acceptedSessionCount() {
    return sessions.stream().filter(SessionInfo::accepted).count();
  }

  /**
   * Hellos of an agent process after its first one (same {@code agent_epoch}): how often agents
   * re-established their session.
   */
  public long reconnectCount() {
    return sessions.size() - sessions.stream().map(SessionInfo::agentEpoch).distinct().count();
  }

  /** Session stream messages other than hello and results (heartbeat, bye, events, updates). */
  public List<Message> sessionMessages() {
    synchronized (sessionMessages) {
      return List.copyOf(sessionMessages);
    }
  }

  /** Every heartbeat with its clock round (11 §9). */
  public List<HeartbeatRecord> heartbeats() {
    synchronized (heartbeats) {
      return List.copyOf(heartbeats);
    }
  }

  /** Waits until {@code count} heartbeats arrived. */
  public List<HeartbeatRecord> awaitHeartbeats(int count, Duration timeout) {
    return awaitList(this::heartbeats, count, timeout);
  }

  /**
   * The 11 §9 offset estimate of an instance (server minus agent, ms): the median of its last 5
   * accepted rounds, else the hello's first estimate.
   */
  public OptionalLong clockOffsetMs(long instanceId) {
    ClockEstimator clock = clocks.get(instanceId);
    return clock == null ? OptionalLong.empty() : clock.estimate();
  }

  /** Every {@code AgentBye} received. */
  public List<ByeInfo> byes() {
    return List.copyOf(byes);
  }

  /** Every {@code AgentEvent} received, in arrival order (they also stay in sessionMessages). */
  public List<AgentEventInfo> agentEvents() {
    return List.copyOf(agentEvents);
  }

  /** The {@code AgentEvent}s of {@code type} ({@code KILL_SWITCH_ON}, ...), in arrival order. */
  public List<AgentEventInfo> agentEvents(String type) {
    return agentEvents.stream().filter(e -> e.type().equals(type)).toList();
  }

  /**
   * Waits until {@code count} {@code AgentEvent}s of {@code type} arrived; returns what is there
   * (fewer when the timeout passed).
   */
  public List<AgentEventInfo> awaitAgentEvents(String type, int count, Duration timeout) {
    return awaitList(() -> agentEvents(type), count, timeout);
  }

  /** Every {@code InstanceUpdate.config_hash} value received, in arrival order. */
  public List<String> configHashes() {
    return configHashUpdates.stream().map(ConfigHashUpdate::hash).toList();
  }

  /** Every {@code InstanceUpdate.config_hash} received with its session, in arrival order. */
  public List<ConfigHashUpdate> configHashUpdates() {
    return List.copyOf(configHashUpdates);
  }

  /**
   * Waits until an {@code InstanceUpdate.config_hash} equal to {@code hash} arrived (for example
   * the {@link ConfigApplyOutcome#effectiveConfigHash()} of a push); true when it did.
   */
  public boolean awaitConfigHash(String hash, Duration timeout) {
    long deadline = System.nanoTime() + timeout.toNanos();
    while (!configHashes().contains(hash)) {
      if (System.nanoTime() >= deadline) {
        return false;
      }
      try {
        Thread.sleep(20);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        return configHashes().contains(hash);
      }
    }
    return true;
  }

  /**
   * How long after {@code bye} the last batch arrived (the agent's shutdown flush, 11 §6 "종료": at
   * most 1 s); empty when no batch arrived after it.
   */
  public Optional<Duration> flushAfterBye(ByeInfo bye) {
    List<ReceivedBatch> after = batchesAfter(bye.receivedAt());
    if (after.isEmpty()) {
      return Optional.empty();
    }
    return Optional.of(
        Duration.between(bye.receivedAt(), after.get(after.size() - 1).receivedAt()));
  }

  /** Session streams open now (accepted hellos whose stream has not ended). */
  public int openSessionCount() {
    return openSessions.size();
  }

  /** Dictionary texts received so far, by hash. */
  public Map<Long, String> dictionary() {
    return Map.copyOf(dictionary);
  }

  /** Every dictionary entry received, in arrival order (repeats included). */
  public List<Collected.DictionaryItem> dictionaryEntries() {
    synchronized (dictionaryItems) {
      return List.copyOf(dictionaryItems);
    }
  }

  /** The collected data as the assertion DSL reads it. */
  public Collected collected() {
    return new CollectorCollected(this, protocol);
  }

  List<Collected.DictionaryItem> dictionaryItems() {
    return dictionaryEntries();
  }

  Map<Long, SessionInfo> sessionsByInstance() {
    Map<Long, SessionInfo> byInstance = new LinkedHashMap<>();
    for (SessionInfo session : sessions) {
      if (session.accepted()) {
        byInstance.put(session.instanceId(), session);
      }
    }
    return byInstance;
  }

  /** The collector's clock: real time plus {@link FaultInjector#clockOffset}. */
  long now() {
    return clock.getAsLong() + faults.currentClockOffset().toMillis();
  }

  // --- server → agent messages ------------------------------------------------------------------

  /**
   * Sends {@code ConfigPush} to the only open session; completes with the agent's {@code
   * ConfigApplyResult}.
   */
  public CompletableFuture<ConfigApplyOutcome> pushConfig(Map<String, String> values) {
    return pushConfig(values, false);
  }

  /**
   * Sends {@code ConfigPush{values, revert}} to the only open session (11 §4.2: {@code revert} true
   * means "these keys go back to the local value"); completes with the agent's {@code
   * ConfigApplyResult}, whose {@link ConfigApplyOutcome#roundTripMs()} the collector measures.
   */
  public CompletableFuture<ConfigApplyOutcome> pushConfig(
      Map<String, String> values, boolean revert) {
    CollectorProtocol.SessionChannel channel = onlySession();
    String changeId = "change-" + changeIds.incrementAndGet();
    CompletableFuture<ConfigApplyOutcome> result = new CompletableFuture<>();
    configResults.put(changeId, result);
    configSentNanos.put(changeId, System.nanoTime());
    send(channel, c -> c.configPush(changeId, Map.copyOf(values), revert, now()));
    return result;
  }

  /** Default {@code Command.ttl_s} of {@link #command(String, Map)} (11 §8.2: 60). */
  public static final int DEFAULT_COMMAND_TTL_S = 60;

  /**
   * Sends {@code Command} of {@code type} ({@code THREAD_DUMP}, {@code SELF_DIAGNOSTICS}, ...) to
   * the only open session with {@code ttl_s} 60; completes with the joined {@code CommandResult}
   * chunks.
   */
  public CompletableFuture<CommandOutcome> command(String type, Map<String, String> args) {
    return command(type, args, DEFAULT_COMMAND_TTL_S);
  }

  /**
   * {@link #command(String, Map)} with {@code ttl_s} (0 is sent as 0: the agent's default applies)
   * and {@code issued_at_ms} = the collector's clock.
   */
  public CompletableFuture<CommandOutcome> command(
      String type, Map<String, String> args, int ttlS) {
    return command(type, args, ttlS, now());
  }

  /**
   * {@link #command(String, Map)} with {@code ttl_s} and an explicit {@code issued_at_ms} (11 §8.2:
   * the agent measures the TTL from its receive time, so an old {@code issued_at_ms} alone must not
   * expire a command).
   */
  public CompletableFuture<CommandOutcome> command(
      String type, Map<String, String> args, int ttlS, long issuedAtMs) {
    requireTtl(ttlS);
    CollectorProtocol.SessionChannel channel = onlySession();
    CommandAccumulator accumulator = newCommand();
    Map<String, String> copy = Map.copyOf(args);
    send(channel, c -> c.command(accumulator.commandId, type, copy, ttlS, issuedAtMs));
    return accumulator.future;
  }

  /**
   * Sends {@code Command} whose {@code type} is the raw wire number {@code typeNumber} (for example
   * one the enum does not define: the agent's closed command list must answer {@code REJECTED},
   * SEC-24); {@code ttl_s} 60.
   */
  public CompletableFuture<CommandOutcome> commandRaw(int typeNumber, Map<String, String> args) {
    CollectorProtocol.SessionChannel channel = onlySession();
    CommandAccumulator accumulator = newCommand();
    Map<String, String> copy = Map.copyOf(args);
    long issuedAt = now();
    send(
        channel,
        c ->
            c.commandNumber(
                accumulator.commandId, typeNumber, copy, DEFAULT_COMMAND_TTL_S, issuedAt));
    return accumulator.future;
  }

  private CommandAccumulator newCommand() {
    CommandAccumulator accumulator = new CommandAccumulator(UUID.randomUUID().toString());
    commandResults.put(accumulator.commandId, accumulator);
    return accumulator;
  }

  private static void requireTtl(int ttlS) {
    if (ttlS < 0) {
      throw new IllegalArgumentException("ttl_s is unsigned: " + ttlS);
    }
  }

  /**
   * Replaces {@code ServerHello.config} for every later accepted hello (initially {@link
   * Builder#config}); the open session keeps what its hello carried. To make a running agent see
   * the new values, end its session: {@code faults().closeStream()} (connection loss, the agent
   * reconnects after its backoff) or {@code faults().sessionClose("SERVER_SHUTDOWN", ...)}, then
   * {@link #awaitSessions} for the new hello.
   */
  public FakeCollector helloConfig(Map<String, String> values) {
    this.helloConfig = Map.copyOf(values);
    return this;
  }

  /** The {@code ServerHello.config} the next accepted hello carries. */
  public Map<String, String> helloConfig() {
    return helloConfig;
  }

  /**
   * Sends {@code DictionaryRequest} for these hashes of {@code kind} ({@code SQL_TEXT}, ...) to the
   * only open session (11 §7 step 3); the agent's answer lands in {@link #dictionary()}.
   */
  public void requestDictionary(String kind, long... hashes) {
    CollectorProtocol.SessionChannel channel = onlySession();
    send(channel, c -> c.dictionaryRequest(kind, hashes.clone()));
  }

  /** Sends {@code Throttle} on every open session (11 §6); null leaves a field absent. */
  public void sendThrottle(Float profileNormalRate, Duration sendBackoff) {
    CollectorProtocol.ThrottleHint hint =
        new CollectorProtocol.ThrottleHint(
            profileNormalRate,
            sendBackoff == null ? null : Math.toIntExact(sendBackoff.toMillis()));
    for (CollectorProtocol.SessionChannel channel : new ArrayList<>(openSessions.keySet())) {
      send(channel, c -> c.throttle(hint));
    }
  }

  private CollectorProtocol.SessionChannel onlySession() {
    List<CollectorProtocol.SessionChannel> open = new ArrayList<>(openSessions.keySet());
    if (open.size() != 1) {
      throw new IllegalStateException(
          "expected exactly one open agent session, found " + open.size());
    }
    return open.get(0);
  }

  // --- protocol callbacks (CollectorProtocol implementations) -----------------------------------

  /** A data call arrived (the protocol's interceptor; ends with {@link #unaryFinished()}). */
  public void unaryStarted() {
    int now = unaryInFlight.incrementAndGet();
    unaryMaxInFlight.accumulateAndGet(now, Math::max);
  }

  /** A data call ended (answered, failed or cancelled by the client). */
  public void unaryFinished() {
    unaryInFlight.decrementAndGet();
  }

  /** A data call: decided (see {@link FaultInjector}), recorded, then answered. */
  public void onUnary(CollectorProtocol.UnaryCall call, CollectorProtocol.UnaryReply reply) {
    Instant now = Instant.now();
    CollectorProtocol.CallMetadata metadata = call.metadata();
    if (faults.hanging()) {
      record(call, now, ReceivedBatch.HUNG, false, 0);
      hung.add(reply);
      return;
    }
    Duration latency = faults.currentLatency();
    // 12 §3.1: the token check (TokenAuthInterceptor) runs before the session check.
    String tokenReject = faults.tokenRejectReason(metadata.token());
    if (tokenReject != null) {
      record(call, now, ReceivedBatch.REJECTED_AUTH, false, 0);
      Status status =
          Status.UNAUTHENTICATED.withDescription(
              "TOKEN_REVOKED".equals(tokenReject)
                  ? "token revoked"
                  : "token missing, malformed or not accepted");
      later(latency, () -> reply.fail(status, rejectTrailers(tokenReject, 0)));
      return;
    }
    LiveSession session = session(metadata);
    if (session == null) {
      record(call, now, ReceivedBatch.SESSION_REQUIRED, false, 0);
      later(
          latency,
          () ->
              reply.fail(
                  Status.FAILED_PRECONDITION.withDescription(ReceivedBatch.SESSION_REQUIRED),
                  Map.of()));
      return;
    }
    if (!session.token().equals(metadata.token())) {
      record(call, now, "FAILED " + Status.Code.PERMISSION_DENIED, false, 0);
      later(
          latency,
          () ->
              reply.fail(
                  Status.PERMISSION_DENIED.withDescription("token does not belong to the session"),
                  Map.of()));
      return;
    }
    FaultInjector.Failure failure = faults.nextFailure();
    if (failure != null) {
      record(call, now, "FAILED " + failure.status().getCode(), false, 0);
      Map<String, String> trailers =
          failure.retryAfter() == null
              ? Map.of()
              : Map.of(
                  FaultInjector.RETRY_AFTER_TRAILER,
                  Long.toString(failure.retryAfter().toMillis()));
      later(latency, () -> reply.fail(failure.status(), trailers));
      return;
    }
    CollectorProtocol.ThrottleHint throttle = faults.ackThrottle();
    long instance = session.instanceId();
    if (faults.walFullActive()
        && !dedup.seen(instance, call.agentEpoch(), call.kind(), call.seq())) {
      // 11 §6 "서버 드롭", 12 §3.2: acknowledged, nothing written, so the seq stays free.
      record(call, now, ReceivedBatch.NOT_WRITTEN, false, call.items());
      CollectorProtocol.AckAnswer full =
          new CollectorProtocol.AckAnswer(
              "ACK_OK",
              dedup.lastSeq(instance, call.agentEpoch(), call.kind()),
              call.items(),
              throttle);
      later(latency, () -> reply.ack(full));
      return;
    }
    Deduplicator.Outcome outcome =
        dedup.offer(instance, call.agentEpoch(), call.kind(), call.seq());
    CollectorProtocol.AckAnswer answer;
    if (outcome.duplicate()) {
      record(call, now, ReceivedBatch.DUPLICATE, true, 0);
      answer = new CollectorProtocol.AckAnswer("ACK_DUPLICATE", outcome.lastSeq(), 0, throttle);
    } else {
      int dropped = Math.min(faults.partialDropItems(), call.items());
      record(call, now, ReceivedBatch.ACKED, false, dropped);
      answer =
          new CollectorProtocol.AckAnswer(
              dropped > 0 ? "ACK_PARTIAL_DROP" : "ACK_OK", outcome.lastSeq(), dropped, throttle);
    }
    later(latency, () -> reply.ack(answer));
  }

  private void record(
      CollectorProtocol.UnaryCall call,
      Instant receivedAt,
      String outcome,
      boolean duplicate,
      int dropped) {
    synchronized (counts) {
      counts.merge(
          call.kind(),
          KindCounts.ZERO.plus(call.items(), call.bytes(), outcome),
          (a, b) -> a.plus(call.items(), call.bytes(), outcome));
    }
    if (retainBatches) {
      batches.add(
          new ReceivedBatch(
              call.kind(),
              call.instanceId(),
              call.agentEpoch(),
              call.seq(),
              receivedAt,
              call.body(),
              call.bytes(),
              outcome,
              duplicate,
              dropped,
              call.metadata().sessionId(),
              call.metadata().token(),
              call.sentAtMs()));
    }
  }

  /** The accepted session a data call names (11 §3), or null. */
  private LiveSession session(CollectorProtocol.CallMetadata metadata) {
    if (metadata.sessionId() == null || metadata.instanceId() == null) {
      return null;
    }
    LiveSession session = sessionsById.get(metadata.sessionId());
    if (session == null) {
      return null;
    }
    try {
      return Long.parseLong(metadata.instanceId().strip()) == session.instanceId() ? session : null;
    } catch (NumberFormatException e) {
      return null;
    }
  }

  /** {@code AgentHello} on a session stream (11 §5). */
  public void onHello(
      CollectorProtocol.SessionChannel channel,
      Message hello,
      CollectorProtocol.HelloFields fields) {
    CollectorProtocol.CallMetadata metadata = channel.metadata();
    int number = helloNumbers.incrementAndGet();
    Instant now = Instant.now();
    if (faults.hanging()) {
      sessions.add(info(number, 0, false, fields, now, hello, null, null, metadata));
      return;
    }
    Duration latency = faults.currentLatency();
    FaultInjector.Rejected rejected =
        faults.helloRejection(metadata, fields.protocolVersion(), ProtocolVersion.CURRENT);
    if (rejected != null) {
      String reason = rejected.rejection().reason();
      sessions.add(info(number, 0, false, fields, now, hello, null, reason, metadata));
      later(
          latency,
          () ->
              send(
                  channel,
                  c -> {
                    c.hello(
                        new CollectorProtocol.HelloAnswer(
                            false,
                            0,
                            null,
                            reason,
                            rejected.message(),
                            rejected.retryAfterS(),
                            now(),
                            heartbeatIntervalS,
                            Map.of(),
                            ByteString.EMPTY));
                    Status.Code code = rejected.rejection().code();
                    c.close(
                        code == Status.Code.OK
                            ? Status.OK
                            : Status.fromCode(code).withDescription(reason),
                        rejectTrailers(reason, rejected.retryAfterS()));
                  }));
      return;
    }
    if (fields.checkOnly() && !ignoreCheckOnly) {
      // C-240 (1): answered without a session or an instance, and the stream ended.
      sessions.add(info(number, 0, true, fields, now, hello, null, null, metadata));
      later(
          latency,
          () ->
              send(
                  channel,
                  c -> {
                    c.hello(
                        new CollectorProtocol.HelloAnswer(
                            true,
                            0,
                            "",
                            null,
                            null,
                            0,
                            now(),
                            heartbeatIntervalS,
                            Map.of(),
                            ByteString.EMPTY));
                    c.close(Status.OK, Map.of());
                  }));
      return;
    }
    LiveSession live;
    List<LiveSession> replaced = new ArrayList<>();
    synchronized (registry) {
      long instanceId =
          instanceIds.computeIfAbsent(
              instanceKey(fields.appName(), fields.env(), fields.instanceName()),
              key -> nextInstanceId.getAndIncrement());
      live =
          new LiveSession(
              UUID.randomUUID().toString(),
              instanceId,
              metadata.token(),
              channel,
              fields.appName(),
              fields.env());
      for (Map.Entry<CollectorProtocol.SessionChannel, LiveSession> open :
          new ArrayList<>(openSessions.entrySet())) {
        LiveSession old = open.getValue();
        if (old.instanceId() == instanceId && !open.getKey().equals(channel)) {
          openSessions.remove(open.getKey());
          replaced.add(old);
        }
      }
      // 12 §4.1: one registry entry per instance. An ended stream's session is forgotten here too.
      sessionsById.values().removeIf(old -> old.instanceId() == instanceId);
      sessionsById.put(live.sessionId(), live);
      openSessions.put(channel, live);
      sessions.add(
          info(number, instanceId, true, fields, now, hello, live.sessionId(), null, metadata));
    }
    // 11 §5 check 7, 12 §4.1: any open session of the instance is replaced, whatever its epoch (an
    // agent reconnecting has already cancelled its old stream and does not see this).
    for (LiveSession old : replaced) {
      send(
          old.channel(),
          c -> {
            c.sessionClose("REPLACED", "another session was opened for this instance", 0);
            c.close(Status.OK, Map.of());
          });
    }
    ClockEstimator clock = clocks.computeIfAbsent(live.instanceId(), id -> new ClockEstimator());
    ByteString salt = tlsActive && !metadata.plaintext() ? userIdSalt : ByteString.EMPTY;
    later(
        latency,
        () -> {
          long serverTime = now();
          clock.hello(fields.sentAtMs(), serverTime);
          send(
              channel,
              c ->
                  c.hello(
                      new CollectorProtocol.HelloAnswer(
                          true,
                          live.instanceId(),
                          live.sessionId(),
                          null,
                          null,
                          0,
                          serverTime,
                          heartbeatIntervalS,
                          helloConfig,
                          salt)));
        });
  }

  private static SessionInfo info(
      int number,
      long instanceId,
      boolean accepted,
      CollectorProtocol.HelloFields fields,
      Instant now,
      Message hello,
      String sessionId,
      String rejectReason,
      CollectorProtocol.CallMetadata metadata) {
    return new SessionInfo(
        number,
        instanceId,
        accepted,
        fields.appName(),
        fields.env(),
        fields.appVersion(),
        fields.agentVersion(),
        now,
        hello,
        sessionId,
        rejectReason,
        metadata.token(),
        fields.agentEpoch(),
        metadata.plaintext(),
        fields.checkOnly());
  }

  private static Map<String, String> rejectTrailers(String reason, int retryAfterS) {
    Map<String, String> trailers = new LinkedHashMap<>();
    trailers.put(FaultInjector.REJECT_REASON_TRAILER, reason);
    if (retryAfterS > 0) {
      trailers.put(FaultInjector.RETRY_AFTER_TRAILER, Long.toString(retryAfterS * 1000L));
    }
    return trailers;
  }

  /** {@code Heartbeat} on a session stream (11 §9): answered with t2/t3, completes a round. */
  public void onHeartbeat(
      CollectorProtocol.SessionChannel channel,
      Message heartbeat,
      long t1,
      long prevT1,
      long prevT4,
      long droppedTotal) {
    Instant receivedAt = Instant.now();
    sessionMessages.add(heartbeat);
    LiveSession live = openSessions.get(channel);
    boolean answer = live != null && !faults.hanging();
    long t2 = answer ? now() : 0;
    if (!answer) {
      heartbeats.add(
          new HeartbeatRecord(
              live == null ? 0 : live.instanceId(),
              live == null ? null : live.sessionId(),
              receivedAt,
              t1,
              prevT1,
              prevT4,
              0,
              0,
              null,
              null,
              false,
              droppedTotal,
              heartbeat));
      return;
    }
    ClockEstimator clock = clocks.computeIfAbsent(live.instanceId(), id -> new ClockEstimator());
    ClockEstimator.Round round = clock.heartbeat(prevT1, prevT4);
    OptionalLong estimate = clock.estimate();
    long t3 = now();
    clock.acked(t1, t2, t3);
    heartbeats.add(
        new HeartbeatRecord(
            live.instanceId(),
            live.sessionId(),
            receivedAt,
            t1,
            prevT1,
            prevT4,
            t2,
            t3,
            round,
            estimate.isPresent() ? estimate.getAsLong() : null,
            true,
            droppedTotal,
            heartbeat));
    long offset = ackClockOffsetMs(estimate.orElse(0));
    String state = Math.abs(offset) >= CLOCK_WARN_MS ? "WARN" : "UP";
    send(channel, c -> c.heartbeatAck(t1, t2, t3, offset, state));
  }

  /**
   * The {@code HeartbeatAck.clock_offset_ms} sent for an 11 §9 estimate: the §9 formula value
   * {@code ((t2 - t1) + (t3 - t4)) / 2} (server minus agent) unchanged, so a server clock 3 s ahead
   * sends +3000 (ADR-0010 C-139).
   */
  static long ackClockOffsetMs(long estimateServerMinusAgentMs) {
    return estimateServerMinusAgentMs;
  }

  /** Dictionary entries on a session stream. */
  public void onDictionary(List<Collected.DictionaryItem> items) {
    for (Collected.DictionaryItem item : items) {
      dictionary.put(item.hash(), item.text());
      dictionaryItems.add(item);
    }
  }

  /** {@code ConfigApplyResult} on a session stream. */
  public void onConfigApplyResult(
      String changeId,
      List<String> applied,
      Map<String, String> rejected,
      int retransformedClasses,
      String effectiveConfigHash,
      long durationMs,
      Map<String, String> messages) {
    CompletableFuture<ConfigApplyOutcome> result = configResults.remove(changeId);
    Long sent = configSentNanos.remove(changeId);
    if (result != null) {
      long roundTrip = sent == null ? 0 : (System.nanoTime() - sent) / 1_000_000;
      result.complete(
          new ConfigApplyOutcome(
              changeId,
              applied,
              rejected,
              retransformedClasses,
              effectiveConfigHash,
              durationMs,
              messages,
              roundTrip));
    }
  }

  /** {@code AgentEvent} on a session stream ({@link #agentEvents()}). */
  public void onAgentEvent(
      CollectorProtocol.SessionChannel channel,
      String type,
      String module,
      String detail,
      long atMs) {
    LiveSession live = openSessions.get(channel);
    agentEvents.add(
        new AgentEventInfo(
            type,
            module,
            detail,
            atMs,
            live == null ? null : live.sessionId(),
            live == null ? 0 : live.instanceId(),
            Instant.now()));
  }

  /** {@code InstanceUpdate.config_hash} on a session stream ({@link #configHashUpdates()}). */
  public void onConfigHash(CollectorProtocol.SessionChannel channel, String hash) {
    LiveSession live = openSessions.get(channel);
    configHashUpdates.add(
        new ConfigHashUpdate(
            hash,
            live == null ? null : live.sessionId(),
            live == null ? 0 : live.instanceId(),
            Instant.now()));
  }

  /** One {@code CommandResult} chunk on a session stream. */
  public void onCommandResult(
      String commandId,
      String status,
      int chunkIndex,
      int chunkCount,
      ByteString payload,
      String contentType,
      String error,
      boolean truncated,
      long durationMs) {
    CommandAccumulator accumulator = commandResults.get(commandId);
    if (accumulator == null) {
      return;
    }
    if (accumulator.add(
        status, chunkIndex, chunkCount, payload, contentType, error, truncated, durationMs)) {
      commandResults.remove(commandId);
    }
  }

  /** {@code AgentBye} on a session stream. */
  public void onBye(
      CollectorProtocol.SessionChannel channel,
      Message bye,
      String reason,
      long lastTxSeq,
      long lastProfileSeq,
      long lastMetricSeq,
      long lastStatSeq) {
    LiveSession live = openSessions.get(channel);
    sessionMessages.add(bye);
    byes.add(
        new ByeInfo(
            live == null ? 0 : live.instanceId(),
            live == null ? null : live.sessionId(),
            reason,
            lastTxSeq,
            lastProfileSeq,
            lastMetricSeq,
            lastStatSeq,
            Instant.now(),
            bye));
  }

  /** Any other session message (instance update, event). */
  public void onSessionMessage(Message message) {
    sessionMessages.add(message);
  }

  /**
   * {@code InstanceUpdate.instance_name} on a session stream (11 §4: {@code host:port} once the
   * connector port is known): the instance takes the new name and keeps its id (12 §4.3 "이름만 갱신"),
   * so the hello of a reconnect, which carries the new name, gets the same instance id and dedup
   * window (11 §5 "재기동 연속성"). It is a rename: the old name no longer points to the instance, and a
   * later hello with it is a new instance. A name another instance of the app already holds is left
   * with that instance and the update changes nothing (merging is WO-22, ADR-0010 C-158 (2)).
   * Unknown or ended streams change nothing.
   */
  public void onInstanceName(CollectorProtocol.SessionChannel channel, String instanceName) {
    if (instanceName == null || instanceName.isBlank()) {
      return;
    }
    synchronized (registry) {
      LiveSession live = openSessions.get(channel);
      if (live == null) {
        return;
      }
      long instanceId = live.instanceId();
      String key = instanceKey(live.appName(), live.env(), instanceName.strip());
      Long holder = instanceIds.get(key);
      if (holder != null && holder != instanceId) {
        return;
      }
      String prefix = instanceKey(live.appName(), live.env(), "");
      instanceIds
          .entrySet()
          .removeIf(e -> e.getValue() == instanceId && e.getKey().startsWith(prefix));
      instanceIds.put(key, instanceId);
    }
  }

  /** A {@code Session} stream started. */
  public void onSessionOpened(CollectorProtocol.SessionChannel channel) {
    streams.add(channel);
  }

  /** True while {@link FaultInjector#pauseSessionReads()} holds the streams back. */
  public boolean sessionReadsPaused() {
    return faults.sessionReadsPaused();
  }

  /**
   * The stream ended, by the agent, the transport or the collector; the session stays known (11 §6
   * flush).
   */
  public void onSessionClosed(CollectorProtocol.SessionChannel channel) {
    openSessions.remove(channel);
    streams.remove(channel);
  }

  private void later(Duration delay, Runnable task) {
    if (delay.isZero()) {
      task.run();
    } else {
      ScheduledFuture<?> unused = scheduler.schedule(task, delay.toNanos(), TimeUnit.NANOSECONDS);
    }
  }

  private static void send(
      CollectorProtocol.SessionChannel channel,
      Consumer<CollectorProtocol.SessionChannel> message) {
    synchronized (channel) {
      message.accept(channel);
    }
  }

  private static String newToken() {
    byte[] random = new byte[24];
    new SecureRandom().nextBytes(random);
    return "argos_" + Base64.getUrlEncoder().withoutPadding().encodeToString(random);
  }

  private static <T> List<T> awaitList(Supplier<List<T>> list, int count, Duration timeout) {
    long deadline = System.nanoTime() + timeout.toNanos();
    List<T> current = list.get();
    while (current.size() < count && System.nanoTime() < deadline) {
      try {
        Thread.sleep(20);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        break;
      }
      current = list.get();
    }
    return current;
  }

  /** Joins the chunks of one command result. */
  private static final class CommandAccumulator {
    final String commandId;
    final CompletableFuture<CommandOutcome> future = new CompletableFuture<>();
    final long sentNanos = System.nanoTime();
    ByteString payload = ByteString.EMPTY;
    int chunks;

    CommandAccumulator(String commandId) {
      this.commandId = commandId;
    }

    /** Adds a chunk; true when it was the last one. */
    synchronized boolean add(
        String status,
        int chunkIndex,
        int chunkCount,
        ByteString data,
        String contentType,
        String error,
        boolean truncated,
        long durationMs) {
      payload = payload.concat(data);
      chunks++;
      if (chunkIndex + 1 >= Math.max(1, chunkCount)) {
        future.complete(
            new CommandOutcome(
                commandId,
                status,
                payload,
                contentType,
                error,
                truncated,
                chunks,
                durationMs,
                (System.nanoTime() - sentNanos) / 1_000_000));
        return true;
      }
      return false;
    }
  }
}
