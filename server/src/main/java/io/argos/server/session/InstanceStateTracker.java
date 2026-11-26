/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.session;

import io.argos.proto.v1.AgentEvent;
import io.argos.proto.v1.ByeReason;
import io.argos.server.session.InstanceStateMachine.Decision;
import io.argos.server.session.InstanceStateMachine.Facts;
import io.argos.server.session.InstanceStateMachine.WarnFacts;
import io.argos.server.session.InstanceStateStore.EventRow;
import io.argos.server.session.InstanceStateStore.OpenInstance;
import io.argos.server.session.InstanceStateStore.StateRow;
import io.argos.server.session.api.InstanceState;
import io.argos.server.session.api.InstanceStateChange;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.json.JsonMapper;

/**
 * The instance states of the server (12 §4.2, F-S28): the facts of every instance that is not
 * {@code DOWN} (or has a session), their {@link InstanceStateMachine} decision, and each transition
 * persisted ({@code instances} state columns and an {@code events} row {@code type = 'state'}, the
 * {@code api.events.Outages} input contract) and published as an {@link InstanceStateChange}.
 *
 * <p>Inputs: the session stream (Hello accepted, heartbeat with {@code dropped_total}, {@code
 * AgentBye}, {@code AgentEvent}, end), the data path (server drops: {@code ACK_PARTIAL_DROP}, a
 * full WAL; the agent's leaked-context counter in a metric batch) and the {@code SessionMonitor}'s
 * {@link #tick} every 10 s (silence, configuration drift, periodic state rows). A hook decides at
 * once (a heartbeat after a STALE period makes the instance UP before its ack is sent); the clock
 * rules need the tick.
 *
 * <p>Agent start (F-A26, S-17, ADR-0010 C-240 (1)): the first accepted Hello of a new agent process
 * writes an {@code events} row {@code type = 'start'} (title {@code Agent start}, detail {@code
 * agent_version}). A process is its {@code agent_epoch}: a reconnect with an epoch this server has
 * seen writes none. For an epoch it has not seen, the Hello is a new process when it created the
 * instance, when the instance is tracked under another epoch, or when the instance's stored process
 * start ({@code instances.started_at}, read before the Hello wrote it) differs from {@code
 * AgentHello.process_start_ms}; so after a server restart the same process reconnecting writes
 * none. When the stored start cannot be read (PostgreSQL down) or the agent reports none, no start
 * is written. A {@code start} row is no state transition: {@code api.events.Outages} reads only
 * {@code state} rows of instances.
 *
 * <p>Thread-safe; every hook is short and never blocks: PostgreSQL writes go to the {@link
 * InstanceStateWriter} thread, listeners of the published change must not block. Bounded: an
 * instance is tracked while it has a session or is not {@code DOWN} (sessions are bounded by the
 * Hello checks, {@link #MAX_MODULES} disabled modules per instance).
 */
public final class InstanceStateTracker {

  /** Disabled modules remembered per instance (names cut to {@value #MAX_MODULE_CHARS}). */
  public static final int MAX_MODULES = 64;

  /** Characters kept of a module name. */
  public static final int MAX_MODULE_CHARS = 64;

  /** How often the state row of a live instance is refreshed ({@code last_seen_at}, offset). */
  public static final long PERSIST_INTERVAL_MS = 60_000;

  /** A stored offset is refreshed by the next tick once the live one moved this far. */
  public static final long OFFSET_PERSIST_STEP_MS = 100;

  /** Instances in the {@code argos_clock_offset_ms} gauge (largest offsets, 12 §10). */
  public static final int OFFSET_GAUGE_TOP = 50;

  /**
   * Agent processes ({@code agent_epoch}) remembered for the start rule, the least recently seen
   * forgotten beyond it: twice the server's live sessions ({@link HelloService#MAX_SESSIONS}),
   * about 1 MB at most.
   */
  public static final int MAX_SEEN_EPOCHS = 2 * HelloService.MAX_SESSIONS;

  private static final Logger LOG = LoggerFactory.getLogger(InstanceStateTracker.class);
  private static final JsonMapper JSON = JsonMapper.builder().build();

  private final InstanceStateMachine machine;
  private final SessionRegistry registry;
  private final AgentVersionRange versions;
  private final InstanceStateWriter writer;
  private final Consumer<InstanceStateChange> publisher;
  private final ConcurrentHashMap<Integer, Tracked> tracked = new ConcurrentHashMap<>();
  private final LinkedHashMap<Long, Boolean> seenEpochs =
      new LinkedHashMap<>(256, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<Long, Boolean> eldest) {
          return size() > MAX_SEEN_EPOCHS;
        }
      };

  /**
   * A tracker.
   *
   * @param machine the rules and the server clock
   * @param registry the live sessions
   * @param versions the accepted agent versions ({@code old_version})
   * @param writer the PostgreSQL writer
   * @param publisher where transitions go (Spring's {@code ApplicationEventPublisher})
   */
  public InstanceStateTracker(
      InstanceStateMachine machine,
      SessionRegistry registry,
      AgentVersionRange versions,
      InstanceStateWriter writer,
      Consumer<InstanceStateChange> publisher) {
    this.machine = Objects.requireNonNull(machine, "machine");
    this.registry = Objects.requireNonNull(registry, "registry");
    this.versions = Objects.requireNonNull(versions, "versions");
    this.writer = Objects.requireNonNull(writer, "writer");
    this.publisher = Objects.requireNonNull(publisher, "publisher");
  }

  // --- queries ---

  /** The state of {@code instanceId}: {@code DOWN} for an instance that is not tracked. */
  public InstanceState state(int instanceId) {
    Tracked t = tracked.get(instanceId);
    return t == null ? InstanceState.DOWN : t.state;
  }

  /** The reasons of the current state (the WARN conditions while {@code WARN}). */
  public Set<String> reasons(int instanceId) {
    Tracked t = tracked.get(instanceId);
    return t == null ? Set.of() : t.reasons;
  }

  /**
   * The modules the agent of {@code instanceId} reported disabled, sorted (empty if none); null for
   * an instance that is not tracked (unknown).
   */
  public List<String> modulesDisabled(int instanceId) {
    Tracked t = tracked.get(instanceId);
    if (t == null) {
      return null;
    }
    synchronized (t) {
      return List.copyOf(t.modulesDisabled);
    }
  }

  /** Tracked instances per state ({@code DOWN} counts only tracked ones). */
  public Map<InstanceState, Integer> counts() {
    Map<InstanceState, Integer> out = new EnumMap<>(InstanceState.class);
    for (InstanceState s : InstanceState.values()) {
      out.put(s, 0);
    }
    for (Tracked t : tracked.values()) {
      out.merge(t.state, 1, Integer::sum);
    }
    return out;
  }

  /**
   * The clock offsets of the live instances with the largest {@code |offset|}, at most {@value
   * #OFFSET_GAUGE_TOP} ({@code argos_clock_offset_ms{instance}}, 12 §10).
   */
  public Map<Integer, Long> topOffsets() {
    return registry.all().stream()
        .sorted(
            Comparator.comparingLong((AgentSession s) -> Math.abs(s.clock().estimateMs()))
                .reversed()
                .thenComparingInt(AgentSession::instanceId))
        .limit(OFFSET_GAUGE_TOP)
        .collect(
            Collectors.toMap(
                AgentSession::instanceId,
                s -> s.clock().estimateMs(),
                (a, b) -> a,
                LinkedHashMap::new));
  }

  // --- session hooks ---

  /**
   * An accepted Hello: the instance is UP (or WARN) now (a reconnect ends a Bye grace); a new agent
   * process also writes a {@code start} event (the class comment).
   */
  public InstanceState onSessionStart(AgentSession s) {
    long now = machine.clock().millis();
    boolean firstSeen = rememberEpoch(s.agentEpoch());
    InstanceStateChange change;
    InstanceState state;
    while (true) {
      Tracked t = tracked.computeIfAbsent(s.instanceId(), id -> new Tracked(id, s.appId(), env(s)));
      synchronized (t) {
        if (t.removed) {
          continue;
        }
        t.appId = s.appId();
        t.env = env(s);
        if (firstSeen && newProcess(t.agentEpoch, s)) {
          firstSeen = false;
          writer.enqueue(
              new EventRow(
                  UUID.randomUUID(),
                  s.appId(),
                  env(s),
                  s.instanceId(),
                  "start",
                  "Agent start",
                  json(Map.of("agent_version", s.agentVersion())),
                  Instant.ofEpochMilli(now)));
        }
        if (t.agentEpoch != s.agentEpoch()) {
          // a new agent process: its modules and kill switch start over
          t.modulesDisabled.clear();
          t.killSwitch = false;
        }
        t.agentEpoch = s.agentEpoch();
        t.byeAtMs = InstanceStateMachine.NEVER;
        t.lastHeartbeatMs = s.acceptedAtMs();
        t.heartbeatSeen = true;
        t.lastDroppedTotal = -1;
        t.oldVersion = versions.atLowerBound(s.agentVersion());
        change = evaluate(t, now, true);
        state = t.state;
      }
      break;
    }
    publish(change);
    return state;
  }

  /** Records {@code epoch} as seen; true when this server had not seen it (or forgot it). */
  private boolean rememberEpoch(long epoch) {
    synchronized (seenEpochs) {
      return seenEpochs.put(epoch, Boolean.TRUE) == null;
    }
  }

  /**
   * Whether the first Hello of {@code s}'s epoch on this server is a new agent process (the class
   * comment), given the epoch the instance is tracked under ({@code Long.MIN_VALUE}: untracked).
   */
  static boolean newProcess(long trackedEpoch, AgentSession s) {
    long stored = s.storedStartMs();
    if (stored == AgentSession.STARTED_NEW_INSTANCE) {
      return true;
    }
    if (trackedEpoch != Long.MIN_VALUE) {
      return trackedEpoch != s.agentEpoch();
    }
    if (stored == AgentSession.STARTED_UNKNOWN || s.processStartMs() <= 0) {
      return false;
    }
    return stored == AgentSession.STARTED_NONE || stored != s.processStartMs();
  }

  /**
   * A heartbeat of {@code s} received at {@code receivedAtMs} with the agent's cumulative {@code
   * dropped_total}; returns the state for its {@code HeartbeatAck}.
   */
  public InstanceState onHeartbeat(AgentSession s, long receivedAtMs, long droppedTotal) {
    Tracked t = tracked.get(s.instanceId());
    if (t == null) {
      onSessionStart(s);
      t = tracked.get(s.instanceId());
      if (t == null) {
        return InstanceState.UP;
      }
    }
    InstanceStateChange change;
    InstanceState state;
    synchronized (t) {
      t.lastHeartbeatMs = Math.max(t.lastHeartbeatMs, receivedAtMs);
      t.heartbeatSeen = true;
      // An agent that keeps beating after its Bye is not shutting down (12 §4.2 grace ends).
      t.byeAtMs = InstanceStateMachine.NEVER;
      if (t.lastDroppedTotal >= 0 && droppedTotal > t.lastDroppedTotal) {
        t.lastDropMs = receivedAtMs;
      }
      t.lastDroppedTotal = droppedTotal;
      change = evaluate(t, machine.clock().millis(), false);
      state = t.state;
    }
    publish(change);
    return state;
  }

  /**
   * {@code AgentBye} (12 §4.2, S-17): an {@code events} row {@code type = 'stop'} for the instance
   * and the 5 minute grace; a reconnect inside it never makes the instance DOWN.
   */
  public void onBye(AgentSession s, ByeReason reason) {
    long now = machine.clock().millis();
    Tracked t = tracked.get(s.instanceId());
    if (t == null) {
      return;
    }
    synchronized (t) {
      t.byeAtMs = now;
    }
    String name = reason == null ? "BYE_REASON_UNSPECIFIED" : reason.name();
    writer.enqueue(
        new EventRow(
            UUID.randomUUID(),
            s.appId(),
            env(s),
            s.instanceId(),
            "stop",
            "Agent shutdown",
            json(Map.of("reason", name.toLowerCase(Locale.ROOT))),
            Instant.ofEpochMilli(now)));
  }

  /**
   * {@code AgentEvent}: {@code MODULE_DISABLED} and {@code KILL_SWITCH_ON}/{@code _OFF} change the
   * WARN conditions, {@code QUEUE_DROP_BURST} counts as an agent drop; the others are ignored here.
   */
  public void onEvent(AgentSession s, AgentEvent event) {
    long now = machine.clock().millis();
    Tracked t = tracked.get(s.instanceId());
    if (t == null) {
      return;
    }
    InstanceStateChange change;
    synchronized (t) {
      switch (event.getType()) {
        case MODULE_DISABLED -> {
          String module = SessionStore.cut(event.getModule().strip(), MAX_MODULE_CHARS);
          if (module.isEmpty()) {
            module = "unknown";
          }
          if (t.modulesDisabled.size() < MAX_MODULES || t.modulesDisabled.contains(module)) {
            t.modulesDisabled.add(module);
          }
        }
        case KILL_SWITCH_ON -> t.killSwitch = true;
        case KILL_SWITCH_OFF -> t.killSwitch = false;
        case QUEUE_DROP_BURST -> t.lastDropMs = now;
        default -> {
          return;
        }
      }
      change = evaluate(t, now, false);
    }
    publish(change);
  }

  /** The session stream of {@code s} ended (cancelled): the silence rules take over. */
  public void onSessionEnd(AgentSession s) {
    Tracked t = tracked.get(s.instanceId());
    if (t == null) {
      return;
    }
    InstanceStateChange change;
    synchronized (t) {
      change = evaluate(t, machine.clock().millis(), false);
    }
    publish(change);
  }

  /**
   * Instance {@code instanceId} will not come back under its id (a temporary {@code host:pid}
   * instance merged into the existing instance of its new name, 12 §4.3, ADR-0010 C-158 (2)): it
   * stops being tracked without a transition (no state event, no published change: it never went
   * down) and its row is archived ({@code DOWN}, {@code archived_at} now).
   */
  public void retire(int instanceId) {
    Tracked t = tracked.get(instanceId);
    if (t != null) {
      synchronized (t) {
        t.removed = true;
        tracked.remove(instanceId, t);
      }
    }
    writer.archive(instanceId, Instant.ofEpochMilli(machine.clock().millis()));
  }

  // --- data path hooks ---

  /** The server dropped items of {@code instanceId} ({@code ACK_PARTIAL_DROP}, a full WAL). */
  public void onServerDrop(int instanceId) {
    touch(instanceId, t -> t.lastDropMs = machine.clock().millis());
  }

  /** The agent's leaked-context counter of {@code instanceId} grew (PM question 4 assumption). */
  public void onContextLeak(int instanceId) {
    touch(instanceId, t -> t.lastLeakMs = machine.clock().millis());
  }

  private void touch(int instanceId, Consumer<Tracked> update) {
    Tracked t = tracked.get(instanceId);
    if (t == null) {
      return;
    }
    InstanceStateChange change;
    synchronized (t) {
      update.accept(t);
      change = evaluate(t, machine.clock().millis(), false);
    }
    publish(change);
  }

  // --- monitor ---

  /**
   * Instances the server left up when it stopped (12 §4.2, WO-22 assumption): their last heartbeat
   * counts as {@code startMs}, so that they become STALE 60 s and DOWN 180 s after the start unless
   * their agent reconnects. An instance that already has a session is left alone.
   */
  public void restore(Collection<OpenInstance> open, long startMs) {
    for (OpenInstance o : open) {
      InstanceState stored;
      try {
        stored = InstanceState.valueOf(o.status().strip().toUpperCase(Locale.ROOT));
      } catch (IllegalArgumentException e) {
        continue;
      }
      if (stored == InstanceState.DOWN) {
        continue;
      }
      Tracked fresh = new Tracked(o.instanceId(), o.appId(), o.env());
      fresh.state = stored;
      fresh.reasons =
          stored == InstanceState.WARN
              ? Set.copyOf(o.warnFlags())
              : stored == InstanceState.STALE
                  ? Set.of(InstanceStateMachine.SESSION_LOST)
                  : Set.of();
      fresh.lastHeartbeatMs = startMs;
      fresh.clockOffsetMs = o.clockOffsetMs() == null ? null : o.clockOffsetMs().longValue();
      tracked.putIfAbsent(o.instanceId(), fresh);
    }
  }

  /**
   * Evaluates every instance at the server clock's now (the {@code SessionMonitor}, every 10 s):
   * configuration drift per application, the silence and Bye rules, a refreshed state row of each
   * live instance every {@value #PERSIST_INTERVAL_MS} ms; a {@code DOWN} instance without a session
   * stops being tracked.
   */
  public void tick() {
    long now = machine.clock().millis();
    for (AgentSession s : registry.all()) {
      if (!tracked.containsKey(s.instanceId())) {
        onSessionStart(s);
      }
    }
    Map<Integer, Boolean> drift = drift(registry.all());
    List<InstanceStateChange> changes = new ArrayList<>();
    for (Tracked t : tracked.values()) {
      synchronized (t) {
        if (t.removed) {
          continue;
        }
        t.configDrift = drift.getOrDefault(t.instanceId, false);
        InstanceStateChange c = evaluate(t, now, false);
        if (c != null) {
          changes.add(c);
        }
        boolean live = registry.find(t.instanceId).isPresent();
        if (c == null && live && (now - t.lastPersistMs >= PERSIST_INTERVAL_MS || offsetMoved(t))) {
          persistRow(t, now);
        }
        if (t.state == InstanceState.DOWN && !live) {
          t.removed = true;
          tracked.remove(t.instanceId, t);
        }
      }
    }
    for (InstanceStateChange c : changes) {
      publish(c);
    }
  }

  /** The configuration drift of each live instance (12 §4.2: hash ≠ the application's majority). */
  static Map<Integer, Boolean> drift(Collection<AgentSession> sessions) {
    Map<Integer, Map<String, Integer>> counts = new HashMap<>();
    for (AgentSession s : sessions) {
      String h = s.configHash();
      if (h != null && !h.isBlank()) {
        counts.computeIfAbsent(s.appId(), k -> new HashMap<>()).merge(h, 1, Integer::sum);
      }
    }
    Map<Integer, String> majority = new HashMap<>();
    counts.forEach(
        (app, byHash) ->
            byHash.entrySet().stream()
                .max(
                    Comparator.comparing(Map.Entry<String, Integer>::getValue)
                        .thenComparing(Map.Entry::getKey, Comparator.reverseOrder()))
                .ifPresent(e -> majority.put(app, e.getKey())));
    Map<Integer, Boolean> out = new HashMap<>();
    for (AgentSession s : sessions) {
      String h = s.configHash();
      String m = majority.get(s.appId());
      out.put(s.instanceId(), h != null && !h.isBlank() && m != null && !h.equals(m));
    }
    return out;
  }

  // --- internals ---

  /** Decides under {@code t}'s lock; persists a transition (or a forced row) and returns it. */
  private InstanceStateChange evaluate(Tracked t, long now, boolean forceRow) {
    AgentSession live = registry.find(t.instanceId).orElse(null);
    if (live != null) {
      t.clockOffsetMs = live.clock().estimateMs();
    }
    Facts facts =
        new Facts(
            t.state,
            t.reasons,
            t.lastHeartbeatMs,
            t.byeAtMs,
            live != null,
            new WarnFacts(
                t.clockOffsetMs,
                t.lastDropMs,
                t.oldVersion,
                t.modulesDisabled,
                t.killSwitch,
                t.configDrift,
                t.lastLeakMs));
    Decision d = machine.next(facts, now);
    InstanceState from = t.state;
    boolean changed = d.state() != from;
    boolean reasonsChanged = !d.reasons().equals(t.reasons);
    t.state = d.state();
    t.reasons = d.reasons();
    if (!changed) {
      if (reasonsChanged || forceRow) {
        persistRow(t, now);
      }
      return null;
    }
    persistRow(t, now);
    Instant at = Instant.ofEpochMilli(now);
    InstanceStateChange change =
        new InstanceStateChange(t.instanceId, t.appId, t.env, from, d.state(), d.reasons(), at);
    List<String> sorted = new ArrayList<>(new TreeSet<>(d.reasons()));
    Map<String, Object> detail = new LinkedHashMap<>();
    detail.put("from", from.name());
    detail.put("to", d.state().name());
    detail.put("reason", sorted.isEmpty() ? "recovered" : sorted.get(0));
    detail.put("reasons", sorted);
    writer.enqueue(
        new EventRow(
            UUID.randomUUID(),
            t.appId,
            t.env,
            t.instanceId,
            "state",
            "Instance " + from.name() + " to " + d.state().name(),
            json(detail),
            at));
    return change;
  }

  /** Whether the offset moved {@value #OFFSET_PERSIST_STEP_MS} ms or more since it was stored. */
  private static boolean offsetMoved(Tracked t) {
    if (t.clockOffsetMs == null) {
      return false;
    }
    return t.persistedOffsetMs == null
        || Math.abs(t.clockOffsetMs - t.persistedOffsetMs) >= OFFSET_PERSIST_STEP_MS;
  }

  private void persistRow(Tracked t, long now) {
    t.lastPersistMs = now;
    t.persistedOffsetMs = t.clockOffsetMs;
    List<String> flags =
        t.state == InstanceState.WARN ? new ArrayList<>(new TreeSet<>(t.reasons)) : List.of();
    Integer offset =
        t.clockOffsetMs == null
            ? null
            : (int) Math.max(Integer.MIN_VALUE, Math.min(Integer.MAX_VALUE, t.clockOffsetMs));
    Instant seen = t.heartbeatSeen ? Instant.ofEpochMilli(t.lastHeartbeatMs) : null;
    writer.enqueue(new StateRow(t.instanceId, t.state.name(), flags, offset, seen));
  }

  private void publish(InstanceStateChange change) {
    if (change == null) {
      return;
    }
    try {
      publisher.accept(change);
    } catch (RuntimeException e) {
      LOG.error("A listener of instance state changes failed", e);
    }
  }

  private static String env(AgentSession s) {
    return s.app().env();
  }

  private static String json(Map<String, ?> value) {
    return JSON.writeValueAsString(value);
  }

  /** The facts of one instance; guarded by its own monitor (state and reasons read without). */
  private static final class Tracked {
    final int instanceId;
    int appId;
    String env;
    volatile InstanceState state = InstanceState.DOWN;
    volatile Set<String> reasons = Set.of();
    long lastHeartbeatMs;
    boolean heartbeatSeen;
    long byeAtMs = InstanceStateMachine.NEVER;
    long agentEpoch = Long.MIN_VALUE;
    long lastDroppedTotal = -1;
    long lastDropMs = InstanceStateMachine.NEVER;
    boolean oldVersion;
    final TreeSet<String> modulesDisabled = new TreeSet<>();
    boolean killSwitch;
    boolean configDrift;
    long lastLeakMs = InstanceStateMachine.NEVER;
    Long clockOffsetMs;
    Long persistedOffsetMs;
    long lastPersistMs = Long.MIN_VALUE / 2;
    boolean removed;

    Tracked(int instanceId, int appId, String env) {
      this.instanceId = instanceId;
      this.appId = appId;
      this.env = env;
    }
  }
}
