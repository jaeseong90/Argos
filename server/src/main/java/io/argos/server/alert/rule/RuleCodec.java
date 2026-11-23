/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.alert.rule;

import io.argos.server.alert.metric.AlertMetric;
import io.argos.server.alert.rule.RuleSpec.ActiveTime;
import io.argos.server.alert.rule.RuleSpec.Channels;
import io.argos.server.alert.rule.RuleSpec.Condition;
import io.argos.server.alert.rule.RuleSpec.EvaluationUnit;
import io.argos.server.alert.rule.RuleSpec.Kind;
import io.argos.server.alert.rule.RuleSpec.MuteSchedule;
import io.argos.server.alert.rule.RuleSpec.NoData;
import io.argos.server.alert.rule.RuleSpec.Operator;
import io.argos.server.alert.rule.RuleSpec.Recover;
import io.argos.server.alert.rule.RuleSpec.Target;
import io.argos.server.alert.rule.RuleSpec.TargetType;
import io.argos.server.alert.template.TemplateRenderer;
import io.argos.server.api.error.ApiException;
import io.argos.server.api.error.ErrorCode;
import java.net.URI;
import java.net.URISyntaxException;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.LocalTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.scheduling.support.CronExpression;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * The rule JSON of 14 §4.13 (published as the JSON Schema {@code alert/alert-rule.schema.json}):
 * strict parsing with validation ({@link #parse}) and the canonical form ({@link #toJson}), which
 * is also the stored {@code alert_rules.spec}.
 *
 * <p>Validation errors answer {@code 400 invalid_request} with one detail per field ({@code field}
 * in the JSON path spelling, {@code reason} snake_case); a message template with a reference
 * outside the allowed variables answers {@code 422 template_variable_forbidden} (SEC-39). The
 * response fields {@code rule_id}, {@code version}, {@code created_at}, {@code updated_at}, {@code
 * created_by}, {@code history} and {@code template_diff} are accepted and ignored on input, so a
 * client can send back what it read.
 */
public final class RuleCodec {

  /** Characters of a rule name at most ({@code alert_rules.name VARCHAR(128)}). */
  public static final int MAX_NAME_CHARS = 128;

  /** Ids of a target at most. */
  public static final int MAX_TARGET_IDS = 100;

  /** Channels per severity at most. */
  public static final int MAX_CHANNELS = 10;

  /** Active time slots and mute schedules at most. */
  public static final int MAX_SLOTS = 14;

  /** Characters of a runbook URL at most. */
  public static final int MAX_URL_CHARS = 1_024;

  /** Characters of a mute schedule reason at most. */
  public static final int MAX_REASON_CHARS = 256;

  /** The shortest window (the metric grid is 5 s). */
  public static final Duration MIN_WINDOW = Duration.ofSeconds(5);

  /** The longest window (minute rollups, 14 §3). */
  public static final Duration MAX_WINDOW = Duration.ofHours(24);

  /** The longest {@code for}, recovery {@code for} and warm-up grace. */
  public static final Duration MAX_FOR = Duration.ofHours(24);

  /** The shortest renotify interval (zero turns it off). */
  public static final Duration MIN_RENOTIFY = Duration.ofMinutes(5);

  /** The default window. */
  public static final Duration DEFAULT_WINDOW = Duration.ofMinutes(1);

  /** The default warm-up grace (01 Appendix A: every rule has 3 minutes). */
  public static final Duration DEFAULT_WARMUP = Duration.ofMinutes(3);

  /** The default renotify interval (12 §5.1 example). */
  public static final Duration DEFAULT_RENOTIFY = Duration.ofMinutes(30);

  /** The default minimum samples of a request metric (01 Appendix A: 20 per minute). */
  public static final int DEFAULT_MIN_SAMPLES = 20;

  /** Top-level fields of the rule JSON. */
  public static final Set<String> FIELDS =
      Set.of(
          "name",
          "enabled",
          "template_key",
          "template_version",
          "target",
          "kind",
          "metric",
          "window",
          "for",
          "evaluation_unit",
          "condition",
          "recover",
          "min_samples",
          "no_data",
          "warmup_grace",
          "active_time",
          "mute_schedules",
          "channels",
          "renotify_interval",
          "runbook_url",
          "message_template");

  /** Response fields accepted and ignored on input. */
  public static final Set<String> READ_ONLY_FIELDS =
      Set.of(
          "rule_id",
          "version",
          "created_at",
          "updated_at",
          "created_by",
          "history",
          "template_diff",
          "deleted_at");

  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final Pattern ENV = Pattern.compile("[A-Za-z0-9._-]{1,32}");
  private static final Pattern TEMPLATE_KEY = Pattern.compile("[a-z0-9_]{1,64}");
  private static final Pattern ID = Pattern.compile("[0-9]{1,20}");
  private static final Pattern HH_MM = Pattern.compile("([01][0-9]|2[0-3]):[0-5][0-9]|24:00");

  private RuleCodec() {}

  /**
   * Parses and validates a rule.
   *
   * @throws ApiException {@code invalid_request} with details, or {@code
   *     template_variable_forbidden}
   */
  public static RuleSpec parse(JsonNode node) {
    Errors e = new Errors();
    if (node == null || !node.isObject()) {
      throw new ApiException(ErrorCode.INVALID_REQUEST, "The rule must be a JSON object")
          .detail("rule", "not_an_object");
    }
    for (String f : node.propertyNames()) {
      if (!FIELDS.contains(f) && !READ_ONLY_FIELDS.contains(f)) {
        e.add(f, "unknown_field");
      }
    }
    String name = text(node, "name", e, true);
    if (name != null && (name.isBlank() || name.length() > MAX_NAME_CHARS)) {
      e.add("name", "length");
    }
    boolean enabled = bool(node, "enabled", true, e);
    String templateKey = text(node, "template_key", e, false);
    if (templateKey != null && !TEMPLATE_KEY.matcher(templateKey).matches()) {
      e.add("template_key", "invalid");
    }
    Integer templateVersion = null;
    JsonNode tv = node.get("template_version");
    if (tv != null && !tv.isNull()) {
      if (!tv.isIntegralNumber() || tv.intValue() < 1) {
        e.add("template_version", "invalid");
      } else {
        templateVersion = tv.intValue();
      }
    }
    AlertMetric metric = null;
    String metricText = text(node, "metric", e, true);
    if (metricText != null) {
      metric = AlertMetric.of(metricText);
      if (metric == null) {
        e.add("metric", "unknown_metric");
      }
    }
    Target target = target(node.get("target"), e);
    Kind kind = enumValue(node, "kind", Kind.class, Kind.HTTP, e);
    Duration window = duration(node, "window", DEFAULT_WINDOW, e);
    if (window != null && (window.compareTo(MIN_WINDOW) < 0 || window.compareTo(MAX_WINDOW) > 0)) {
      e.add("window", "out_of_range");
    }
    Duration forDuration = duration(node, "for", Duration.ZERO, e);
    if (forDuration != null && forDuration.compareTo(MAX_FOR) > 0) {
      e.add("for", "out_of_range");
    }
    EvaluationUnit unit =
        enumValue(node, "evaluation_unit", EvaluationUnit.class, EvaluationUnit.APP, e);
    if (unit == EvaluationUnit.INSTANCE
        && target != null
        && target.type() != TargetType.APP
        && target.type() != TargetType.INSTANCE) {
      e.add("evaluation_unit", "not_for_target");
    }
    Condition condition = condition(node.get("condition"), e);
    Recover recover = recover(node.get("recover"), e);
    int minSamples =
        integer(
            node,
            "min_samples",
            metric != null && metric.request() ? DEFAULT_MIN_SAMPLES : 0,
            0,
            1_000_000,
            e);
    NoData noData = enumValue(node, "no_data", NoData.class, NoData.IGNORE, e);
    Duration warmup = duration(node, "warmup_grace", DEFAULT_WARMUP, e);
    if (warmup != null && warmup.compareTo(MAX_FOR) > 0) {
      e.add("warmup_grace", "out_of_range");
    }
    List<ActiveTime> activeTime = activeTime(node.get("active_time"), e);
    List<MuteSchedule> mutes = muteSchedules(node.get("mute_schedules"), e);
    Channels channels = channels(node.get("channels"), e);
    Duration renotify = duration(node, "renotify_interval", DEFAULT_RENOTIFY, e);
    if (renotify != null && !renotify.isZero() && renotify.compareTo(MIN_RENOTIFY) < 0) {
      e.add("renotify_interval", "out_of_range");
    }
    String runbook = text(node, "runbook_url", e, false);
    if (runbook != null) {
      if (runbook.isBlank()) {
        runbook = null;
      } else if (runbook.length() > MAX_URL_CHARS || !httpUrl(runbook)) {
        e.add("runbook_url", "invalid_url");
      }
    }
    String template = text(node, "message_template", e, false);
    if (template != null && template.length() > TemplateRenderer.MAX_TEMPLATE_CHARS) {
      e.add("message_template", "length");
    }
    if (metric != null && target != null && !metric.targets().contains(target.type())) {
      e.add("target.type", "not_for_metric");
    }
    e.throwIfAny();
    if (template != null && !TemplateRenderer.forbiddenReferences(template).isEmpty()) {
      throw new ApiException(ErrorCode.TEMPLATE_VARIABLE_FORBIDDEN)
          .detail("message_template", "variable_not_allowed");
    }
    return new RuleSpec(
        name.strip(),
        enabled,
        templateKey,
        templateVersion,
        target,
        kind,
        metric.wire(),
        window,
        forDuration,
        unit,
        condition,
        recover,
        minSamples,
        noData,
        warmup,
        activeTime,
        mutes,
        channels,
        renotify,
        runbook,
        template == null || template.isBlank() ? null : template);
  }

  /** Parses a stored {@code spec} (the canonical JSON this class wrote). */
  public static RuleSpec parseStored(String json) {
    return parse(JSON.readTree(json));
  }

  /** The canonical JSON of a rule (the stored {@code spec}, the API's rule body without ids). */
  public static ObjectNode toJson(RuleSpec r) {
    ObjectNode o = JSON.createObjectNode();
    o.put("name", r.name());
    o.put("enabled", r.enabled());
    if (r.templateKey() != null) {
      o.put("template_key", r.templateKey());
    }
    if (r.templateVersion() != null) {
      o.put("template_version", r.templateVersion().intValue());
    }
    ObjectNode t = o.putObject("target");
    if (r.target().appId() == null) {
      t.putNull("app_id");
    } else {
      t.put("app_id", r.target().appId().intValue());
    }
    if (r.target().env() == null) {
      t.putNull("env");
    } else {
      t.put("env", r.target().env());
    }
    t.put("type", r.target().type().wire());
    ArrayNode ids = t.putArray("ids");
    r.target().ids().forEach(ids::add);
    o.put("kind", r.kind().name());
    o.put("metric", r.metric());
    o.put("window", RuleDurations.format(r.window()));
    o.put("for", RuleDurations.format(r.forDuration()));
    o.put("evaluation_unit", r.evaluationUnit().wire());
    ObjectNode c = o.putObject("condition");
    c.put("operator", r.condition().operator().symbol());
    putNumber(c, "warn", r.condition().warn());
    putNumber(c, "critical", r.condition().critical());
    if (r.recover() != null) {
      ObjectNode rc = o.putObject("recover");
      putNumber(rc, "threshold", r.recover().threshold());
      if (r.recover().forDuration() == null) {
        rc.putNull("for");
      } else {
        rc.put("for", RuleDurations.format(r.recover().forDuration()));
      }
    }
    o.put("min_samples", r.minSamples());
    o.put("no_data", r.noData().wire());
    o.put("warmup_grace", RuleDurations.format(r.warmupGrace()));
    ArrayNode at = o.putArray("active_time");
    for (ActiveTime a : r.activeTime()) {
      ObjectNode slot = at.addObject();
      ArrayNode days = slot.putArray("days");
      for (DayOfWeek d : DayOfWeek.values()) {
        if (a.days().contains(d)) {
          days.add(dayName(d));
        }
      }
      slot.put("from", a.from().toString());
      slot.put("to", a.to() == null ? "24:00" : a.to().toString());
    }
    ArrayNode ms = o.putArray("mute_schedules");
    for (MuteSchedule m : r.muteSchedules()) {
      ObjectNode s = ms.addObject();
      s.put("cron", m.cron());
      s.put("duration", RuleDurations.format(m.duration()));
      if (m.reason() == null) {
        s.putNull("reason");
      } else {
        s.put("reason", m.reason());
      }
    }
    ObjectNode ch = o.putObject("channels");
    ArrayNode warn = ch.putArray("warn");
    r.channels().warn().forEach(id -> warn.add(id.toString()));
    ArrayNode crit = ch.putArray("critical");
    r.channels().critical().forEach(id -> crit.add(id.toString()));
    o.put("renotify_interval", RuleDurations.format(r.renotifyInterval()));
    if (r.runbookUrl() == null) {
      o.putNull("runbook_url");
    } else {
      o.put("runbook_url", r.runbookUrl());
    }
    if (r.messageTemplate() == null) {
      o.putNull("message_template");
    } else {
      o.put("message_template", r.messageTemplate());
    }
    return o;
  }

  /** The compact JSON text of {@link #toJson}. */
  public static String toJsonText(RuleSpec r) {
    return JSON.writeValueAsString(toJson(r));
  }

  /**
   * The next fire times of a 5-field cron ({@code minute hour day month weekday}) as Spring's
   * 6-field expression, or null when it is not one.
   */
  public static CronExpression cron(String fiveFields) {
    if (fiveFields == null) {
      return null;
    }
    String[] parts = fiveFields.strip().split("\\s+", -1);
    if (parts.length != 5) {
      return null;
    }
    try {
      return CronExpression.parse("0 " + String.join(" ", parts));
    } catch (IllegalArgumentException ex) {
      return null;
    }
  }

  // --- parts ------------------------------------------------------------------------------------

  private static Target target(JsonNode t, Errors e) {
    if (t == null || !t.isObject()) {
      e.add("target", "required");
      return null;
    }
    for (String f : t.propertyNames()) {
      if (!Set.of("app_id", "env", "type", "ids").contains(f)) {
        e.add("target." + f, "unknown_field");
      }
    }
    TargetType type = enumValue(t, "type", TargetType.class, null, e, "target.type");
    if (type == null) {
      if (!e.has("target.type")) {
        e.add("target.type", "required");
      }
      return null;
    }
    Integer appId = null;
    JsonNode a = t.get("app_id");
    if (a != null && !a.isNull()) {
      if (!a.isIntegralNumber() || a.longValue() < 1 || a.longValue() > Integer.MAX_VALUE) {
        e.add("target.app_id", "invalid");
      } else {
        appId = a.intValue();
      }
    }
    if (type == TargetType.SERVER && appId != null) {
      e.add("target.app_id", "not_for_server");
    }
    if (type != TargetType.SERVER && appId == null && !e.has("target.app_id")) {
      e.add("target.app_id", "required");
    }
    String env = null;
    JsonNode en = t.get("env");
    if (en != null && !en.isNull()) {
      if (!en.isString() || !ENV.matcher(en.asString()).matches()) {
        e.add("target.env", "invalid");
      } else {
        env = en.asString();
      }
    }
    List<String> ids = new ArrayList<>();
    JsonNode idsNode = t.get("ids");
    if (idsNode != null && !idsNode.isNull()) {
      if (!idsNode.isArray() || idsNode.size() > MAX_TARGET_IDS) {
        e.add("target.ids", "invalid");
      } else {
        for (JsonNode id : idsNode.values()) {
          String v = id.isIntegralNumber() ? id.asString() : id.isString() ? id.asString() : null;
          if (v == null || !ID.matcher(v).matches()) {
            e.add("target.ids", "invalid");
            break;
          }
          if (!ids.contains(v)) {
            ids.add(v);
          }
        }
      }
    }
    if (!ids.isEmpty() && (type == TargetType.APP || type == TargetType.SERVER)) {
      e.add("target.ids", "not_for_target");
    }
    return new Target(appId, env, type, ids);
  }

  private static Condition condition(JsonNode c, Errors e) {
    if (c == null || !c.isObject()) {
      e.add("condition", "required");
      return null;
    }
    for (String f : c.propertyNames()) {
      if (!Set.of("operator", "warn", "critical").contains(f)) {
        e.add("condition." + f, "unknown_field");
      }
    }
    Operator op = null;
    JsonNode o = c.get("operator");
    if (o == null || !o.isString() || (op = Operator.of(o.asString())) == null) {
      e.add("condition.operator", "invalid");
    }
    Double warn = number(c, "warn", "condition.warn", e);
    Double critical = number(c, "critical", "condition.critical", e);
    if (warn == null
        && critical == null
        && !e.has("condition.warn")
        && !e.has("condition.critical")) {
      e.add("condition", "threshold_required");
    }
    if (op == null || (warn == null && critical == null)) {
      return null;
    }
    if (warn != null && critical != null) {
      boolean ordered =
          switch (op) {
            case GT, GTE -> warn <= critical;
            case LT, LTE -> warn >= critical;
            case EQ, NE -> true;
          };
      if (!ordered) {
        e.add("condition.critical", "not_beyond_warn");
      }
    }
    return new Condition(op, warn, critical);
  }

  private static Recover recover(JsonNode r, Errors e) {
    if (r == null || r.isNull()) {
      return null;
    }
    if (!r.isObject()) {
      e.add("recover", "invalid");
      return null;
    }
    for (String f : r.propertyNames()) {
      if (!Set.of("threshold", "for").contains(f)) {
        e.add("recover." + f, "unknown_field");
      }
    }
    Double threshold = number(r, "threshold", "recover.threshold", e);
    Duration forDuration = null;
    JsonNode f = r.get("for");
    if (f != null && !f.isNull()) {
      forDuration = f.isString() ? RuleDurations.parse(f.asString()) : null;
      if (forDuration == null || forDuration.compareTo(MAX_FOR) > 0) {
        e.add("recover.for", "invalid_duration");
        forDuration = null;
      }
    }
    return threshold == null && forDuration == null ? null : new Recover(threshold, forDuration);
  }

  private static List<ActiveTime> activeTime(JsonNode a, Errors e) {
    List<ActiveTime> out = new ArrayList<>();
    if (a == null || a.isNull()) {
      return out;
    }
    if (!a.isArray() || a.size() > MAX_SLOTS) {
      e.add("active_time", "invalid");
      return out;
    }
    int i = 0;
    for (JsonNode slot : a.values()) {
      String p = "active_time[" + i++ + "]";
      if (!slot.isObject()) {
        e.add(p, "invalid");
        continue;
      }
      Set<DayOfWeek> days = EnumSet.noneOf(DayOfWeek.class);
      JsonNode d = slot.get("days");
      if (d == null || !d.isArray() || d.isEmpty()) {
        e.add(p + ".days", "required");
      } else {
        for (JsonNode day : d.values()) {
          DayOfWeek w = day.isString() ? day(day.asString()) : null;
          if (w == null) {
            e.add(p + ".days", "invalid");
            break;
          }
          days.add(w);
        }
      }
      LocalTime from = hhmm(slot.get("from"), p + ".from", e, false);
      LocalTime to = hhmm(slot.get("to"), p + ".to", e, true);
      if (from != null && to != null && !to.isAfter(from)) {
        e.add(p + ".to", "not_after_from");
      }
      if (!days.isEmpty() && from != null && !e.has(p + ".to")) {
        out.add(new ActiveTime(days, from, to));
      }
    }
    return out;
  }

  private static List<MuteSchedule> muteSchedules(JsonNode a, Errors e) {
    List<MuteSchedule> out = new ArrayList<>();
    if (a == null || a.isNull()) {
      return out;
    }
    if (!a.isArray() || a.size() > MAX_SLOTS) {
      e.add("mute_schedules", "invalid");
      return out;
    }
    int i = 0;
    for (JsonNode m : a.values()) {
      String p = "mute_schedules[" + i++ + "]";
      if (!m.isObject()) {
        e.add(p, "invalid");
        continue;
      }
      JsonNode c = m.get("cron");
      String cron = c != null && c.isString() ? c.asString().strip() : null;
      if (cron == null || cron(cron) == null) {
        e.add(p + ".cron", "invalid_cron");
      }
      JsonNode dn = m.get("duration");
      Duration duration = dn != null && dn.isString() ? RuleDurations.parse(dn.asString()) : null;
      if (duration == null || duration.isZero() || duration.compareTo(Duration.ofDays(7)) > 0) {
        e.add(p + ".duration", "invalid_duration");
      }
      JsonNode rn = m.get("reason");
      String reason = rn != null && rn.isString() ? rn.asString() : null;
      if (reason != null && reason.length() > MAX_REASON_CHARS) {
        e.add(p + ".reason", "length");
      }
      if (cron != null && cron(cron) != null && duration != null) {
        out.add(new MuteSchedule(cron, duration, reason));
      }
    }
    return out;
  }

  private static Channels channels(JsonNode c, Errors e) {
    if (c == null || c.isNull()) {
      return Channels.NONE;
    }
    if (!c.isObject()) {
      e.add("channels", "invalid");
      return Channels.NONE;
    }
    for (String f : c.propertyNames()) {
      if (!Set.of("warn", "critical").contains(f)) {
        e.add("channels." + f, "unknown_field");
      }
    }
    return new Channels(
        uuids(c.get("warn"), "channels.warn", e), uuids(c.get("critical"), "channels.critical", e));
  }

  private static List<UUID> uuids(JsonNode a, String field, Errors e) {
    List<UUID> out = new ArrayList<>();
    if (a == null || a.isNull()) {
      return out;
    }
    if (!a.isArray() || a.size() > MAX_CHANNELS) {
      e.add(field, "invalid");
      return out;
    }
    for (JsonNode id : a.values()) {
      try {
        UUID u = UUID.fromString(id.isString() ? id.asString() : "");
        if (!out.contains(u)) {
          out.add(u);
        }
      } catch (IllegalArgumentException ex) {
        e.add(field, "invalid_channel_id");
        break;
      }
    }
    return out;
  }

  // --- scalars ----------------------------------------------------------------------------------

  private static String text(JsonNode n, String field, Errors e, boolean required) {
    JsonNode v = n.get(field);
    if (v == null || v.isNull()) {
      if (required) {
        e.add(field, "required");
      }
      return null;
    }
    if (!v.isString()) {
      e.add(field, "not_a_string");
      return null;
    }
    String s = v.asString();
    for (int i = 0; i < s.length(); i++) {
      char ch = s.charAt(i);
      if (ch < 0x20 && ch != '\n' && ch != '\t') {
        e.add(field, "control_character");
        return null;
      }
    }
    return s;
  }

  private static boolean bool(JsonNode n, String field, boolean dflt, Errors e) {
    JsonNode v = n.get(field);
    if (v == null || v.isNull()) {
      return dflt;
    }
    if (!v.isBoolean()) {
      e.add(field, "not_a_boolean");
      return dflt;
    }
    return v.booleanValue();
  }

  private static int integer(JsonNode n, String field, int dflt, int min, int max, Errors e) {
    JsonNode v = n.get(field);
    if (v == null || v.isNull()) {
      return dflt;
    }
    if (!v.isIntegralNumber() || v.longValue() < min || v.longValue() > max) {
      e.add(field, "out_of_range");
      return dflt;
    }
    return v.intValue();
  }

  private static Double number(JsonNode n, String field, String path, Errors e) {
    JsonNode v = n.get(field);
    if (v == null || v.isNull()) {
      return null;
    }
    if (!v.isNumber() || !Double.isFinite(v.doubleValue())) {
      e.add(path, "not_a_number");
      return null;
    }
    return v.doubleValue();
  }

  private static Duration duration(JsonNode n, String field, Duration dflt, Errors e) {
    JsonNode v = n.get(field);
    if (v == null || v.isNull()) {
      return dflt;
    }
    Duration d = v.isString() ? RuleDurations.parse(v.asString()) : null;
    if (d == null) {
      e.add(field, "invalid_duration");
      return dflt;
    }
    return d;
  }

  private static <E extends Enum<E>> E enumValue(
      JsonNode n, String field, Class<E> type, E dflt, Errors e) {
    return enumValue(n, field, type, dflt, e, field);
  }

  private static <E extends Enum<E>> E enumValue(
      JsonNode n, String field, Class<E> type, E dflt, Errors e, String path) {
    JsonNode v = n.get(field);
    if (v == null || v.isNull()) {
      return dflt;
    }
    if (v.isString()) {
      String s = v.asString();
      for (E c : type.getEnumConstants()) {
        if (c.name().equalsIgnoreCase(s)) {
          return c;
        }
      }
    }
    e.add(path, "invalid");
    return dflt;
  }

  private static LocalTime hhmm(JsonNode v, String path, Errors e, boolean allow24) {
    if (v == null || !v.isString() || !HH_MM.matcher(v.asString()).matches()) {
      e.add(path, "invalid_time");
      return null;
    }
    if (v.asString().equals("24:00")) {
      if (!allow24) {
        e.add(path, "invalid_time");
      }
      return null;
    }
    try {
      return LocalTime.parse(v.asString());
    } catch (DateTimeParseException ex) {
      e.add(path, "invalid_time");
      return null;
    }
  }

  private static DayOfWeek day(String s) {
    String t = s.strip().toUpperCase(Locale.ROOT);
    for (DayOfWeek d : DayOfWeek.values()) {
      if (d.name().startsWith(t) && t.length() >= 3) {
        return d;
      }
    }
    return null;
  }

  private static String dayName(DayOfWeek d) {
    String n = d.name();
    return n.charAt(0) + n.substring(1, 3).toLowerCase(Locale.ROOT);
  }

  private static boolean httpUrl(String s) {
    try {
      URI u = new URI(s);
      String scheme = u.getScheme();
      return u.getHost() != null
          && scheme != null
          && (scheme.equalsIgnoreCase("https") || scheme.equalsIgnoreCase("http"));
    } catch (URISyntaxException ex) {
      return false;
    }
  }

  private static void putNumber(ObjectNode o, String field, Double v) {
    if (v == null) {
      o.putNull(field);
    } else {
      o.put(field, v.doubleValue());
    }
  }

  /** Collected validation errors. */
  private static final class Errors {
    private final Map<String, String> byField = new LinkedHashMap<>();

    void add(String field, String reason) {
      byField.putIfAbsent(field, reason);
    }

    boolean has(String field) {
      return byField.containsKey(field);
    }

    void throwIfAny() {
      if (byField.isEmpty()) {
        return;
      }
      ApiException ex = new ApiException(ErrorCode.INVALID_REQUEST, "The alert rule is invalid");
      byField.forEach(ex::detail);
      throw ex;
    }
  }
}
