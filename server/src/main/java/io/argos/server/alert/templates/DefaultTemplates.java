/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.alert.templates;

import io.argos.server.alert.rule.RuleCodec;
import io.argos.server.alert.rule.RuleSpec;
import io.argos.server.alert.rule.RuleSpec.Channels;
import io.argos.server.alert.rule.RuleSpec.Condition;
import io.argos.server.alert.rule.RuleSpec.EvaluationUnit;
import io.argos.server.alert.rule.RuleSpec.Kind;
import io.argos.server.alert.rule.RuleSpec.NoData;
import io.argos.server.alert.rule.RuleSpec.Operator;
import io.argos.server.alert.rule.RuleSpec.Recover;
import io.argos.server.alert.rule.RuleSpec.Target;
import io.argos.server.alert.rule.RuleSpec.TargetType;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * The 14 default alert rule templates of 01 Appendix A (F-S24, 12 §5.6) as code constants. A
 * template has a key, a version, a scope and one or more parts; a part is one rule (one metric). A
 * template whose row of Appendix A names two metrics has two parts (connection pool waiting and
 * usage, agent and server drops, certificate and token expiry), told apart by the metric. Rules
 * made from a template record {@code template_key} and {@code template_version}; when the version
 * here rises, the rule shows its differences ({@link TemplateDiff}) and is never overwritten.
 *
 * <p>Thresholds are the starting points of Appendix A ("임계치는 출발점이며 7일 미리보기로 조정"); every rule has
 * the 3-minute warm-up grace and a recovery threshold. Where Appendix A gives two durations for two
 * severities (heap 10 m / 5 m, pool 1 m / 2 m) the rule model has one {@code for}: the heap rule
 * uses the shorter (critical) one and the pool parts use one each (WO-25 assumption, reported).
 */
public final class DefaultTemplates {

  /** Template keys of Appendix A, in its order. */
  public static final String INSTANCE_DOWN = "instance_down";

  /** Error rate. */
  public static final String ERROR_RATE = "error_rate";

  /** Response time p95. */
  public static final String P95_LATENCY = "p95_latency";

  /** Active transactions. */
  public static final String ACTIVE_TRANSACTIONS = "active_transactions";

  /** Connection pool. */
  public static final String CONNECTION_POOL = "connection_pool";

  /** Heap. */
  public static final String HEAP = "heap";

  /** GC pause ratio. */
  public static final String GC_PAUSE = "gc_pause";

  /** Outgoing call timeouts. */
  public static final String EXT_TIMEOUT = "ext_timeout";

  /** Batch delay (created when the application's first BATCH transaction is seen). */
  public static final String BATCH_DELAY = "batch_delay";

  /** Collection drops. */
  public static final String COLLECTION = "collection";

  /** Server disk (server self rule). */
  public static final String SERVER_DISK = "server_disk";

  /** Ingest lag (server self rule). */
  public static final String INGEST_LAG = "ingest_lag";

  /** Channel failure (server self rule, event driven). */
  public static final String CHANNEL_FAILURE = "channel_failure";

  /** Certificate and token expiry (server self rule). */
  public static final String CERT_TOKEN_EXPIRY = "cert_token_expiry";

  /** Where a template part applies. */
  public enum Scope {
    /** Per application. */
    APP,
    /** Once for the server. */
    SERVER
  }

  /**
   * One part of a template: one rule.
   *
   * @param scope application or server
   * @param spec the rule with an empty target application (filled when applied)
   */
  public record Part(Scope scope, RuleSpec spec) {}

  /**
   * One template.
   *
   * @param key the template key
   * @param name the English name (the UI translates by key)
   * @param version the template version
   * @param appliesWhen when it is created: {@code always}, {@code batch} (first BATCH transaction)
   *     or {@code server} (first server start)
   * @param parts its rules
   */
  public record Template(
      String key, String name, int version, String appliesWhen, List<Part> parts) {

    /** Copies. */
    public Template {
      parts = List.copyOf(parts);
    }

    /** The part of {@code metric}, if any. */
    public Optional<Part> part(String metric) {
      return parts.stream().filter(p -> p.spec().metric().equals(metric)).findFirst();
    }
  }

  private static final DefaultTemplates STANDARD = new DefaultTemplates(standardTemplates());

  private final List<Template> templates;

  /** Templates of a given list (tests: a bumped version). */
  public DefaultTemplates(List<Template> templates) {
    this.templates = List.copyOf(templates);
  }

  /** The templates of Appendix A. */
  public static DefaultTemplates standard() {
    return STANDARD;
  }

  /** Every template, in Appendix A order. */
  public List<Template> all() {
    return templates;
  }

  /** The template {@code key}. */
  public Optional<Template> get(String key) {
    return templates.stream().filter(t -> t.key().equals(key)).findFirst();
  }

  /**
   * The rule of a part for an application (or the server), validated by the rule codec (the
   * template constants must be valid rules).
   */
  public static RuleSpec instantiate(Template t, Part p, Integer appId, String env) {
    RuleSpec s = p.spec();
    Target target =
        new Target(
            p.scope() == Scope.SERVER ? null : Objects.requireNonNull(appId, "appId"),
            p.scope() == Scope.SERVER ? null : env,
            s.target().type(),
            s.target().ids());
    RuleSpec r = s.withTarget(target);
    RuleSpec withTemplate =
        new RuleSpec(
            r.name(),
            r.enabled(),
            t.key(),
            t.version(),
            r.target(),
            r.kind(),
            r.metric(),
            r.window(),
            r.forDuration(),
            r.evaluationUnit(),
            r.condition(),
            r.recover(),
            r.minSamples(),
            r.noData(),
            r.warmupGrace(),
            r.activeTime(),
            r.muteSchedules(),
            r.channels(),
            r.renotifyInterval(),
            r.runbookUrl(),
            r.messageTemplate());
    return RuleCodec.parse(RuleCodec.toJson(withTemplate));
  }

  // --- Appendix A
  // ---------------------------------------------------------------------------------

  private static List<Template> standardTemplates() {
    Duration m1 = Duration.ofMinutes(1);
    Duration s5 = Duration.ofSeconds(5);
    return List.of(
        new Template(
            INSTANCE_DOWN,
            "Instance down",
            1,
            "always",
            List.of(
                app(
                    "Instance down",
                    "instance_state",
                    TargetType.APP,
                    EvaluationUnit.INSTANCE,
                    m1,
                    Duration.ZERO,
                    new Condition(Operator.GTE, null, 1.0),
                    null,
                    0))),
        new Template(
            ERROR_RATE,
            "Error rate",
            1,
            "always",
            List.of(
                app(
                    "Error rate",
                    "error_rate",
                    TargetType.APP,
                    EvaluationUnit.APP,
                    m1,
                    Duration.ofMinutes(5),
                    new Condition(Operator.GTE, 0.05, 0.20),
                    new Recover(0.02, Duration.ofMinutes(2)),
                    20))),
        new Template(
            P95_LATENCY,
            "Response time p95",
            1,
            "always",
            List.of(
                app(
                    "Response time p95",
                    "response_time_p95",
                    TargetType.APP,
                    EvaluationUnit.APP,
                    m1,
                    Duration.ofMinutes(5),
                    new Condition(Operator.GTE, 3000.0, 10000.0),
                    new Recover(2000.0, Duration.ofMinutes(2)),
                    20))),
        new Template(
            ACTIVE_TRANSACTIONS,
            "Active transactions",
            1,
            "always",
            List.of(
                app(
                    "Active transactions",
                    "active_ratio_to_max_threads",
                    TargetType.APP,
                    EvaluationUnit.INSTANCE,
                    s5,
                    m1,
                    new Condition(Operator.GTE, null, 0.8),
                    new Recover(0.5, null),
                    0))),
        new Template(
            CONNECTION_POOL,
            "Connection pool",
            1,
            "always",
            List.of(
                app(
                    "Connection pool waiting",
                    "jdbc_pool_waiting",
                    TargetType.APP,
                    EvaluationUnit.INSTANCE,
                    s5,
                    m1,
                    new Condition(Operator.GTE, 1.0, null),
                    new Recover(0.0, null),
                    0),
                app(
                    "Connection pool usage",
                    "jdbc_pool_usage_ratio",
                    TargetType.APP,
                    EvaluationUnit.INSTANCE,
                    s5,
                    Duration.ofMinutes(2),
                    new Condition(Operator.GTE, null, 1.0),
                    null,
                    0))),
        new Template(
            HEAP,
            "Heap",
            1,
            "always",
            List.of(
                app(
                    "Heap usage",
                    "jvm_heap_used_ratio",
                    TargetType.APP,
                    EvaluationUnit.INSTANCE,
                    s5,
                    Duration.ofMinutes(5),
                    new Condition(Operator.GTE, 0.85, 0.95),
                    new Recover(0.75, null),
                    0))),
        new Template(
            GC_PAUSE,
            "GC pause ratio",
            1,
            "always",
            List.of(
                app(
                    "GC pause ratio",
                    "jvm_gc_pause_ratio",
                    TargetType.APP,
                    EvaluationUnit.INSTANCE,
                    m1,
                    Duration.ofMinutes(5),
                    new Condition(Operator.GTE, 0.10, 0.30),
                    new Recover(0.05, null),
                    0))),
        new Template(
            EXT_TIMEOUT,
            "External call timeouts",
            1,
            "always",
            List.of(
                app(
                    "External call timeouts",
                    "ext_call_timeout_ratio",
                    TargetType.EXT_HOST,
                    EvaluationUnit.APP,
                    m1,
                    Duration.ofMinutes(3),
                    new Condition(Operator.GTE, 0.10, 0.50),
                    new Recover(0.02, null),
                    10))),
        new Template(
            BATCH_DELAY,
            "Batch delay",
            1,
            "batch",
            List.of(
                new Part(
                    Scope.APP,
                    spec(
                        "Batch delay",
                        "batch_elapsed_seconds",
                        new Target(null, null, TargetType.SERVICE, List.of()),
                        Kind.BATCH,
                        EvaluationUnit.APP,
                        s5,
                        Duration.ZERO,
                        new Condition(Operator.GT, 1800.0, null),
                        new Recover(1800.0, Duration.ZERO),
                        0)))),
        new Template(
            COLLECTION,
            "Collection",
            1,
            "always",
            List.of(
                app(
                    "Agent drops",
                    "argos_agent_dropped_total",
                    TargetType.APP,
                    EvaluationUnit.APP,
                    m1,
                    Duration.ofMinutes(5),
                    new Condition(Operator.GTE, 1.0, null),
                    new Recover(0.0, null),
                    0),
                server(
                    "Server drops",
                    "argos_server_dropped_total",
                    m1,
                    Duration.ofMinutes(5),
                    new Condition(Operator.GTE, 1.0, null),
                    new Recover(0.0, null)))),
        new Template(
            SERVER_DISK,
            "Server disk",
            1,
            "server",
            List.of(
                server(
                    "Server disk",
                    "argos_server_disk_usage_ratio",
                    m1,
                    Duration.ofMinutes(10),
                    new Condition(Operator.GTE, 0.80, 0.90),
                    new Recover(0.75, null)))),
        new Template(
            INGEST_LAG,
            "Ingest lag",
            1,
            "server",
            List.of(
                server(
                    "Ingest lag",
                    "argos_server_ingest_lag_seconds",
                    m1,
                    Duration.ofMinutes(5),
                    new Condition(Operator.GTE, 30.0, null),
                    new Recover(5.0, null)))),
        new Template(
            CHANNEL_FAILURE,
            "Channel failure",
            1,
            "server",
            List.of(
                server(
                    "Channel failure",
                    "argos_notification_failures_total",
                    m1,
                    Duration.ZERO,
                    new Condition(Operator.GTE, null, 1.0),
                    null))),
        new Template(
            CERT_TOKEN_EXPIRY,
            "Certificate and token expiry",
            1,
            "server",
            List.of(
                server(
                    "Server certificate expiry",
                    "argos_server_tls_cert_expiry_days",
                    Duration.ofHours(1),
                    Duration.ZERO,
                    new Condition(Operator.LTE, 30.0, 7.0),
                    new Recover(31.0, Duration.ZERO)),
                server(
                    "Agent token expiry",
                    "agent_token_expiry_days",
                    Duration.ofHours(1),
                    Duration.ZERO,
                    new Condition(Operator.LTE, 30.0, 7.0),
                    new Recover(31.0, Duration.ZERO)))));
  }

  private static Part app(
      String name,
      String metric,
      TargetType type,
      EvaluationUnit unit,
      Duration window,
      Duration forDuration,
      Condition condition,
      Recover recover,
      int minSamples) {
    return new Part(
        Scope.APP,
        spec(
            name,
            metric,
            new Target(null, null, type, List.of()),
            Kind.HTTP,
            unit,
            window,
            forDuration,
            condition,
            recover,
            minSamples));
  }

  private static Part server(
      String name,
      String metric,
      Duration window,
      Duration forDuration,
      Condition condition,
      Recover recover) {
    return new Part(
        Scope.SERVER,
        spec(
            name,
            metric,
            new Target(null, null, TargetType.SERVER, List.of()),
            Kind.HTTP,
            EvaluationUnit.APP,
            window,
            forDuration,
            condition,
            recover,
            0));
  }

  private static RuleSpec spec(
      String name,
      String metric,
      Target target,
      Kind kind,
      EvaluationUnit unit,
      Duration window,
      Duration forDuration,
      Condition condition,
      Recover recover,
      int minSamples) {
    return new RuleSpec(
        name,
        true,
        null,
        null,
        target,
        kind,
        metric,
        window,
        forDuration,
        unit,
        condition,
        recover,
        minSamples,
        NoData.IGNORE,
        RuleCodec.DEFAULT_WARMUP,
        List.of(),
        List.of(),
        Channels.NONE,
        RuleCodec.DEFAULT_RENOTIFY,
        null,
        null);
  }
}
