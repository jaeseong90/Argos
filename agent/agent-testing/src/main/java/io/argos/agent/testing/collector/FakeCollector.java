/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.testing.collector;

import com.google.protobuf.ByteString;
import com.google.protobuf.Message;
import io.argos.agent.testing.collector.poc.PocProtocol;
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
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
import java.util.function.Supplier;

/**
 * The fake collector of the agent tests (18 §5.3, DSN-18-04): a gRPC {@code AgentService} on
 * 127.0.0.1 inside the test JVM (Netty, loopback, so an agent in another JVM can connect) that
 * records every batch as received ({@link ReceivedBatch}: instance, seq, receive time, body) and
 * answers through the {@link FaultInjector}. It is the "sent count" reference: no limits, no quotas
 * (that is the server's job).
 *
 * <pre>
 * try (FakeCollector collector = FakeCollector.start()) {
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
 * <p>Wire contract: {@link CollectorProtocol}. Today the WO-02 transport PoC protocol ({@link
 * PocProtocol}); the agent sends nothing over gRPC before WO-11, which swaps the protocol
 * implementation for the real {@code proto/argos/v1} stubs without changing this API.
 */
public final class FakeCollector implements AutoCloseable {
  private final CollectorProtocol protocol;
  private final Path tlsDirectory;
  private final FaultInjector faults = new FaultInjector(this);
  private final List<ReceivedBatch> batches = new CopyOnWriteArrayList<>();
  private final List<SessionInfo> sessions = new CopyOnWriteArrayList<>();
  private final List<Message> sessionMessages = new CopyOnWriteArrayList<>();
  private final Map<Long, String> dictionary = new ConcurrentHashMap<>();
  private final List<Collected.DictionaryItem> dictionaryItems = new CopyOnWriteArrayList<>();
  private final Map<CollectorProtocol.SessionChannel, Long> openSessions =
      new ConcurrentHashMap<>();
  private final List<UnaryReply> hung = new CopyOnWriteArrayList<>();
  private final Map<String, CompletableFuture<ConfigApplyOutcome>> configResults =
      new ConcurrentHashMap<>();
  private final Map<String, CommandAccumulator> commandResults = new ConcurrentHashMap<>();
  private final AtomicLong nextInstanceId;
  private final AtomicInteger sessionNumbers = new AtomicInteger();
  private final AtomicInteger changeIds = new AtomicInteger();
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

  /** Answer of one unary call, implemented by the protocol. */
  public interface UnaryReply {
    void ok();

    void fail(Status status);
  }

  private FakeCollector(Builder builder) {
    this.protocol = builder.protocol != null ? builder.protocol : new PocProtocol();
    this.tlsDirectory =
        builder.tlsDirectory != null
            ? builder.tlsDirectory
            : Path.of(System.getProperty("java.io.tmpdir"));
    this.nextInstanceId = new AtomicLong(builder.firstInstanceId);
    this.tlsActive = builder.tls;
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

    private Builder() {}

    /** TLS with a throw-away self-signed certificate for {@code localhost}. */
    public Builder tls(boolean on) {
      this.tls = on;
      return this;
    }

    /** The wire contract (default: the WO-02 PoC protocol). */
    public Builder protocol(CollectorProtocol value) {
      this.protocol = value;
      return this;
    }

    /** The instance id of the first accepted hello (then +1 per hello). */
    public Builder firstInstanceId(long id) {
      this.firstInstanceId = id;
      return this;
    }

    /** Where the temporary TLS key store goes (default {@code java.io.tmpdir}). */
    public Builder tlsDirectory(Path directory) {
      this.tlsDirectory = directory;
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
      NettyServerBuilder builder =
          NettyServerBuilder.forAddress(
                  new InetSocketAddress(InetAddress.getLoopbackAddress(), port))
              .permitKeepAliveTime(1, TimeUnit.SECONDS)
              .permitKeepAliveWithoutCalls(true)
              .maxInboundMessageSize(NO_INBOUND_LIMIT)
              .maxInboundMetadataSize(NO_INBOUND_LIMIT)
              .addService(protocol.service(this));
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

  /** {@link FaultInjector#serverRestart}, {@code plaintextOnly}, {@code wrongHostCertificate}. */
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
    if (newInstanceId >= 0) {
      nextInstanceId.set(newInstanceId);
    }
    tlsActive = tls;
    startServer(wrongHost);
  }

  /** Ends every open session stream with {@code status}. */
  void closeSessions(Status status) {
    for (CollectorProtocol.SessionChannel channel : new ArrayList<>(openSessions.keySet())) {
      openSessions.remove(channel);
      try {
        channel.close(status);
      } catch (RuntimeException e) {
        // already closed by the peer
      }
    }
  }

  /** Ends every hung unary call with {@code status}. */
  void releaseHung(Status status) {
    for (UnaryReply reply : new ArrayList<>(hung)) {
      hung.remove(reply);
      try {
        reply.fail(status);
      } catch (RuntimeException e) {
        // the client gave up (deadline) already
      }
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
   * server binds the IPv4 loopback only ({@code localhost} may resolve to {@code ::1} first), and
   * the TLS certificate names {@code localhost} and IP {@code 127.0.0.1}, so host name verification
   * holds for this address.
   */
  public synchronized String address() {
    return "127.0.0.1:" + port;
  }

  /** True while the server speaks TLS. */
  public synchronized boolean tls() {
    return tlsActive;
  }

  /** The PEM certificate a TLS client must trust ({@code server.tls.ca-file}); null without TLS. */
  public synchronized Path trustPem() {
    return tlsActive && tlsMaterial != null ? tlsMaterial.certificate() : null;
  }

  /** The agent settings that point an agent to this collector (10 §15 keys). */
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

  /** Every batch received so far, in arrival order (also failed, hung and rejected ones). */
  public List<ReceivedBatch> batches() {
    return List.copyOf(batches);
  }

  /** The batches of one kind. */
  public List<ReceivedBatch> batches(BatchKind kind) {
    return batches.stream().filter(b -> b.kind() == kind).toList();
  }

  /** Waits until {@code count} batches of {@code kind} arrived; returns what is there. */
  public List<ReceivedBatch> awaitBatches(BatchKind kind, int count, Duration timeout) {
    return awaitList(() -> batches(kind), count, timeout);
  }

  /** Every hello received (accepted, rejected or unanswered). */
  public List<SessionInfo> sessions() {
    return List.copyOf(sessions);
  }

  /** Waits until {@code count} hellos arrived. */
  public List<SessionInfo> awaitSessions(int count, Duration timeout) {
    return awaitList(this::sessions, count, timeout);
  }

  /** Session stream messages other than hello and results (heartbeat, bye, events, updates). */
  public List<Message> sessionMessages() {
    return List.copyOf(sessionMessages);
  }

  /** Session streams open now (accepted hellos whose stream has not ended). */
  public int openSessionCount() {
    return openSessions.size();
  }

  /** Dictionary texts received so far, by hash. */
  public Map<Long, String> dictionary() {
    return Map.copyOf(dictionary);
  }

  /** The collected data as the assertion DSL reads it. */
  public Collected collected() {
    return new CollectorCollected(this, protocol);
  }

  List<Collected.DictionaryItem> dictionaryItems() {
    return List.copyOf(dictionaryItems);
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

  /**
   * Sends {@code ConfigPush} to the only open session; completes with the agent's {@code
   * ConfigApplyResult}.
   */
  public CompletableFuture<ConfigApplyOutcome> pushConfig(Map<String, String> values) {
    CollectorProtocol.SessionChannel channel = onlySession();
    String changeId = "change-" + changeIds.incrementAndGet();
    CompletableFuture<ConfigApplyOutcome> result = new CompletableFuture<>();
    configResults.put(changeId, result);
    synchronized (channel) {
      channel.configPush(changeId, Map.copyOf(values));
    }
    return result;
  }

  /**
   * Sends {@code Command} of {@code type} ({@code THREAD_DUMP}, {@code SELF_DIAGNOSTICS}, ...) to
   * the only open session; completes with the joined {@code CommandResult} chunks.
   */
  public CompletableFuture<CommandOutcome> command(String type, Map<String, String> args) {
    CollectorProtocol.SessionChannel channel = onlySession();
    String commandId = UUID.randomUUID().toString();
    CommandAccumulator accumulator = new CommandAccumulator(commandId);
    commandResults.put(commandId, accumulator);
    synchronized (channel) {
      channel.command(commandId, type, Map.copyOf(args), 60);
    }
    return accumulator.future;
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

  /** A unary data call: recorded, then answered as the faults decide. */
  public void onUnary(
      BatchKind kind,
      long instanceId,
      long agentEpoch,
      long seq,
      Message body,
      int bytes,
      UnaryReply reply) {
    FaultInjector.Decision decision = faults.decideUnary();
    batches.add(
        new ReceivedBatch(
            kind, instanceId, agentEpoch, seq, Instant.now(), body, bytes, decision.outcome()));
    switch (decision.action()) {
      case ACK -> later(decision.latency(), reply::ok);
      case FAIL, REJECT_AUTH -> later(decision.latency(), () -> reply.fail(decision.status()));
      case HANG -> hung.add(reply);
    }
  }

  /** {@code AgentHello} on a session stream. */
  public void onHello(
      CollectorProtocol.SessionChannel channel,
      Message hello,
      String appName,
      String env,
      String appVersion,
      String agentVersion) {
    int number = sessionNumbers.incrementAndGet();
    Instant now = Instant.now();
    if (faults.hanging()) {
      sessions.add(
          new SessionInfo(number, 0, false, appName, env, appVersion, agentVersion, now, hello));
      return;
    }
    if (faults.rejectingAuth()) {
      sessions.add(
          new SessionInfo(number, 0, false, appName, env, appVersion, agentVersion, now, hello));
      later(
          faults.currentLatency(),
          () -> send(channel, () -> channel.hello(false, 0, "TOKEN_INVALID")));
      return;
    }
    long instanceId = nextInstanceId.getAndIncrement();
    sessions.add(
        new SessionInfo(
            number, instanceId, true, appName, env, appVersion, agentVersion, now, hello));
    openSessions.put(channel, instanceId);
    later(
        faults.currentLatency(), () -> send(channel, () -> channel.hello(true, instanceId, null)));
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
      int retransformedClasses) {
    CompletableFuture<ConfigApplyOutcome> result = configResults.remove(changeId);
    if (result != null) {
      result.complete(new ConfigApplyOutcome(changeId, applied, rejected, retransformedClasses));
    }
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
      boolean truncated) {
    CommandAccumulator accumulator = commandResults.get(commandId);
    if (accumulator == null) {
      return;
    }
    if (accumulator.add(status, chunkIndex, chunkCount, payload, contentType, error, truncated)) {
      commandResults.remove(commandId);
    }
  }

  /** Any other session message (heartbeat, bye, instance update, event). */
  public void onSessionMessage(Message message) {
    sessionMessages.add(message);
  }

  /** The agent ended its stream (or the transport failed). */
  public void onSessionClosed(CollectorProtocol.SessionChannel channel) {
    openSessions.remove(channel);
  }

  /** True when session messages that expect an answer (heartbeat) may be answered. */
  public boolean answering() {
    return !faults.hanging();
  }

  private void later(Duration delay, Runnable task) {
    if (delay.isZero()) {
      task.run();
    } else {
      ScheduledFuture<?> unused = scheduler.schedule(task, delay.toNanos(), TimeUnit.NANOSECONDS);
    }
  }

  private static void send(CollectorProtocol.SessionChannel channel, Runnable message) {
    synchronized (channel) {
      try {
        message.run();
      } catch (RuntimeException e) {
        // the stream ended meanwhile
      }
    }
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
        boolean truncated) {
      payload = payload.concat(data);
      chunks++;
      if (chunkIndex + 1 >= Math.max(1, chunkCount)) {
        future.complete(
            new CommandOutcome(commandId, status, payload, contentType, error, truncated, chunks));
        return true;
      }
      return false;
    }
  }
}
