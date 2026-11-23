/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.session;

import io.argos.proto.v1.ServerMessage;
import io.argos.server.session.api.InstanceStateChange;
import io.argos.server.session.api.IpMode;
import io.argos.server.session.api.UserIdMode;
import io.argos.server.testing.auth.MutableClock;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * An {@link InstanceStateTracker} on a fake clock with an in-memory registry, a recording store and
 * the published transitions (unit tests of WO-22).
 */
final class TrackerFixture implements AutoCloseable {

  final MutableClock clock = new MutableClock();
  final InMemorySessionRegistry registry = new InMemorySessionRegistry();
  final RecordingStore store = new RecordingStore();
  final InstanceStateWriter writer = new InstanceStateWriter(store, new SimpleMeterRegistry());
  final List<InstanceStateChange> changes = new CopyOnWriteArrayList<>();
  final InstanceStateTracker tracker;

  TrackerFixture() {
    this("1.4.0");
  }

  TrackerFixture(String serverVersion) {
    tracker =
        new InstanceStateTracker(
            new InstanceStateMachine(clock),
            registry,
            new AgentVersionRange(serverVersion),
            writer,
            changes::add);
  }

  /** A session of instance {@code instanceId} of app 7 accepted now (not registered). */
  AgentSession session(int instanceId, long agentEpoch, String agentVersion, String configHash) {
    return new AgentSession(
        UUID.randomUUID(),
        UUID.randomUUID(),
        "argos_AbCdEfGh",
        new AppRef(7, "order-api", "prod"),
        instanceId,
        "host-" + instanceId + ":8080",
        agentEpoch,
        IpMode.MASKED,
        UserIdMode.OFF,
        agentVersion,
        "",
        Boolean.FALSE,
        configHash,
        List.of(),
        clock.millis(),
        new ClockSync(clock.millis(), clock.millis()),
        new SessionStream(new RecordingObserver<ServerMessage>()));
  }

  /** Registers {@code s} and reports its Hello. */
  AgentSession connect(AgentSession s) {
    registry.register(s);
    tracker.onSessionStart(s);
    return s;
  }

  /** The events written so far (flushes the writer on this thread). */
  List<InstanceStateStore.EventRow> events() {
    writer.flush();
    return List.copyOf(store.events);
  }

  /** The state rows written so far. */
  List<InstanceStateStore.StateRow> rows() {
    writer.flush();
    return List.copyOf(store.rows);
  }

  @Override
  public void close() {
    writer.close();
  }

  /** A store that records what it is given. */
  static final class RecordingStore implements InstanceStateStore {
    final List<StateRow> rows = new ArrayList<>();
    final List<EventRow> events = new ArrayList<>();
    final List<OpenInstance> open = new ArrayList<>();
    final java.util.Map<Integer, java.time.Instant> archived = new java.util.LinkedHashMap<>();
    volatile boolean failing;
    int active;

    @Override
    public synchronized void writeStates(List<StateRow> r) {
      if (failing) {
        throw new IllegalStateException("PostgreSQL down");
      }
      rows.addAll(r);
    }

    @Override
    public synchronized void writeEvents(List<EventRow> e) {
      if (failing) {
        throw new IllegalStateException("PostgreSQL down");
      }
      events.addAll(e);
    }

    @Override
    public synchronized void archiveInstances(java.util.Map<Integer, java.time.Instant> a) {
      if (failing) {
        throw new IllegalStateException("PostgreSQL down");
      }
      archived.putAll(a);
    }

    @Override
    public List<OpenInstance> openInstances() {
      return List.copyOf(open);
    }

    @Override
    public int countActiveInstances() {
      return active;
    }
  }
}
