/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.api.apps;

import io.argos.proto.v1.ActiveTransaction;
import io.argos.proto.v1.TransactionKind;
import io.argos.server.config.ServerBuild;
import io.argos.server.session.AgentSession;
import io.argos.server.session.AgentVersionRange;
import io.argos.server.session.SessionRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * Instance states and live values of the query API from the in-memory session registry (WO-21,
 * {@code session/api}).
 *
 * <p><b>State (ADR-0010 C-160).</b> Until the state machine of WO-22 exists an instance is {@code
 * UP} while the registry has a session for it and {@code DOWN} otherwise; {@code STALE} and {@code
 * WARN} (12 §4.2) never appear before WO-22.
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

  /** A state of 14 §4.3 that exists before WO-22. */
  public static final String UP = "UP";

  /** A state of 14 §4.3 that exists before WO-22. */
  public static final String DOWN = "DOWN";

  /** How recent the end of a range must be for live values. */
  public static final int LIVE_WINDOW_MINUTES = 2;

  /** How old an active snapshot may be to count (twice the 5 s snapshot period, plus slack). */
  static final Duration SNAPSHOT_MAX_AGE = Duration.ofSeconds(15);

  /** Clock offsets from this size on count as skewed (12 §4.2 WARN condition, D-02). */
  public static final long SKEW_WARN_MS = 1_000;

  private static final Pattern MAJOR_MINOR = Pattern.compile("(\\d{1,9})\\.(\\d{1,9}).*");

  private final SessionRegistry registry;
  private final AgentVersionRange versions;
  private final Clock clock;

  public InstanceStates(SessionRegistry registry, ServerBuild build, Clock clock) {
    this.registry = registry;
    this.versions = new AgentVersionRange(build.version());
    this.clock = clock;
  }

  /** {@code UP} with a live session, {@code DOWN} without (C-160). */
  public String state(int instanceId) {
    return session(instanceId).isPresent() ? UP : DOWN;
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
