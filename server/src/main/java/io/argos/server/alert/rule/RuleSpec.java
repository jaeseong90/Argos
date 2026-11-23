/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.alert.rule;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.LocalTime;
import java.time.ZonedDateTime;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * One alert rule (12 §5.1, the JSON of 14 §4.13; F-S06, F-S17, F-S25), as validated by {@link
 * RuleCodec}. Stored as {@code alert_rules.spec} (JSONB) with the index columns {@code app_id},
 * {@code env}, {@code name}, {@code template_key} and {@code enabled}.
 *
 * @param name the rule name (operator text, at most 128 characters)
 * @param enabled whether the engine evaluates it
 * @param templateKey the default template it was made from (F-S24), or null
 * @param templateVersion the template version it was made from, or null
 * @param target the application, environment and evaluation targets
 * @param kind the transaction kind of request metrics (D-20); ignored by server rules
 * @param metric the metric ({@code io.argos.server.alert.metric.AlertMetric} wire name, F-S31)
 * @param window the evaluation window
 * @param forDuration how long the condition must hold before FIRING ({@code for})
 * @param evaluationUnit one state for the application or one per instance
 * @param condition the warn and critical thresholds
 * @param recover the recovery threshold (hysteresis), or null: the condition false for {@code for /
 *     2}
 * @param minSamples requests in the window below which a request metric is not evaluated
 * @param noData what a missing value does
 * @param warmupGrace time after an instance start during which it is not evaluated
 * @param activeTime the time slots of evaluation (empty: always), in {@code argos.server.timezone}
 * @param muteSchedules recurring mutes
 * @param channels channels per severity
 * @param renotifyInterval interval of repeated notifications while FIRING, zero: never
 * @param runbookUrl the runbook link, or null
 * @param messageTemplate the message template (allowed variables only, SEC-39), or null: the
 *     default message
 */
public record RuleSpec(
    String name,
    boolean enabled,
    String templateKey,
    Integer templateVersion,
    Target target,
    Kind kind,
    String metric,
    Duration window,
    Duration forDuration,
    EvaluationUnit evaluationUnit,
    Condition condition,
    Recover recover,
    int minSamples,
    NoData noData,
    Duration warmupGrace,
    List<ActiveTime> activeTime,
    List<MuteSchedule> muteSchedules,
    Channels channels,
    Duration renotifyInterval,
    String runbookUrl,
    String messageTemplate) {

  /** Validates the references and copies the lists. */
  public RuleSpec {
    Objects.requireNonNull(name, "name");
    Objects.requireNonNull(target, "target");
    Objects.requireNonNull(kind, "kind");
    Objects.requireNonNull(metric, "metric");
    Objects.requireNonNull(window, "window");
    Objects.requireNonNull(forDuration, "forDuration");
    Objects.requireNonNull(evaluationUnit, "evaluationUnit");
    Objects.requireNonNull(condition, "condition");
    Objects.requireNonNull(noData, "noData");
    Objects.requireNonNull(warmupGrace, "warmupGrace");
    Objects.requireNonNull(channels, "channels");
    Objects.requireNonNull(renotifyInterval, "renotifyInterval");
    activeTime = List.copyOf(activeTime);
    muteSchedules = List.copyOf(muteSchedules);
  }

  /** The same rule with {@code enabled} changed. */
  public RuleSpec withEnabled(boolean value) {
    return new RuleSpec(
        name,
        value,
        templateKey,
        templateVersion,
        target,
        kind,
        metric,
        window,
        forDuration,
        evaluationUnit,
        condition,
        recover,
        minSamples,
        noData,
        warmupGrace,
        activeTime,
        muteSchedules,
        channels,
        renotifyInterval,
        runbookUrl,
        messageTemplate);
  }

  /** The same rule for another application and environment (rule clone, 14 §4.13). */
  public RuleSpec withTarget(Target value) {
    return new RuleSpec(
        name,
        enabled,
        templateKey,
        templateVersion,
        value,
        kind,
        metric,
        window,
        forDuration,
        evaluationUnit,
        condition,
        recover,
        minSamples,
        noData,
        warmupGrace,
        activeTime,
        muteSchedules,
        channels,
        renotifyInterval,
        runbookUrl,
        messageTemplate);
  }

  /** Lower-case wire name of an enum constant. */
  static String wire(Enum<?> e) {
    return e.name().toLowerCase(Locale.ROOT);
  }

  /** Transaction kind of request metrics (D-20). */
  public enum Kind {
    /** HTTP transactions. */
    HTTP,
    /** Batch transactions. */
    BATCH
  }

  /** What a rule watches (12 §5.1 {@code target.type}). */
  public enum TargetType {
    /** The application. */
    APP,
    /** Instances of the application. */
    INSTANCE,
    /** Services (URL patterns, batch jobs) of the application. */
    SERVICE,
    /** Outgoing call hosts of the application. */
    EXT_HOST,
    /** The server itself (self rules, no application). */
    SERVER;

    /** The wire name ({@code app}, {@code instance}, ...). */
    public String wire() {
      return RuleSpec.wire(this);
    }
  }

  /** One state for the application, or one per instance (F-S25 a). */
  public enum EvaluationUnit {
    /** One state for the target. */
    APP,
    /** One state per instance. */
    INSTANCE;

    /** The wire name. */
    public String wire() {
      return RuleSpec.wire(this);
    }
  }

  /** What a missing value does (12 §5.4 NO_DATA). */
  public enum NoData {
    /** Nothing: the state stays (default; low traffic, F-S25). */
    IGNORE,
    /** NO_DATA with warn severity. */
    WARN,
    /** NO_DATA with critical severity. */
    CRITICAL;

    /** The wire name. */
    public String wire() {
      return RuleSpec.wire(this);
    }
  }

  /** Comparison operator of a condition. */
  public enum Operator {
    /** Greater than. */
    GT(">"),
    /** Greater than or equal. */
    GTE(">="),
    /** Less than. */
    LT("<"),
    /** Less than or equal. */
    LTE("<="),
    /** Equal. */
    EQ("=="),
    /** Not equal. */
    NE("!=");

    private final String symbol;

    Operator(String symbol) {
      this.symbol = symbol;
    }

    /** The JSON symbol. */
    public String symbol() {
      return symbol;
    }

    /** The operator of a JSON symbol, or null. */
    public static Operator of(String symbol) {
      for (Operator o : values()) {
        if (o.symbol.equals(symbol)) {
          return o;
        }
      }
      return null;
    }

    /** Whether {@code value} meets the condition against {@code threshold}. */
    public boolean test(double value, double threshold) {
      return switch (this) {
        case GT -> value > threshold;
        case GTE -> value >= threshold;
        case LT -> value < threshold;
        case LTE -> value <= threshold;
        case EQ -> value == threshold;
        case NE -> value != threshold;
      };
    }

    /**
     * Whether {@code value} is on the good side of a recovery threshold: at or below it for {@code
     * >}/{@code >=}, at or above it for {@code <}/{@code <=} (01 Appendix A "2% 이하", 12 §5.4).
     */
    public boolean recovered(double value, double threshold) {
      return switch (this) {
        case GT, GTE -> value <= threshold;
        case LT, LTE -> value >= threshold;
        case EQ -> value != threshold;
        case NE -> value == threshold;
      };
    }
  }

  /**
   * The targets of a rule.
   *
   * @param appId the application ({@code apps.id}), null for a server rule
   * @param env the environment, or null: every environment of the application
   * @param type what is watched
   * @param ids the watched instance, service or host ids (empty: all of them); decimal strings
   *     because service and host keys are 64-bit
   */
  public record Target(Integer appId, String env, TargetType type, List<String> ids) {

    /** Validates and copies. */
    public Target {
      Objects.requireNonNull(type, "type");
      ids = List.copyOf(ids);
    }
  }

  /**
   * The thresholds; at least one of them is set.
   *
   * @param operator the comparison
   * @param warn the warn threshold, or null
   * @param critical the critical threshold, or null
   */
  public record Condition(Operator operator, Double warn, Double critical) {

    /** Validates. */
    public Condition {
      Objects.requireNonNull(operator, "operator");
      if (warn == null && critical == null) {
        throw new IllegalArgumentException("warn or critical is required");
      }
    }
  }

  /**
   * The recovery threshold (hysteresis, F-S17).
   *
   * @param threshold the threshold on the good side, or null: the condition false
   * @param forDuration how long it must hold ({@code null}: {@code for / 2})
   */
  public record Recover(Double threshold, Duration forDuration) {}

  /**
   * One time slot of evaluation (F-S25 b).
   *
   * @param days the week days
   * @param from start of the slot
   * @param to end of the slot, null for 24:00
   */
  public record ActiveTime(Set<DayOfWeek> days, LocalTime from, LocalTime to) {

    /** Validates and copies. */
    public ActiveTime {
      days = days.isEmpty() ? EnumSet.noneOf(DayOfWeek.class) : EnumSet.copyOf(days);
      Objects.requireNonNull(from, "from");
    }

    /** Whether {@code t} is inside the slot. */
    public boolean contains(ZonedDateTime t) {
      if (!days.contains(t.getDayOfWeek())) {
        return false;
      }
      LocalTime time = t.toLocalTime();
      return !time.isBefore(from) && (to == null || time.isBefore(to));
    }
  }

  /**
   * A recurring mute (F-S25 b).
   *
   * @param cron the start, 5-field cron ({@code minute hour day month weekday}) in {@code
   *     argos.server.timezone}
   * @param duration how long each mute lasts
   * @param reason operator text, or null
   */
  public record MuteSchedule(String cron, Duration duration, String reason) {

    /** Validates. */
    public MuteSchedule {
      Objects.requireNonNull(cron, "cron");
      Objects.requireNonNull(duration, "duration");
    }
  }

  /**
   * Channels per severity (F-S25 a).
   *
   * @param warn channels of warn notifications
   * @param critical channels of critical notifications
   */
  public record Channels(List<UUID> warn, List<UUID> critical) {

    /** No channel. */
    public static final Channels NONE = new Channels(List.of(), List.of());

    /** Copies. */
    public Channels {
      warn = List.copyOf(warn);
      critical = List.copyOf(critical);
    }

    /** Every channel of the rule, without duplicates. */
    public List<UUID> all() {
      LinkedHashSet<UUID> out = new LinkedHashSet<>(warn);
      out.addAll(critical);
      return List.copyOf(out);
    }
  }
}
