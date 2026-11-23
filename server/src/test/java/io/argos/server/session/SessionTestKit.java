/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.session;

import io.argos.proto.ProtocolVersion;
import io.argos.proto.v1.AgentHello;
import io.argos.proto.v1.AgentMessage;
import io.argos.proto.v1.IpMode;
import io.argos.proto.v1.QueryStringMode;
import io.argos.proto.v1.ServerMessage;
import io.argos.proto.v1.SqlParamMode;
import io.argos.proto.v1.UserIdMode;
import io.argos.server.collector.CollectorCalls;
import io.argos.server.collector.CollectorMetrics;
import io.argos.server.collector.CollectorState;
import io.argos.server.collector.api.TokenRecord;
import io.argos.server.collector.api.TokenStatus;
import io.argos.server.collector.api.TokenVerdict;
import io.argos.server.collector.auth.AuthResult;
import io.argos.server.collector.grpc.Inbound;
import io.argos.server.collector.limits.CollectorLimits;
import io.argos.server.collector.limits.LimitsChecker;
import io.argos.server.testing.auth.MutableClock;
import io.grpc.Context;
import io.grpc.stub.StreamObserver;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * A {@link SessionHandler} with its real collaborators in memory, a fake monotonic ticker, a timer
 * whose periodic tasks the test runs by hand, and an instance state tracker (unit tests of the idle
 * timeout and the instance merge, WO-22).
 */
final class SessionTestKit implements AutoCloseable {

  final MutableClock clock = new MutableClock();
  final AtomicLong ticker = new AtomicLong(1_000_000_000L);
  final SimpleMeterRegistry meters = new SimpleMeterRegistry();
  final CollectorMetrics metrics = new CollectorMetrics(meters);
  final InMemorySessionStore store = new InMemorySessionStore(clock);
  final AppRef home = store.addApp("order-api", "prod");
  final InMemorySessionRegistry registry = new InMemorySessionRegistry();
  final ManualTimer timer = new ManualTimer();
  final TrackerFixture.RecordingStore stateStore = new TrackerFixture.RecordingStore();
  final InstanceStateWriter writer = new InstanceStateWriter(stateStore, meters);
  final InstanceStateTracker tracker =
      new InstanceStateTracker(
          new InstanceStateMachine(clock),
          registry,
          new AgentVersionRange("1.4.0"),
          writer,
          change -> {});
  final TokenRecord token =
      new TokenRecord(
          UUID.randomUUID(),
          home.id(),
          "order-api",
          "prod",
          "argos_AbCdEfGh",
          TokenStatus.ACTIVE,
          null,
          null,
          null);
  final SessionHandler handler;

  SessionTestKit(Duration idleTimeout) {
    LimitsChecker limits = new LimitsChecker(CollectorLimits.defaults());
    HelloService hellos =
        new HelloService(
            store,
            registry,
            new SessionSettings(
                false,
                Map.of("default", true),
                100,
                60,
                1_000,
                100,
                Duration.ofSeconds(30),
                CollectorLimits.defaults().toProto(),
                SessionSettings.HELLO_TIMEOUT),
            new AgentVersionRange("1.4.0"),
            limits,
            new CollectorState(),
            metrics,
            clock);
    handler =
        new SessionHandler(
            hellos,
            registry,
            store,
            limits,
            (appId, instanceId, entries) -> {},
            metrics,
            clock,
            timer,
            SessionSettings.HELLO_TIMEOUT,
            tracker,
            idleTimeout,
            ticker::get);
  }

  /** Opens a session stream with the token's authentication. */
  StreamObserver<Inbound<AgentMessage>> open(RecordingObserver<ServerMessage> out) {
    AuthResult auth =
        new AuthResult(AuthResult.Outcome.ACCEPTED, TokenVerdict.ACCEPTED, token, "argos_AbCdEfGh");
    Context context = Context.current().withValue(CollectorCalls.AUTH, auth);
    Context previous = context.attach();
    try {
      return handler.invoke(out);
    } finally {
      context.detach(previous);
    }
  }

  /** A Hello of host {@code host}, pid {@code pid} with instance name {@code instanceName}. */
  static AgentMessage hello(String instanceName, String host, int pid) {
    return AgentMessage.newBuilder()
        .setHello(
            AgentHello.newBuilder()
                .setAgentVersion("1.4.0")
                .setProtocolVersion(ProtocolVersion.CURRENT)
                .setAppName("order-api")
                .setEnv("prod")
                .setInstanceName(instanceName)
                .setHost(host)
                .setPid(pid)
                .setIpMode(IpMode.IP_MASKED)
                .setUserIdMode(UserIdMode.USER_ID_OFF)
                .setQueryStringMode(QueryStringMode.QS_OFF)
                .setSqlParamMode(SqlParamMode.SQL_PARAM_MASKED)
                .setAgentEpoch(77)
                .setSentAtMs(System.currentTimeMillis()))
        .build();
  }

  /** Advances the fake monotonic ticker. */
  void advance(Duration d) {
    ticker.addAndGet(d.toNanos());
  }

  /** Counter {@code argos.collector.sessions.closed{reason}}. */
  double closed(String reason) {
    Counter c = meters.find("argos.collector.sessions.closed").tag("reason", reason).counter();
    return c == null ? 0 : c.count();
  }

  @Override
  public void close() {
    timer.shutdownNow();
    writer.close();
  }

  /**
   * A scheduler whose fixed-delay tasks (the idle checks) are recorded for the test to run by hand
   * and never run by themselves; one-shot tasks (the Hello timeout) run normally.
   */
  static final class ManualTimer extends ScheduledThreadPoolExecutor {
    final List<Runnable> periodic = new CopyOnWriteArrayList<>();

    ManualTimer() {
      super(1);
    }

    @Override
    public ScheduledFuture<?> scheduleWithFixedDelay(
        Runnable command, long initialDelay, long delay, TimeUnit unit) {
      periodic.add(command);
      return super.scheduleWithFixedDelay(() -> {}, 1, 1, TimeUnit.DAYS);
    }

    /** Runs every recorded periodic task once. */
    void runPeriodic() {
      for (Runnable r : periodic) {
        r.run();
      }
    }
  }
}
