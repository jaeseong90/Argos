/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.session;

import io.argos.server.session.api.InstanceState;
import java.time.Clock;
import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/**
 * The instance state rules of 12 §4.2 (F-S28, ADR-0009 C-8) as pure functions of the facts the
 * server knows about one instance; no thread, no storage (the {@link InstanceStateTracker} keeps
 * the facts, the {@code SessionMonitor} calls it every 10 s).
 *
 * <ul>
 *   <li>{@code STALE}: {@value #STALE_AFTER_S} s since the last heartbeat and no {@code AgentBye};
 *   <li>{@code DOWN}: {@value #DOWN_AFTER_S} s since the last heartbeat (STALE plus 2 minutes), or
 *       {@value #BYE_GRACE_S} s after an {@code AgentBye} without a reconnect (S-17: a reconnect
 *       inside the grace never makes the instance DOWN, so no down notification fires). During the
 *       grace the state of the moment of the Bye is held (12 §4.2: STALE requires "no AgentBye");
 *   <li>{@code UP}/{@code WARN}: a live session with a heartbeat younger than {@value
 *       #STALE_AFTER_S} s, {@code WARN} while one of the {@link #warnFlags WARN conditions} holds;
 *   <li>without a live session but with a fresh heartbeat (a stream that just ended, or an instance
 *       the server found up at its start) the current state is held until the silence rules apply.
 * </ul>
 *
 * Boundaries are inclusive: 60 s of silence is {@code STALE}, 59.999 s is not; 180 s is {@code
 * DOWN}; a Bye exactly 5 minutes old is {@code DOWN}.
 */
public final class InstanceStateMachine {

  /** Seconds without a heartbeat before {@code STALE} (2 × the 30 s heartbeat, 12 §4.2). */
  public static final long STALE_AFTER_S = 60;

  /** Seconds without a heartbeat before {@code DOWN} (STALE + 2 minutes). */
  public static final long DOWN_AFTER_S = 180;

  /** Seconds after an {@code AgentBye} before {@code DOWN} without a reconnect (S-17). */
  public static final long BYE_GRACE_S = 300;

  /** Clock offsets from this size on are a WARN condition (12 §4.2, D-02). */
  public static final long SKEW_WARN_MS = 1_000;

  /** How long a drop, an agent drop burst or a context leak keeps the instance WARN (12 §4.2). */
  public static final Duration RECENT = Duration.ofMinutes(5);

  /** WARN: {@code |clock offset| >= 1,000 ms} (D-02). */
  public static final String CLOCK_SKEW = "clock_skew";

  /** WARN: agent or server drops in the last 5 minutes. */
  public static final String DROPS = "drops";

  /** WARN: the agent version is the lowest one the server accepts (12 §4.2 "호환 범위 하한"). */
  public static final String OLD_VERSION = "old_version";

  /** WARN: the agent disabled an instrumentation module ({@code MODULE_DISABLED}, F-A15). */
  public static final String MODULE_DISABLED = "module_disabled";

  /** WARN: the agent's kill switch is on ({@code KILL_SWITCH_ON} until {@code KILL_SWITCH_OFF}). */
  public static final String KILL_SWITCH = "kill_switch";

  /** WARN: the configuration hash differs from the majority of the application's instances. */
  public static final String CONFIG_DRIFT = "config_drift";

  /** WARN: the agent's leaked-context counter grew in the last 5 minutes (01 F-S28). */
  public static final String CONTEXT_LEAK = "context_leak";

  /** STALE/DOWN reason: no heartbeat (the {@code Outages} reason {@code session_lost}). */
  public static final String SESSION_LOST = "session_lost";

  /** DOWN reason: the grace after an {@code AgentBye} ended without a reconnect. */
  public static final String AGENT_BYE = "agent_bye";

  /** Every WARN condition, in the order of 12 §4.2 and 01 F-S28. */
  public static final List<String> WARN_FLAGS =
      List.of(
          CLOCK_SKEW, DROPS, OLD_VERSION, MODULE_DISABLED, KILL_SWITCH, CONFIG_DRIFT, CONTEXT_LEAK);

  /** No {@code AgentBye} / no event time. */
  public static final long NEVER = Long.MIN_VALUE;

  private final Clock clock;
  private final long staleAfterMs;
  private final long downAfterMs;
  private final long byeGraceMs;
  private final long skewWarnMs;

  /** The rules of 12 §4.2 with the server clock {@code clock}. */
  public InstanceStateMachine(Clock clock) {
    this(
        clock,
        Duration.ofSeconds(STALE_AFTER_S),
        Duration.ofSeconds(DOWN_AFTER_S),
        Duration.ofSeconds(BYE_GRACE_S),
        SKEW_WARN_MS);
  }

  /**
   * The rules with configured durations ({@code argos.collector.stale-after}, {@code down-after},
   * {@code bye-grace}).
   */
  public InstanceStateMachine(
      Clock clock, Duration staleAfter, Duration downAfter, Duration byeGrace, long skewWarnMs) {
    this.clock = Objects.requireNonNull(clock, "clock");
    this.staleAfterMs = staleAfter.toMillis();
    this.downAfterMs = downAfter.toMillis();
    this.byeGraceMs = byeGrace.toMillis();
    this.skewWarnMs = skewWarnMs;
    if (!(staleAfterMs > 0 && staleAfterMs < downAfterMs && byeGraceMs > 0)) {
      throw new IllegalArgumentException("0 < stale-after < down-after and bye-grace > 0");
    }
  }

  /** The server clock. */
  public Clock clock() {
    return clock;
  }

  /** The next state of {@code facts} now. */
  public Decision next(Facts facts) {
    return next(facts, clock.millis());
  }

  /** The next state of {@code facts} at server time {@code nowMs}. */
  public Decision next(Facts facts, long nowMs) {
    if (facts.byeAtMs() != NEVER) {
      if (nowMs - facts.byeAtMs() >= byeGraceMs) {
        return new Decision(InstanceState.DOWN, Set.of(AGENT_BYE));
      }
      return hold(facts);
    }
    long silence = nowMs - facts.lastHeartbeatMs();
    if (silence >= downAfterMs) {
      return new Decision(InstanceState.DOWN, Set.of(SESSION_LOST));
    }
    if (silence >= staleAfterMs) {
      return new Decision(InstanceState.STALE, Set.of(SESSION_LOST));
    }
    if (!facts.live()) {
      return hold(facts);
    }
    Set<String> warn = warnFlags(facts.warn(), nowMs);
    return warn.isEmpty()
        ? new Decision(InstanceState.UP, Set.of())
        : new Decision(InstanceState.WARN, warn);
  }

  /**
   * The WARN conditions that hold at {@code nowMs} (12 §4.2 and 01 F-S28, the PM question 4
   * assumption of WO-22: their union), sorted by name.
   */
  public Set<String> warnFlags(WarnFacts w, long nowMs) {
    TreeSet<String> flags = new TreeSet<>();
    if (w.clockOffsetMs() != null && Math.abs(w.clockOffsetMs()) >= skewWarnMs) {
      flags.add(CLOCK_SKEW);
    }
    if (recent(w.lastDropMs(), nowMs)) {
      flags.add(DROPS);
    }
    if (w.oldVersion()) {
      flags.add(OLD_VERSION);
    }
    if (!w.modulesDisabled().isEmpty()) {
      flags.add(MODULE_DISABLED);
    }
    if (w.killSwitch()) {
      flags.add(KILL_SWITCH);
    }
    if (w.configDrift()) {
      flags.add(CONFIG_DRIFT);
    }
    if (recent(w.lastLeakMs(), nowMs)) {
      flags.add(CONTEXT_LEAK);
    }
    return Collections.unmodifiableSet(flags);
  }

  private static boolean recent(long atMs, long nowMs) {
    return atMs != NEVER && nowMs - atMs < RECENT.toMillis();
  }

  private static Decision hold(Facts facts) {
    return new Decision(facts.current(), facts.currentReasons());
  }

  /**
   * What the server knows about one instance.
   *
   * @param current the current state
   * @param currentReasons the reasons of the current state
   * @param lastHeartbeatMs server time of the last heartbeat (the ServerHello before the first; the
   *     server start for an instance found up at the start)
   * @param byeAtMs server time of the {@code AgentBye} of the last session, {@link #NEVER} without
   * @param live whether the instance has a session in the registry
   * @param warn the inputs of the WARN conditions
   */
  public record Facts(
      InstanceState current,
      Set<String> currentReasons,
      long lastHeartbeatMs,
      long byeAtMs,
      boolean live,
      WarnFacts warn) {

    /** Validates and copies. */
    public Facts {
      Objects.requireNonNull(current, "current");
      currentReasons = currentReasons == null ? Set.of() : Set.copyOf(currentReasons);
      Objects.requireNonNull(warn, "warn");
    }
  }

  /**
   * The inputs of the WARN conditions of one instance.
   *
   * @param clockOffsetMs the session's clock offset (server − agent, C-139), null when unknown
   * @param lastDropMs server time of the last agent or server drop, {@link #NEVER} without
   * @param oldVersion whether the agent version is the lowest accepted one
   * @param modulesDisabled modules the agent reported disabled
   * @param killSwitch whether the agent's kill switch is on
   * @param configDrift whether the configuration hash differs from the application's majority
   * @param lastLeakMs server time of the last growth of the leaked-context counter, {@link #NEVER}
   *     without
   */
  public record WarnFacts(
      Long clockOffsetMs,
      long lastDropMs,
      boolean oldVersion,
      Set<String> modulesDisabled,
      boolean killSwitch,
      boolean configDrift,
      long lastLeakMs) {

    /** No WARN input. */
    public static final WarnFacts NONE =
        new WarnFacts(null, NEVER, false, Set.of(), false, false, NEVER);

    /** Validates and copies. */
    public WarnFacts {
      modulesDisabled = modulesDisabled == null ? Set.of() : Set.copyOf(modulesDisabled);
    }
  }

  /**
   * The outcome of {@link #next}.
   *
   * @param state the state
   * @param reasons the reasons ({@link io.argos.server.session.api.InstanceStateChange#reasons})
   */
  public record Decision(InstanceState state, Set<String> reasons) {

    /** Validates and copies. */
    public Decision {
      Objects.requireNonNull(state, "state");
      reasons = reasons == null ? Set.of() : Set.copyOf(reasons);
    }
  }
}
