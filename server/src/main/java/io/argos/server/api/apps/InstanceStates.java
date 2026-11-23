/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.api.apps;

import io.argos.proto.v1.ActiveTransaction;
import io.argos.proto.v1.TransactionKind;
import io.argos.server.config.ServerBuild;
import io.argos.server.session.AgentSession;
import io.argos.server.session.AgentVersionRange;
import io.argos.server.session.InstanceStateMachine;
import io.argos.server.session.InstanceStateTracker;
import io.argos.server.session.SessionRegistry;
import io.argos.server.session.api.InstanceState;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.IntFunction;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * Instance states and live values of the query API from the in-memory session registry (WO-21,
 * {@code session/api}) and the instance state machine (WO-22).
 *
 * <p><b>State (12 §4.2, F-S28).</b> The value of the server's {@link InstanceStateTracker}: {@code
 * UP}, {@code STALE} (60 s without a heartbeat), {@code DOWN} (180 s without a heartbeat, 5 minutes
 * after an {@code AgentBye}, or never connected since) or {@code WARN} (up with a WARN condition).
 * A context without the collector (no state machine) falls back to "live session = UP, otherwise
 * DOWN".
 *
 * <p><b>Application summary.</b> The status of an application is the worst state of its instances
 * that are not archived ({@code DOWN} &gt; {@code STALE} &gt; {@code WARN} &gt; {@code UP}; {@code
 * DOWN} without instances), and its up count is the number of those instances in {@code UP} or
 * {@code WARN} (a {@code WARN} instance is connected and collecting; ADR-0010 C-216 (3), WO-31
 * assumption): {@link #summarize(Collection)}, used by the overview and the application views.
 *
 * <p><b>Live values.</b> Active transactions, running batches and clock offsets exist only now
 * (latest active snapshot of each session, 5 s; 14 §4.9): {@link #live} answers them only for a
 * range that ends in the last {@value #LIVE_WINDOW_MINUTES} minutes, null otherwise, and null when
 * no session has a recent snapshot (D-23: unknown is not 0).
 *
 * <p><b>Compatibility.</b> {@code ok}: the agent's major.minor is the server's; {@code old}: an
 * older minor still accepted (11 §5 check 4: up to two behind); {@code unsupported}: outside the
 * range. Null without an agent version.
 */
@Component
public class InstanceStates {

  /** A state of 14 §4.3: connected, no WARN condition. */
  public static final String UP = "UP";

  /** A state of 14 §4.3: no heartbeat for 180 s, after a Bye grace, or never connected. */
  public static final String DOWN = "DOWN";

  /** A state of 14 §4.3: no heartbeat for 60 s. */
  public static final String STALE = "STALE";

  /** A state of 14 §4.3: up with a WARN condition. */
  public static final String WARN = "WARN";

  /**
   * Most instance ids read for the summaries of one application list (the overview and {@code GET
   * /apps}: 500 applications); far above any installation of 1.0 (N-12).
   */
  public static final int SUMMARY_INSTANCES_MAX = 100_000;

  /** How recent the end of a range must be for live values. */
  public static final int LIVE_WINDOW_MINUTES = 2;

  /** How old an active snapshot may be to count (twice the 5 s snapshot period, plus slack). */
  static final Duration SNAPSHOT_MAX_AGE = Duration.ofSeconds(15);

  /** Clock offsets from this size on count as skewed (12 §4.2 WARN condition, D-02). */
  public static final long SKEW_WARN_MS = InstanceStateMachine.SKEW_WARN_MS;

  private static final Pattern MAJOR_MINOR = Pattern.compile("(\\d{1,9})\\.(\\d{1,9}).*");

  private final SessionRegistry registry;
  private final AgentVersionRange versions;
  private final Clock clock;
  private final InstanceStateTracker tracker;

  public InstanceStates(
      SessionRegistry registry,
      ServerBuild build,
      Clock clock,
      ObjectProvider<InstanceStateTracker> tracker) {
    this.registry = registry;
    this.versions = new AgentVersionRange(build.version());
    this.clock = clock;
    this.tracker = tracker.getIfAvailable();
  }

  /**
   * {@code UP}, {@code STALE}, {@code DOWN} or {@code WARN} (12 §4.2); without a state machine (no
   * collector in the context) {@code UP} with a live session and {@code DOWN} without.
   */
  public String state(int instanceId) {
    if (tracker != null) {
      return tracker.state(instanceId).name();
    }
    return session(instanceId).isPresent() ? UP : DOWN;
  }

  /**
   * The rank of a state for {@link #worst}: {@code UP} 0, {@code WARN} 1, {@code STALE} 2, {@code
   * DOWN} 3; an unknown value ranks as {@code DOWN} (never hide a state the API does not know).
   */
  static int rank(String state) {
    if (UP.equals(state)) {
      return 0;
    }
    if (WARN.equals(state)) {
      return 1;
    }
    if (STALE.equals(state)) {
      return 2;
    }
    return 3;
  }

  /**
   * The worst of {@code states} ({@code DOWN} &gt; {@code STALE} &gt; {@code WARN} &gt; {@code
   * UP}); {@code DOWN} when there is none (an application without instances collects nothing).
   */
  public static String worst(Collection<String> states) {
    String out = null;
    for (String s : states) {
      String known = rank(s) == 3 ? DOWN : s;
      if (out == null || rank(known) > rank(out)) {
        out = known;
      }
    }
    return out == null ? DOWN : out;
  }

  /** Whether an instance in {@code state} counts as up: {@code UP} or {@code WARN}. */
  public static boolean countsAsUp(String state) {
    return UP.equals(state) || WARN.equals(state);
  }

  /**
   * The status and up count of an application whose instances (not archived) are {@code
   * instanceIds}, their states read through {@code state} (class comment).
   */
  public static Summary summarize(Collection<Integer> instanceIds, IntFunction<String> state) {
    List<String> states = new ArrayList<>(instanceIds.size());
    int up = 0;
    for (int id : instanceIds) {
      String s = state.apply(id);
      states.add(s);
      if (countsAsUp(s)) {
        up++;
      }
    }
    return new Summary(worst(states), up);
  }

  /** The status and up count of an application from the states of {@link #state(int)}. */
  public Summary summarize(Collection<Integer> instanceIds) {
    return summarize(instanceIds, this::state);
  }

  /**
   * An application's instance summary (class comment).
   *
   * @param status the worst instance state, {@code DOWN} without instances
   * @param up instances in {@code UP} or {@code WARN}
   */
  public record Summary(String status, int up) {}

  /** The WARN conditions of an instance while it is {@code WARN}, sorted; otherwise empty. */
  public List<String> warnReasons(int instanceId) {
    if (tracker == null || tracker.state(instanceId) != InstanceState.WARN) {
      return List.of();
    }
    return new TreeSet<>(tracker.reasons(instanceId)).stream().toList();
  }

  /**
   * The modules the agent of a tracked instance reported disabled ({@code AgentEvent
   * MODULE_DISABLED}), sorted; null for an instance that is not tracked (DOWN) or without a state
   * machine (unknown).
   */
  public List<String> modulesDisabled(int instanceId) {
    return tracker == null ? null : tracker.modulesDisabled(instanceId);
  }

  /** The live session of an instance. */
  public Optional<AgentSession> session(int instanceId) {
    Optional<AgentSession> s = registry.find(instanceId);
    return s == null ? Optional.empty() : s;
  }

  /** The live sessions of an application. */
  public Collection<AgentSession> sessions(int appId) {
    Collection<AgentSession> s = registry.byApp(appId);
    return s == null ? List.of() : s;
  }

  /** Live sessions of the whole server. */
  public int sessionCount() {
    return registry.size();
  }

  /** {@code ok}, {@code old}, {@code unsupported}, or null without a version (class comment). */
  public String compat(String agentVersion) {
    if (agentVersion == null || agentVersion.isBlank()) {
      return null;
    }
    if (versions.check(agentVersion) != AgentVersionRange.Verdict.ACCEPTED) {
      return "unsupported";
    }
    String max = versions.max();
    Matcher m = MAJOR_MINOR.matcher(agentVersion.strip());
    if (max.isEmpty() || !m.matches()) {
      return "ok";
    }
    return (m.group(1) + "." + m.group(2)).equals(max) ? "ok" : "old";
  }

  /** Whether a range that ends at {@code to} is recent enough for live values. */
  public boolean isLive(Instant to) {
    return !to.isBefore(clock.instant().minus(Duration.ofMinutes(LIVE_WINDOW_MINUTES)));
  }

  /**
   * The live values of an application's sessions, restricted to {@code instances} unless empty.
   *
   * @param to the end of the requested range: values are null unless {@link #isLive(Instant)}
   */
  public Live live(int appId, Set<Integer> instances, Instant to) {
    if (!isLive(to)) {
      return Live.UNKNOWN;
    }
    long now = clock.millis();
    long active = 0;
    long batches = 0;
    int skewed = 0;
    boolean snapshots = false;
    Map<Integer, Long> activeByInstance = new HashMap<>();
    Map<Integer, Long> skew = new HashMap<>();
    for (AgentSession s : sessions(appId)) {
      if (!instances.isEmpty() && !instances.contains(s.instanceId())) {
        continue;
      }
      long offset = s.clock().estimateMs();
      skew.put(s.instanceId(), offset);
      if (Math.abs(offset) >= SKEW_WARN_MS) {
        skewed++;
      }
      AgentSession.ActiveSnapshot snapshot = s.latestSnapshot();
      if (snapshot == null || now - snapshot.receivedAtMs() > SNAPSHOT_MAX_AGE.toMillis()) {
        continue;
      }
      snapshots = true;
      long total = snapshot.snapshot().getTotalActive();
      active += total;
      activeByInstance.put(s.instanceId(), total);
      for (ActiveTransaction tx : snapshot.snapshot().getItemsList()) {
        if (tx.getKind() == TransactionKind.BATCH) {
          batches++;
        }
      }
    }
    return new Live(
        snapshots ? active : null,
        snapshots ? batches : null,
        skewed,
        Map.copyOf(activeByInstance),
        Map.copyOf(skew));
  }

  /**
   * Live values (class comment).
   *
   * @param active active transactions of the sessions with a recent snapshot, or null
   * @param batchRunning running {@code BATCH} transactions in those snapshots (their first 500
   *     items), or null
   * @param clockSkewInstances live instances with {@code |offset| >= 1 s}, or null when not live
   * @param activeByInstance active transactions per instance with a recent snapshot
   * @param skewMs the clock offset of each live instance
   */
  public record Live(
      Long active,
      Long batchRunning,
      Integer clockSkewInstances,
      Map<Integer, Long> activeByInstance,
      Map<Integer, Long> skewMs) {

    /** Not live: every value unknown. */
    public static final Live UNKNOWN = new Live(null, null, null, Map.of(), Map.of());
  }
}
