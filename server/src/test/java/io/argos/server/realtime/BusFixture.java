/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.realtime;

import io.argos.proto.v1.ActiveTransactionSnapshot;
import io.argos.proto.v1.ServerMessage;
import io.argos.proto.v1.TransactionKind;
import io.argos.server.api.active.ActiveTransactions;
import io.argos.server.api.query.QueryLimits.TxKind;
import io.argos.server.api.query.UntrustedStringsModule;
import io.argos.server.ingest.api.XlogPoint;
import io.argos.server.session.AgentSession;
import io.argos.server.session.AppRef;
import io.argos.server.session.ClockSync;
import io.argos.server.session.SessionStream;
import io.argos.server.session.api.IpMode;
import io.argos.server.session.api.SessionContext;
import io.argos.server.session.api.UserIdMode;
import io.argos.server.storage.query.lookup.AppDirectory;
import io.grpc.stub.ServerCallStreamObserver;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Shared pieces of the realtime unit tests: sources, clock, sessions, points, frames. */
final class BusFixture {

  static final JsonMapper JSON =
      JsonMapper.builder().addModule(new UntrustedStringsModule()).build();

  /** 2026-11-22T00:00:00Z, on the 5-second grid. */
  static final long T0 = Instant.parse("2026-11-22T00:00:00Z").toEpochMilli();

  private BusFixture() {}

  /** A clock the test moves. */
  static final class TestClock extends Clock {
    private final AtomicLong millis;

    TestClock(long millis) {
      this.millis = new AtomicLong(millis);
    }

    void advance(long ms) {
      millis.addAndGet(ms);
    }

    @Override
    public ZoneId getZone() {
      return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
      return this;
    }

    @Override
    public Instant instant() {
      return Instant.ofEpochMilli(millis.get());
    }

    @Override
    public long millis() {
      return millis.get();
    }
  }

  /** In-memory sources. */
  static final class Sources implements BusSources {
    final Map<Integer, AppDirectory.AppInfo> apps = new ConcurrentHashMap<>();
    final Map<Integer, Map<Long, String>> names = new ConcurrentHashMap<>();
    final Map<Integer, List<AgentSession>> sessions = new ConcurrentHashMap<>();
    final Map<Integer, String> states = new ConcurrentHashMap<>();
    final List<String> lookups = new CopyOnWriteArrayList<>();

    Sources app(int id, String env, int slowMs) {
      apps.put(id, new AppDirectory.AppInfo(id, "app-" + id, env, slowMs, 500));
      return this;
    }

    Sources name(int app, long service, String name) {
      names.computeIfAbsent(app, a -> new ConcurrentHashMap<>()).put(service, name);
      return this;
    }

    @Override
    public Optional<AppDirectory.AppInfo> app(int appId) {
      lookups.add("app:" + appId);
      return Optional.ofNullable(apps.get(appId));
    }

    @Override
    public Map<Long, String> serviceNames(int appId, Collection<Long> ids) {
      lookups.add("names:" + appId + ":" + ids.size());
      Map<Long, String> known = names.getOrDefault(appId, Map.of());
      Map<Long, String> out = new LinkedHashMap<>();
      for (Long id : ids) {
        if (known.containsKey(id)) {
          out.put(id, known.get(id));
        }
      }
      return out;
    }

    @Override
    public Collection<AgentSession> sessions(int appId) {
      return sessions.getOrDefault(appId, List.of());
    }

    @Override
    public String state(int instanceId) {
      return states.getOrDefault(instanceId, "UP");
    }

    @Override
    public ActiveTransactions active(int appId, TxKind kinds, Set<Integer> instances) {
      return ActivePayloads.build(appId, sessions(appId), kinds, instances, this::state, null);
    }
  }

  static RealtimeBus bus(Sources sources, TestClock clock, SimpleMeterRegistry registry) {
    return new RealtimeBus(sources, registry, clock, JSON);
  }

  static SessionContext session(int app, String env, int instance, long receivedAtMs) {
    return new SessionContext(
        app,
        instance,
        env,
        UUID.nameUUIDFromBytes(("s" + instance).getBytes(java.nio.charset.StandardCharsets.UTF_8)),
        1L,
        IpMode.MASKED,
        UserIdMode.OFF,
        500,
        receivedAtMs,
        "1.0",
        0);
  }

  static XlogPoint point(long tx, long tsMs, int elapsed, boolean error, long service) {
    return new XlogPoint(tx, tsMs, elapsed, TransactionKind.HTTP, service, error, 200, 0);
  }

  static XlogPoint point(long tx, long tsMs, int elapsed, TransactionKind kind, long service) {
    return new XlogPoint(tx, tsMs, elapsed, kind, service, false, 200, 0);
  }

  static AgentSession agentSession(int app, String env, int instance, long nowMs) {
    return new AgentSession(
        UUID.randomUUID(),
        UUID.randomUUID(),
        "argos_AbCdEfGh",
        new AppRef(app, "app-" + app, env),
        instance,
        "host-" + instance + ":8080",
        1L,
        IpMode.MASKED,
        UserIdMode.OFF,
        "1.0.0",
        "",
        false,
        null,
        List.of(),
        nowMs,
        new ClockSync(nowMs, nowMs),
        new SessionStream(new NoopObserver()));
  }

  static void snapshot(AgentSession s, long nowMs, int totalActive) {
    s.replaceSnapshot(
        new AgentSession.ActiveSnapshot(
            nowMs,
            ActiveTransactionSnapshot.newBuilder()
                .setSnapshotTsMs(nowMs)
                .setTotalActive(totalActive)
                .build()));
  }

  /** An outbox whose frames the test takes, with a wake-up counter. */
  static final class Collector {
    final List<Long> droppedHook = new CopyOnWriteArrayList<>();
    final SubscriberOutbox outbox;

    Collector() {
      this(SubscriberOutbox.BUDGET_BYTES);
    }

    Collector(long budget) {
      outbox = new SubscriberOutbox(budget, () -> {}, droppedHook::add);
    }

    /** Everything queued, parsed. */
    List<JsonNode> drain() {
      List<JsonNode> out = new ArrayList<>();
      String f;
      while ((f = outbox.poll()) != null) {
        out.add(JSON.readTree(f));
      }
      return out;
    }
  }

  static final class NoopObserver extends ServerCallStreamObserver<ServerMessage> {
    @Override
    public boolean isCancelled() {
      return false;
    }

    @Override
    public void setOnCancelHandler(Runnable handler) {}

    @Override
    public void setCompression(String compression) {}

    @Override
    public boolean isReady() {
      return true;
    }

    @Override
    public void setOnReadyHandler(Runnable handler) {}

    @Override
    public void disableAutoInboundFlowControl() {}

    @Override
    public void request(int count) {}

    @Override
    public void setMessageCompression(boolean enable) {}

    @Override
    public void onNext(ServerMessage value) {}

    @Override
    public void onError(Throwable t) {}

    @Override
    public void onCompleted() {}
  }
}
