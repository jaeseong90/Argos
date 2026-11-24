/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.admin.configchange;

import io.argos.proto.v1.ConfigApplyResult;
import io.argos.proto.v1.ConfigPush;
import io.argos.proto.v1.RejectedKey;
import io.argos.server.admin.AdminPush;
import io.argos.server.api.error.ApiException;
import io.argos.server.api.error.ErrorCode;
import io.argos.server.api.query.CursorCodec;
import io.argos.server.audit.Actor;
import io.argos.server.audit.AuditEvent;
import io.argos.server.audit.AuditResult;
import io.argos.server.audit.AuditService;
import io.argos.server.audit.Target;
import io.argos.server.auth.ArgosPrincipal;
import io.argos.server.auth.Role;
import io.argos.server.session.AgentSession;
import io.argos.server.session.SessionRegistry;
import io.argos.server.session.api.ConfigApplyResultSink;
import io.argos.server.session.api.HelloConfigSource;
import io.argos.server.session.api.SessionEndListener;
import io.argos.server.session.api.SessionMessenger;
import io.argos.server.storage.postgres.app.EventWriteRepository;
import io.argos.server.storage.postgres.configchange.ConfigChangeRepository;
import io.argos.server.storage.postgres.configchange.ConfigChangeRepository.AppConfigValue;
import io.argos.server.storage.postgres.configchange.ConfigChangeRepository.AppRow;
import io.argos.server.storage.postgres.configchange.ConfigChangeRepository.ChangeRow;
import io.argos.server.storage.postgres.configchange.ConfigChangeRepository.InstanceRow;
import io.argos.server.storage.postgres.configchange.ConfigChangeRepository.NewChange;
import io.argos.server.storage.postgres.configchange.ConfigChangeRepository.ResultRow;
import io.micrometer.core.instrument.MeterRegistry;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Configuration change control (12 §6.2, F-S29, DSN-12-09, 16 §5.5, §5.7, 11 §8.1, 14 §4.14;
 * WO-26).
 *
 * <p><b>Propose</b> ({@link #propose}): a reason (required, at most {@value #MAX_REASON_BYTES}
 * UTF-8 bytes), the targets (every instance of the app that is not archived, or at most {@value
 * #MAX_TARGETS} of its instances) and the values ({@code {key: value}}, at most {@value #MAX_KEYS};
 * a JSON {@code null} removes the server-held value). Every key must be in the server-side schema
 * ({@link AgentKeySchema}: unknown, immutable or an invalid value is {@code 400}); {@code
 * capture.mask-patterns} and {@code commands.env-dump.mask-patterns} may only grow against the
 * server-held list ({@code 400 reduce_masking}); the kill switch key goes through {@link
 * KillSwitchService} only. Against the targets' {@code remote_config_allow} lists (the live
 * session's, else the one the instance's last Hello stored) a key no target allows is {@code 400}
 * naming the instances; a key only some targets allow is listed per instance in the preview's
 * {@code rejected_keys_by_instance} and never pushed to those instances (WO-26 assumption on 12
 * §6.2 against 14 §4.14). The diff compares with the server-held values ({@code app_config}). The
 * change is stored {@code previewed}; audit {@code CONFIG_CHANGE_CREATED}.
 *
 * <p><b>Apply</b> ({@link #apply}): only a preview (409 otherwise); the app's other previews become
 * {@code superseded}; {@code app_config} takes the values; every target with a live session gets a
 * {@code ConfigPush} of the keys its allow list names ({@code pending} with {@code pushed_at} until
 * its {@code ConfigApplyResult}), a target without one is {@code pending} and gets the server-held
 * values in its next {@code ServerHello.config} ({@link #configFor}); a target whose allow list
 * names none of the keys is {@code rejected} without a push. Results ({@link #onResult}) are {@code
 * applied}, {@code partial} or {@code rejected} with the agent's and the server's rejected keys; a
 * push without an answer within {@link #RESULT_TIMEOUT} or whose session ends is {@code offline}
 * (its values come with the next Hello). The change is {@code applied} when no push waits for an
 * answer: audit {@code CONFIG_CHANGE_APPLIED}, an {@code admin} WebSocket push per result and at
 * the end. Audit {@code CONFIG_PUSH} (or {@code KILL_SWITCH}), an {@code events} row ({@code
 * config} or {@code kill_switch}).
 *
 * <p><b>Revert</b> ({@link #revert}): a new change ({@code revert_of}) of the previous server-held
 * values of the reverted change's keys, applied at once with {@code ConfigPush.revert = true};
 * audit {@code CONFIG_ROLLBACK}. A key the server did not hold before is removed from {@code
 * app_config} and not pushed: the protocol has no "unset", so the agent keeps the pushed value
 * until its restart or a {@code reload_config} (its local file wins then, 16 §5.5 step 7).
 *
 * <p><b>Reads</b>: {@link #appConfig} (the keys every target allows, each instance's list, the
 * server-held values masked, the drift) and {@link #effective} (per key the server-held value and
 * whether the instance runs it). The server only knows what it pushed and what agents answered:
 * values that come from an agent's local file or environment are never sent to it, so {@code
 * source} is {@code server} for a value the instance runs from the server and null otherwise.
 *
 * <p><b>Threads.</b> Proposals, applies and reads run on the request thread under a per-app lock;
 * results, session ends, Hello deliveries and the result timeout run on the {@code argos-config}
 * worker ({@code worker}).
 */
public class ConfigChangeService
    implements ConfigApplyResultSink, SessionEndListener, HelloConfigSource {

  /** UTF-8 bytes of a reason at most (12 §6.2). */
  public static final int MAX_REASON_BYTES = 1024;

  /** Instances named in one change at most. */
  public static final int MAX_TARGETS = 100;

  /** Keys of one change at most. */
  public static final int MAX_KEYS = 64;

  /** Changes listed at most per page. */
  public static final int MAX_LIST = 100;

  /** A push without a {@code ConfigApplyResult} for this long is {@code offline}. */
  public static final Duration RESULT_TIMEOUT = Duration.ofSeconds(30);

  /** {@code config_changes.kind} of a configuration change. */
  public static final String KIND_CONFIG = "config";

  /** {@code config_changes.kind} of the kill switch. */
  public static final String KIND_KILL_SWITCH = "kill_switch";

  /** {@code error} of a push without an answer in time. */
  static final String NO_RESULT = "no_result";

  /** {@code error} of a push whose session ended before the answer. */
  static final String SESSION_ENDED = "session_ended";

  /** {@code error} of a push the session's transport did not take. */
  static final String NOT_DELIVERED = "not_delivered";

  /** {@code rejected[].by}: the server (the instance's allow list does not name the key). */
  static final String BY_SERVER = "server";

  /** {@code rejected[].by}: the agent's {@code ConfigApplyResult}. */
  static final String BY_AGENT = "agent";

  /** The custom method instrumentation key (docs/agent-config.md, 16 §5.7). */
  static final String CUSTOM_METHODS_KEY = "instrumentation.custom-methods";

  private static final Set<String> MASK_PATTERN_KEYS =
      Set.of("capture.mask-patterns", "commands.env-dump.mask-patterns");
  private static final int LOCKS = 64;
  private static final int AUDIT_INSTANCES = 100;

  /**
   * Who applied a change of the custom method keys and what it set (masked), from the apply until
   * its outcome is audited ({@code CUSTOM_METHOD_CHANGED}, ADR-0010 C-238 (8)); bounded by the
   * changes in progress (one apply at a time per app). After a restart the outcome is audited with
   * the user recorded on the change ({@code applied_by}, else {@code requested_by}; ADR-0010 C-239
   * (3)) and the stored before and after values (masked).
   */
  private final Map<UUID, CustomAudit> customAudits = new ConcurrentHashMap<>();

  private record CustomAudit(
      Actor actor, Target target, Map<String, Object> before, Map<String, Object> after) {}

  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final Logger LOG = LoggerFactory.getLogger(ConfigChangeService.class);

  /** The targets of a change: every instance of the app, or the listed ones. */
  public record Targets(boolean all, List<Integer> instanceIds) {

    public Targets {
      instanceIds = java.util.Collections.unmodifiableList(new ArrayList<>(instanceIds));
    }

    /** Every instance of the app that is not archived. */
    public static Targets everyInstance() {
      return new Targets(true, List.of());
    }
  }

  /** One key of a diff: the server-held value before (null: not held) and after (null: removed). */
  public record DiffEntry(String key, @Nullable String before, @Nullable String after) {}

  /** Keys an instance's allow list does not name (never pushed to it). */
  public record InstanceKeys(int instanceId, List<String> keys) {}

  /** A change with its diff, preview rejections and per-instance results. */
  public record Change(
      ChangeRow row,
      List<DiffEntry> diff,
      List<InstanceKeys> rejectedKeysByInstance,
      List<ResultRow> results) {}

  /** A page of an app's changes; {@code nextCursor} null on the last page. */
  public record Page(List<Change> changes, @Nullable String nextCursor) {}

  /** An instance's allow list as the server knows it. */
  public record InstanceAllow(int instanceId, String name, boolean online, List<String> patterns) {}

  /**
   * A known difference between the server-held values and an instance.
   *
   * @param key the key, null for a configuration hash that differs from the app's majority
   * @param value the server-held value the instance does not run (masked), or the instance's
   *     configuration hash for {@code reason = config_hash}
   * @param reason {@code config_hash}, {@code not_allowed} (its allow list does not name the key),
   *     {@code rejected} (its agent refused it) or {@code pending} (not delivered yet)
   */
  public record Drift(
      int instanceId, @Nullable String key, @Nullable String value, String reason) {}

  /** {@code GET /admin/apps/{appId}/config}. */
  public record AppConfig(
      List<String> remoteAllowedKeys,
      List<InstanceAllow> byInstance,
      Map<String, String> current,
      List<Drift> drift) {}

  /**
   * One key of an instance's effective configuration as the server knows it.
   *
   * @param value the value the instance runs from the server (masked), null when unknown to the
   *     server
   * @param source {@code server} when the instance runs the server-held value, null when the value
   *     comes from the agent's default, file or environment (never sent to the server)
   * @param remoteAllowed whether the instance's allow list names the key
   * @param serverValue the server-held value (masked), null when none
   * @param serverStatus the instance's state of the server-held value: {@code applied}, {@code
   *     pending}, {@code rejected}, {@code not_allowed}; null without a server-held value
   */
  public record Effective(
      String key,
      @Nullable String value,
      @Nullable String source,
      boolean remoteAllowed,
      @Nullable String serverValue,
      @Nullable String serverStatus) {}

  /** A target and its allow list. */
  record TargetState(InstanceRow instance, AllowPatterns allow, boolean online) {}

  private final ConfigChangeRepository repository;
  private final SessionRegistry sessions;
  private final SessionMessenger messenger;
  private final EventWriteRepository events;
  private final AuditService audit;
  private final AdminPush push;
  private final CursorCodec cursors;
  private final Clock clock;
  private final Executor worker;
  private final MeterRegistry meters;
  private final Object[] appLocks = new Object[LOCKS];
  private final Set<UUID> applying = ConcurrentHashMap.newKeySet();
  private volatile boolean resumed;

  public ConfigChangeService(
      ConfigChangeRepository repository,
      SessionRegistry sessions,
      SessionMessenger messenger,
      EventWriteRepository events,
      AuditService audit,
      AdminPush push,
      CursorCodec cursors,
      Clock clock,
      Executor worker,
      MeterRegistry meters) {
    this.repository = Objects.requireNonNull(repository, "repository");
    this.sessions = Objects.requireNonNull(sessions, "sessions");
    this.messenger = Objects.requireNonNull(messenger, "messenger");
    this.events = Objects.requireNonNull(events, "events");
    this.audit = Objects.requireNonNull(audit, "audit");
    this.push = Objects.requireNonNull(push, "push");
    this.cursors = Objects.requireNonNull(cursors, "cursors");
    this.clock = Objects.requireNonNull(clock, "clock");
    this.worker = Objects.requireNonNull(worker, "worker");
    this.meters = Objects.requireNonNull(meters, "meters");
    for (int i = 0; i < LOCKS; i++) {
      appLocks[i] = new Object();
    }
  }

  // --- Propose ---------------------------------------------------------------------------------

  /**
   * Proposes a configuration change (14 §4.14 {@code POST /admin/apps/{appId}/config/changes}).
   *
   * @param changes {@code {key: value}}: a string, number, boolean or list of strings, or null to
   *     remove the server-held value
   * @param targets null or {@code all} for every instance
   * @throws ApiException 400, 403, 404 as the class comment says
   */
  public Change propose(
      ArgosPrincipal principal,
      Actor actor,
      int appId,
      @Nullable String reason,
      @Nullable Map<String, ?> changes,
      @Nullable Targets targets) {
    requireAdmin(principal);
    Map<String, @Nullable String> values = values(changes);
    if (values.containsKey(AgentKeySchema.KILL_SWITCH_KEY)) {
      throw new ApiException(
              ErrorCode.INVALID_REQUEST, "The kill switch is changed through its own endpoint")
          .detail("changes.enabled", "use_kill_switch");
    }
    return proposeKind(principal, actor, appId, reason, values, targets, KIND_CONFIG, null);
  }

  /**
   * Proposes a change of {@code kind} with checked raw values (canonical keys, string values or
   * null).
   */
  Change proposeKind(
      ArgosPrincipal principal,
      Actor actor,
      int appId,
      @Nullable String reason,
      Map<String, @Nullable String> raw,
      @Nullable Targets targets,
      String kind,
      @Nullable UUID revertOf) {
    requireAdmin(principal);
    AppRow app = repository.app(appId).orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
    String why = checkReason(reason);
    Targets t = targets == null ? Targets.everyInstance() : targets;
    synchronized (lock(appId)) {
      Map<String, AppConfigValue> held = repository.appConfig(app.id());
      Map<String, @Nullable String> values = checkValues(raw, held);
      List<TargetState> resolved = resolveTargets(app.id(), t);
      List<InstanceKeys> rejected = checkAllowed(values, resolved);
      Map<String, String> before = new TreeMap<>();
      Map<String, @Nullable String> after = new TreeMap<>();
      for (Map.Entry<String, @Nullable String> e : values.entrySet()) {
        AppConfigValue h = held.get(e.getKey());
        if (h != null) {
          before.put(e.getKey(), h.value());
        }
        after.put(e.getKey(), e.getValue());
      }
      List<DiffEntry> diff = diff(before, after);
      UUID id = UUID.randomUUID();
      Instant now = clock.instant();
      repository.insert(
          new NewChange(
              id,
              app.id(),
              kind,
              t.all(),
              t.all() ? List.of() : t.instanceIds(),
              why,
              before,
              nullsAsJson(after),
              previewJson(diff, rejected),
              principal.userId(),
              now,
              revertOf));
      Map<String, Object> auditAfter = new LinkedHashMap<>();
      auditAfter.put("app_id", app.id());
      auditAfter.put("kind", kind);
      auditAfter.put("reason", why);
      auditAfter.put("diff", maskedDiff(diff));
      auditAfter.put("targets", t.all() ? "all" : t.instanceIds());
      audit.record(
          AuditEvent.CONFIG_CHANGE_CREATED,
          actor,
          target(id, app),
          AuditResult.success(),
          null,
          auditAfter);
      return change(id);
    }
  }

  /**
   * The reason: required, at most {@value #MAX_REASON_BYTES} UTF-8 bytes, no control characters.
   */
  static String checkReason(@Nullable String reason) {
    if (reason == null || reason.isBlank()) {
      throw new ApiException(ErrorCode.INVALID_REQUEST, "A reason is required")
          .detail("reason", "required");
    }
    String r = reason.strip();
    if (r.getBytes(StandardCharsets.UTF_8).length > MAX_REASON_BYTES) {
      throw new ApiException(ErrorCode.LIMIT_EXCEEDED, "The reason is longer than 1 KiB")
          .detail("reason", "too_long");
    }
    if (r.chars().anyMatch(c -> (c < 0x20 && c != '\n' && c != '\t') || c == 0x7f)) {
      throw new ApiException(ErrorCode.INVALID_REQUEST, "The reason has control characters")
          .detail("reason", "invalid_format");
    }
    return r;
  }

  /**
   * The request's {@code {key: value}} as canonical keys and raw string values (null: remove):
   * strings as given, booleans and numbers as text, a list of scalars as a flow sequence.
   */
  static Map<String, @Nullable String> values(@Nullable Map<String, ?> changes) {
    if (changes == null || changes.isEmpty()) {
      throw new ApiException(ErrorCode.INVALID_REQUEST, "At least one key is required")
          .detail("changes", "required");
    }
    if (changes.size() > MAX_KEYS) {
      throw new ApiException(ErrorCode.LIMIT_EXCEEDED, "Too many keys in one change")
          .detail("changes", "too_many");
    }
    Map<String, @Nullable String> out = new LinkedHashMap<>();
    for (Map.Entry<String, ?> e : changes.entrySet()) {
      String key = e.getKey() == null ? "" : AgentKeySchema.canonical(e.getKey());
      if (!AgentKeySchema.wellFormed(key)) {
        throw new ApiException(ErrorCode.INVALID_REQUEST, "A key is not a configuration key")
            .detail("changes", "unknown_key");
      }
      if (out.containsKey(key)) {
        throw new ApiException(ErrorCode.INVALID_REQUEST, "A key is given twice")
            .detail(field(key), "duplicate");
      }
      out.put(key, raw(key, e.getValue()));
    }
    return out;
  }

  private static @Nullable String raw(String key, @Nullable Object value) {
    if (value == null) {
      return null;
    }
    if (value instanceof String s) {
      return s;
    }
    if (value instanceof Boolean b) {
      return b.toString();
    }
    if (value instanceof Number n) {
      return new BigDecimal(n.toString()).toPlainString();
    }
    if (value instanceof List<?> list) {
      List<String> items = new ArrayList<>();
      for (Object item : list) {
        if (item == null || item instanceof Map<?, ?> || item instanceof List<?>) {
          throw invalidValue(key);
        }
        items.add(item.toString());
      }
      return AgentKeySchema.flow(items);
    }
    throw invalidValue(key);
  }

  private static ApiException invalidValue(String key) {
    return new ApiException(ErrorCode.INVALID_REQUEST, "A value is invalid for its key")
        .detail(field(key), AgentKeySchema.Refusal.INVALID_VALUE.code());
  }

  /**
   * The schema check of every key (all refusals in one {@code 400}) and the add-only rule of the
   * mask pattern lists against the server-held values; returns the normalized values.
   */
  static Map<String, @Nullable String> checkValues(
      Map<String, @Nullable String> raw, Map<String, AppConfigValue> held) {
    Map<String, @Nullable String> out = new TreeMap<>();
    ApiException refused = null;
    for (Map.Entry<String, @Nullable String> e : raw.entrySet()) {
      String key = e.getKey();
      String reason = null;
      String normalized = null;
      if (e.getValue() == null) {
        if (AgentKeySchema.immutablePrefix(key)) {
          reason = AgentKeySchema.Refusal.IMMUTABLE.code();
        } else {
          Optional<AgentKeySchema.Key> k = AgentKeySchema.find(key);
          if (k.isEmpty()) {
            reason = AgentKeySchema.Refusal.UNKNOWN_KEY.code();
          } else if (!k.get().remotable()) {
            reason = AgentKeySchema.Refusal.IMMUTABLE.code();
          }
        }
      } else {
        AgentKeySchema.Checked c = AgentKeySchema.check(key, e.getValue());
        if (c.ok()) {
          normalized = c.value();
        } else {
          reason = Objects.requireNonNull(c.refusal()).code();
        }
      }
      if (reason == null && MASK_PATTERN_KEYS.contains(key) && held.containsKey(key)) {
        List<String> kept = AgentKeySchema.splitList(held.get(key).value());
        List<String> next = normalized == null ? List.of() : AgentKeySchema.splitList(normalized);
        if (kept != null && next != null && !next.containsAll(kept)) {
          reason = "reduce_masking";
        }
      }
      if (reason != null) {
        if (refused == null) {
          refused =
              new ApiException(
                  ErrorCode.INVALID_REQUEST, "A key is unknown, immutable or has an invalid value");
        }
        refused.detail(field(key), reason);
        continue;
      }
      out.put(key, normalized);
    }
    if (refused != null) {
      throw refused;
    }
    return out;
  }

  /** The targets with their allow lists: the live session's, else the stored one. */
  private List<TargetState> resolveTargets(int appId, Targets t) {
    List<InstanceRow> instances = repository.instancesOf(appId);
    List<InstanceRow> chosen;
    if (t.all()) {
      chosen = instances;
    } else {
      if (t.instanceIds().isEmpty()) {
        throw new ApiException(ErrorCode.INVALID_REQUEST, "Name at least one instance")
            .detail("targets.instance_ids", "required");
      }
      if (t.instanceIds().size() > MAX_TARGETS) {
        throw new ApiException(ErrorCode.LIMIT_EXCEEDED, "At most 100 instances per change")
            .detail("targets.instance_ids", "too_many");
      }
      Map<Integer, InstanceRow> byId = new HashMap<>();
      instances.forEach(i -> byId.put(i.id(), i));
      chosen = new ArrayList<>();
      Set<Integer> seen = new HashSet<>();
      for (Integer id : t.instanceIds()) {
        InstanceRow row = id == null ? null : byId.get(id);
        if (row == null) {
          throw new ApiException(
                  ErrorCode.INVALID_REQUEST, "An instance is not an active instance of this app")
              .detail("targets.instance_ids", "unknown_instance");
        }
        if (seen.add(id)) {
          chosen.add(row);
        }
      }
    }
    List<TargetState> out = new ArrayList<>(chosen.size());
    for (InstanceRow row : chosen) {
      Optional<AgentSession> live = sessions.find(row.id());
      List<String> allow =
          live.map(AgentSession::remoteConfigAllow).orElse(row.remoteConfigAllow());
      out.add(new TargetState(row, AllowPatterns.of(allow), live.isPresent()));
    }
    return out;
  }

  /**
   * DSN-12-09 against the targets' allow lists (keys with a value only: a removal is not pushed): a
   * key no target allows is {@code 422 remote_key_not_allowed} (14 §2.6, ADR-0010 C-231 (4)) naming
   * the key and the targets; returns, per target, the keys its list does not name.
   */
  static List<InstanceKeys> checkAllowed(
      Map<String, @Nullable String> values, List<TargetState> targets) {
    if (targets.isEmpty()) {
      return List.of();
    }
    Map<Integer, List<String>> refusedBy = new TreeMap<>();
    ApiException none = null;
    for (Map.Entry<String, @Nullable String> e : values.entrySet()) {
      if (e.getValue() == null) {
        continue;
      }
      int refusing = 0;
      for (TargetState t : targets) {
        if (!t.allow().matches(e.getKey())) {
          refusing++;
          refusedBy.computeIfAbsent(t.instance().id(), k -> new ArrayList<>()).add(e.getKey());
        }
      }
      if (refusing == targets.size()) {
        if (none == null) {
          none =
              new ApiException(
                  ErrorCode.REMOTE_KEY_NOT_ALLOWED,
                  "No target instance allows a key in its remote-config.allow list");
        }
        none.detail(field(e.getKey()), "not_allowed_by_targets");
      }
    }
    if (none != null) {
      for (TargetState t : targets) {
        none.detail("targets.instance_ids." + t.instance().id(), "not_allowed");
      }
      throw none;
    }
    List<InstanceKeys> out = new ArrayList<>();
    refusedBy.forEach((id, keys) -> out.add(new InstanceKeys(id, List.copyOf(keys))));
    return out;
  }

  /** The diff of the server-held values {@code before} and the new values {@code after}. */
  static List<DiffEntry> diff(Map<String, String> before, Map<String, @Nullable String> after) {
    List<DiffEntry> out = new ArrayList<>();
    new TreeMap<>(after).forEach((k, v) -> out.add(new DiffEntry(k, before.get(k), v)));
    return out;
  }

  /**
   * The values of a revert: for every key of the reverted change, the value the server held before
   * it, null where it held none (removed and not pushed).
   */
  static Map<String, @Nullable String> revertValues(ChangeRow row) {
    Map<String, @Nullable String> out = new TreeMap<>();
    for (String key : row.after().keySet()) {
      out.put(key, row.before().get(key));
    }
    return out;
  }

  // --- Apply -----------------------------------------------------------------------------------

  /**
   * Applies a preview (14 §4.14 {@code POST /admin/config/changes/{changeId}/apply}).
   *
   * @throws ApiException 404, 409 {@code invalid_state} when the change is not a preview
   */
  public Change apply(ArgosPrincipal principal, Actor actor, UUID changeId) {
    requireAdmin(principal);
    ChangeRow row =
        repository.find(changeId).orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
    return applyRow(principal, actor, row);
  }

  private Change applyRow(ArgosPrincipal principal, Actor actor, ChangeRow row) {
    AppRow app =
        repository.app(row.appId()).orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
    Map<String, @Nullable String> after = jsonNulls(row.after());
    boolean revert = row.revertedFrom() != null;
    Instant now = clock.instant();
    Map<Integer, List<String>> notPushed = new TreeMap<>();
    int pushed = 0;
    synchronized (lock(row.appId())) {
      if (!repository.markApplying(row.id(), principal.userId(), now)) {
        throw new ApiException(ErrorCode.INVALID_STATE, "The change is not a preview")
            .detail("status", repository.find(row.id()).map(ChangeRow::status).orElse("unknown"));
      }
      applying.add(row.id());
      repository.supersedePreviews(row.appId(), row.id());
      repository.putAppConfig(row.appId(), after, row.id(), principal.userId(), now);
      List<TargetState> targets;
      if (row.targetAll()) {
        targets = resolveTargets(row.appId(), Targets.everyInstance());
      } else {
        Set<Integer> active = new HashSet<>();
        repository.instancesOf(row.appId()).forEach(i -> active.add(i.id()));
        List<Integer> still =
            row.targetInstanceIds().stream().filter(active::contains).distinct().toList();
        targets =
            still.isEmpty() ? List.of() : resolveTargets(row.appId(), new Targets(false, still));
      }
      for (TargetState t : targets) {
        Map<String, String> values = new TreeMap<>();
        List<String> refused = new ArrayList<>();
        after.forEach(
            (k, v) -> {
              if (v != null) {
                if (t.allow().matches(k)) {
                  values.put(k, v);
                } else {
                  refused.add(k);
                }
              }
            });
        if (!refused.isEmpty()) {
          notPushed.put(t.instance().id(), refused);
        }
        String serverRejected = rejectedJson(serverRejections(refused), List.of());
        int id = t.instance().id();
        if (!t.online()) {
          repository.upsertResult(
              result(row.id(), id, ConfigChangeRepository.PENDING, List.of(), refused, null, null));
          continue;
        }
        if (values.isEmpty()) {
          String status =
              refused.isEmpty()
                  ? ConfigChangeRepository.RESULT_APPLIED
                  : ConfigChangeRepository.RESULT_REJECTED;
          repository.upsertResult(
              new ResultRow(
                  row.id(),
                  id,
                  status,
                  List.of(),
                  refused,
                  serverRejected,
                  null,
                  null,
                  now,
                  null,
                  null));
          continue;
        }
        repository.upsertResult(
            new ResultRow(
                row.id(),
                id,
                ConfigChangeRepository.PENDING,
                List.of(),
                refused,
                serverRejected,
                null,
                null,
                null,
                null,
                now));
        ConfigPush message =
            ConfigPush.newBuilder()
                .setChangeId(row.id().toString())
                .putAllValues(values)
                .setRevert(revert)
                .setIssuedAtMs(now.toEpochMilli())
                .build();
        if (messenger.pushConfig(id, message)) {
          pushed++;
        } else {
          repository.upsertResult(
              new ResultRow(
                  row.id(),
                  id,
                  ConfigChangeRepository.RESULT_OFFLINE,
                  List.of(),
                  refused,
                  serverRejected,
                  null,
                  null,
                  null,
                  NOT_DELIVERED,
                  null));
        }
      }
    }
    meters.counter("argos.config.pushes", "kind", row.kind()).increment(pushed);
    auditApply(actor, row, app, notPushed);
    event(row, app, principal.userId(), now);
    publish(row.id(), app.id(), row.kind(), ConfigChangeRepository.APPLYING);
    finishIfDone(row.id());
    return change(row.id());
  }

  private void auditApply(
      Actor actor, ChangeRow row, AppRow app, Map<Integer, List<String>> notPushed) {
    Map<String, Object> before = new LinkedHashMap<>();
    Map<String, Object> after = new LinkedHashMap<>();
    for (Map.Entry<String, String> e : row.after().entrySet()) {
      before.put(e.getKey(), ConfigValueMasking.mask(e.getKey(), row.before().get(e.getKey())));
      after.put(e.getKey(), ConfigValueMasking.mask(e.getKey(), jsonNull(e.getValue())));
    }
    Map<String, Object> notSent = new LinkedHashMap<>();
    notPushed.entrySet().stream()
        .limit(AUDIT_INSTANCES)
        .forEach(e -> notSent.put(Integer.toString(e.getKey()), e.getValue()));
    after.put("_not_allowed_by_instance", notSent);
    boolean kill = KIND_KILL_SWITCH.equals(row.kind());
    if (kill) {
      String enabled = jsonNull(row.after().get(AgentKeySchema.KILL_SWITCH_KEY));
      after.put("_kill_switch", "false".equals(enabled) ? "on" : "off");
    }
    audit.record(
        kill ? AuditEvent.KILL_SWITCH : AuditEvent.CONFIG_PUSH,
        actor,
        target(row.id(), app),
        AuditResult.success(),
        before,
        after);
    rememberCustomMethods(actor, row, app, before, after);
  }

  private static boolean isCustomMethodsKey(String key) {
    return key.equals(CUSTOM_METHODS_KEY) || key.startsWith(CUSTOM_METHODS_KEY + ".");
  }

  /**
   * Keeps the actor and the custom method keys (masked) of a change that sets them, for {@link
   * #auditCustomMethods} at its outcome.
   */
  private void rememberCustomMethods(
      Actor actor,
      ChangeRow row,
      AppRow app,
      Map<String, Object> before,
      Map<String, Object> after) {
    Map<String, Object> customBefore = new LinkedHashMap<>();
    Map<String, Object> customAfter = new LinkedHashMap<>();
    for (String key : row.after().keySet()) {
      if (isCustomMethodsKey(key)) {
        customBefore.put(key, before.get(key));
        customAfter.put(key, after.get(key));
      }
    }
    if (!customAfter.isEmpty()) {
      customAudits.put(
          row.id(), new CustomAudit(actor, target(row.id(), app), customBefore, customAfter));
    }
  }

  /**
   * 16 §5.7 {@code CUSTOM_METHOD_CHANGED} (ADR-0010 C-238 (8)): besides {@code CONFIG_PUSH}, the
   * outcome of a change (or revert) of the custom method keys ({@value #CUSTOM_METHODS_KEY} and its
   * sub-keys) once its instances answered: {@code success} when an instance applied the key and
   * none rejected it, {@code success} with reason {@code partial} when some did each, {@code
   * rejected} ({@code rejected_by_agent} or {@code rejected_by_server}) when every answer rejected
   * it, and {@code failure} {@code not_applied} when no instance answered (offline, pending). The
   * detail holds those keys (masked like {@code CONFIG_PUSH}) and {@code _outcome}: the counts and,
   * per instance (at most {@value #AUDIT_INSTANCES}), the status and the rejection code, never
   * values.
   */
  private void auditCustomMethods(ChangeRow row, List<ResultRow> results) {
    Set<String> keys = new LinkedHashSet<>();
    row.after().keySet().stream()
        .filter(ConfigChangeService::isCustomMethodsKey)
        .forEach(keys::add);
    if (keys.isEmpty()) {
      return;
    }
    CustomAudit pending = customAudits.remove(row.id());
    Map<String, Object> before = new LinkedHashMap<>();
    Map<String, Object> after = new LinkedHashMap<>();
    if (pending != null) {
      before.putAll(pending.before());
      after.putAll(pending.after());
    } else {
      for (String key : keys) {
        before.put(key, ConfigValueMasking.mask(key, row.before().get(key)));
        after.put(key, ConfigValueMasking.mask(key, jsonNull(row.after().get(key))));
      }
    }
    int applied = 0;
    int byAgent = 0;
    int byServer = 0;
    int other = 0;
    Map<String, Object> instances = new LinkedHashMap<>();
    for (ResultRow r : results) {
      boolean rejected = r.rejectedKeys().stream().anyMatch(keys::contains);
      boolean took = !rejected && r.appliedKeys().stream().anyMatch(keys::contains);
      String code = rejected ? customRejectionCode(r.rejectedJson(), keys) : null;
      if (took) {
        applied++;
      } else if (rejected && code != null && !code.startsWith(BY_SERVER + ":")) {
        byAgent++;
      } else if (rejected) {
        byServer++;
      } else {
        other++;
      }
      if (instances.size() < AUDIT_INSTANCES) {
        Map<String, Object> one = new LinkedHashMap<>();
        one.put("status", took ? "applied" : rejected ? "rejected" : r.status());
        if (code != null) {
          one.put("code", code);
        }
        instances.put(Integer.toString(r.instanceId()), one);
      }
    }
    Map<String, Object> outcome = new LinkedHashMap<>();
    outcome.put("applied", applied);
    outcome.put("rejected_by_agent", byAgent);
    outcome.put("rejected_by_server", byServer);
    outcome.put("not_applied", other);
    outcome.put("instances", instances);
    after.put("_outcome", outcome);
    AuditResult result;
    if (applied > 0) {
      result = byAgent + byServer == 0 ? AuditResult.success() : AuditResult.success("partial");
    } else if (byAgent > 0) {
      result = AuditResult.rejected("rejected_by_agent");
    } else if (byServer > 0) {
      result = AuditResult.rejected("rejected_by_server");
    } else {
      result = AuditResult.failure("not_applied");
    }
    try {
      audit.record(
          AuditEvent.CUSTOM_METHOD_CHANGED,
          pending == null ? storedActor(row) : pending.actor(),
          pending == null ? storedTarget(row) : pending.target(),
          result,
          before,
          after);
    } catch (RuntimeException e) {
      LOG.debug("An audit record failed: {}", e.getClass().getSimpleName());
    }
  }

  /**
   * The actor of a custom method outcome whose apply this service no longer remembers (a restart,
   * ADR-0010 C-239 (3)): the user recorded on the change, {@code applied_by} or else {@code
   * requested_by}, with the name read now (the id as the name for a deleted user); the system only
   * when the change names nobody or the user cannot be read.
   */
  private Actor storedActor(ChangeRow row) {
    UUID user = row.appliedBy() != null ? row.appliedBy() : row.requestedBy();
    if (user == null) {
      return Actor.system();
    }
    try {
      return Actor.user(user, repository.username(user).orElse(user.toString()), null);
    } catch (RuntimeException e) {
      LOG.debug("The applying user could not be read: {}", e.getClass().getSimpleName());
      return Actor.system();
    }
  }

  /**
   * The target of a custom method outcome: as at apply, {@code config_change} with the app name.
   */
  private Target storedTarget(ChangeRow row) {
    String app = null;
    try {
      app = repository.app(row.appId()).map(AppRow::name).orElse(null);
    } catch (RuntimeException e) {
      LOG.debug("The application could not be read: {}", e.getClass().getSimpleName());
    }
    return new Target("config_change", row.id().toString(), app);
  }

  /**
   * The rejection code of the first custom method key in {@code rejectedJson}: the agent's code
   * ({@code forbidden_pattern}, {@code limit_exceeded}, ...) or {@code server:<code>} for a key the
   * instance's allow list does not name; {@code server:not_allowed} without an entry.
   */
  private static @Nullable String customRejectionCode(
      @Nullable String rejectedJson, Set<String> keys) {
    if (rejectedJson != null && !rejectedJson.isBlank()) {
      for (JsonNode n : JSON.readTree(rejectedJson)) {
        if (keys.contains(n.path("key").asString())) {
          String code = n.path("code").asString();
          return BY_SERVER.equals(n.path("by").asString()) ? BY_SERVER + ":" + code : code;
        }
      }
    }
    return BY_SERVER + ":not_allowed";
  }

  private void event(ChangeRow row, AppRow app, UUID userId, Instant now) {
    try {
      ObjectNode detail = JSON.createObjectNode();
      detail.put("change_id", row.id().toString());
      ArrayNode keys = detail.putArray("keys");
      row.after().keySet().forEach(keys::add);
      if (row.revertedFrom() != null) {
        detail.put("revert_of", row.revertedFrom().toString());
      }
      String type;
      String title;
      if (KIND_KILL_SWITCH.equals(row.kind())) {
        boolean on = "false".equals(jsonNull(row.after().get(AgentKeySchema.KILL_SWITCH_KEY)));
        detail.put("kill_switch", on ? "on" : "off");
        type = "kill_switch";
        title = on ? "Kill switch on" : "Kill switch off";
      } else {
        type = "config";
        title =
            row.revertedFrom() == null
                ? "Configuration change applied"
                : "Configuration change reverted";
      }
      events.insert(
          UUID.randomUUID(),
          app.id(),
          app.env(),
          type,
          title,
          JSON.writeValueAsString(detail),
          null,
          now,
          userId);
    } catch (RuntimeException e) {
      LOG.warn("The event of a configuration change was not stored: {}", e.getClass().getName());
    }
  }

  // --- Revert ----------------------------------------------------------------------------------

  /**
   * Reverts an applied change (14 §4.14 {@code POST /admin/config/changes/{changeId}/revert}): a
   * new change of the previous values, applied at once.
   *
   * @throws ApiException 400 without a reason, 404, 409 when the change was not applied
   */
  public Change revert(
      ArgosPrincipal principal, Actor actor, UUID changeId, @Nullable String reason) {
    requireAdmin(principal);
    String why = checkReason(reason);
    ChangeRow row =
        repository.find(changeId).orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
    if (!ConfigChangeRepository.APPLYING.equals(row.status())
        && !ConfigChangeRepository.APPLIED.equals(row.status())) {
      throw new ApiException(ErrorCode.INVALID_STATE, "Only an applied change can be reverted")
          .detail("status", row.status());
    }
    Targets targets =
        row.targetAll() ? Targets.everyInstance() : new Targets(false, activeTargets(row));
    Change created =
        proposeKind(
            principal, actor, row.appId(), why, revertValues(row), targets, row.kind(), row.id());
    AppRow app =
        repository.app(row.appId()).orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
    Map<String, Object> after = new LinkedHashMap<>();
    after.put("reverted_change_id", row.id().toString());
    after.put("reason", why);
    after.put("diff", maskedDiff(created.diff()));
    audit.record(
        AuditEvent.CONFIG_ROLLBACK,
        actor,
        target(created.row().id(), app),
        AuditResult.success(),
        null,
        after);
    return applyRow(principal, actor, created.row());
  }

  private List<Integer> activeTargets(ChangeRow row) {
    Set<Integer> active = new HashSet<>();
    repository.instancesOf(row.appId()).forEach(i -> active.add(i.id()));
    List<Integer> still =
        row.targetInstanceIds().stream().filter(active::contains).distinct().toList();
    if (still.isEmpty()) {
      throw new ApiException(
              ErrorCode.INVALID_STATE, "None of the change's instances is active anymore")
          .detail("targets.instance_ids", "none_active");
    }
    return still;
  }

  // --- Results ---------------------------------------------------------------------------------

  @Override
  public void onResult(int instanceId, ConfigApplyResult result) {
    long receivedAt = clock.millis();
    submit(() -> handleResult(instanceId, result, receivedAt), "result");
  }

  /** One {@code ConfigApplyResult} of {@code instanceId} (worker). */
  void handleResult(int instanceId, ConfigApplyResult result, long receivedAtMs) {
    UUID changeId;
    try {
      changeId = UUID.fromString(result.getChangeId());
    } catch (IllegalArgumentException e) {
      dropped("unknown_change");
      return;
    }
    Optional<ResultRow> existing = repository.result(changeId, instanceId);
    if (existing.isEmpty()
        || !(ConfigChangeRepository.PENDING.equals(existing.get().status())
            || ConfigChangeRepository.RESULT_OFFLINE.equals(existing.get().status()))) {
      dropped("not_awaited");
      return;
    }
    ResultRow row = existing.get();
    List<ObjectNode> serverRejected = serverEntries(row.rejectedJson());
    List<ObjectNode> agentRejected = new ArrayList<>();
    Set<String> rejectedKeys = new LinkedHashSet<>(row.rejectedKeys());
    for (RejectedKey r : result.getRejectedList()) {
      String key = AgentKeySchema.canonical(r.getKey());
      if (!AgentKeySchema.wellFormed(key)) {
        continue;
      }
      ObjectNode n = JSON.createObjectNode();
      n.put("key", key);
      n.put("code", r.getCode().name().toLowerCase(java.util.Locale.ROOT));
      n.put("by", BY_AGENT);
      agentRejected.add(n);
      rejectedKeys.add(key);
    }
    List<String> applied = new ArrayList<>();
    for (String k : result.getAppliedKeysList()) {
      String key = AgentKeySchema.canonical(k);
      if (AgentKeySchema.wellFormed(key) && !rejectedKeys.contains(key)) {
        applied.add(key);
      }
    }
    String status;
    if (!applied.isEmpty() && !rejectedKeys.isEmpty()) {
      status = ConfigChangeRepository.RESULT_PARTIAL;
    } else if (!rejectedKeys.isEmpty()) {
      status = ConfigChangeRepository.RESULT_REJECTED;
    } else {
      status = ConfigChangeRepository.RESULT_APPLIED;
    }
    String hash = result.getEffectiveConfigHash();
    // The push-to-answer round trip (N-25) when the server pushed, else the agent's own figure.
    Integer duration;
    if (row.pushedAt() != null) {
      duration =
          (int)
              Math.max(
                  0, Math.min(Integer.MAX_VALUE, receivedAtMs - row.pushedAt().toEpochMilli()));
    } else {
      duration = result.getDurationMs() == 0 ? null : Integer.valueOf(result.getDurationMs());
    }
    repository.upsertResult(
        new ResultRow(
            changeId,
            instanceId,
            status,
            applied,
            List.copyOf(rejectedKeys),
            rejectedJson(serverRejected, agentRejected),
            hash.isEmpty() || hash.length() > 64 ? null : hash,
            duration,
            Instant.ofEpochMilli(receivedAtMs),
            null,
            row.pushedAt()));
    Optional<ChangeRow> change = repository.find(changeId);
    if (change.isEmpty()) {
      return;
    }
    publishResult(change.get(), instanceId, status, List.copyOf(rejectedKeys));
    if (!applying.contains(changeId)
        && ConfigChangeRepository.APPLIED.equals(change.get().status())) {
      // A late answer (after the timeout): audited on its own.
      auditResults(change.get(), List.of(repository.result(changeId, instanceId).orElseThrow()));
      return;
    }
    finishIfDone(changeId);
  }

  @Override
  public void onSessionEnd(int instanceId, int appId) {
    submit(
        () -> {
          for (UUID changeId :
              new LinkedHashSet<>(repository.expireAwaitingOf(instanceId, SESSION_ENDED))) {
            finishIfDone(changeId);
          }
        },
        "session_end");
  }

  /** The result timeout (worker, every second): pushes without an answer become offline. */
  public void tick() {
    if (!resumed) {
      resume();
    }
    if (applying.isEmpty()) {
      return;
    }
    Instant cutoff = clock.instant().minus(RESULT_TIMEOUT);
    Set<UUID> changed = new LinkedHashSet<>(repository.expireAwaiting(cutoff, NO_RESULT));
    changed.addAll(applying);
    for (UUID id : changed) {
      finishIfDone(id);
    }
  }

  /** Resumes the result timeout of the changes still applying (a restart). */
  public void resume() {
    try {
      applying.addAll(repository.applyingChanges());
      resumed = true;
    } catch (RuntimeException e) {
      // Before the start sequence opened PostgreSQL: the next tick tries again.
      LOG.debug("The applying configuration changes were not read: {}", e.getClass().getName());
    }
  }

  /** The change is applied once no push waits for an answer: audit and push the results. */
  private void finishIfDone(UUID changeId) {
    if (!repository.completeIfDone(changeId)) {
      return;
    }
    applying.remove(changeId);
    Optional<ChangeRow> row = repository.find(changeId);
    if (row.isEmpty()) {
      return;
    }
    List<ResultRow> results = repository.results(changeId);
    auditResults(row.get(), results);
    Map<String, Integer> counts = new TreeMap<>();
    results.forEach(r -> counts.merge(r.status(), 1, Integer::sum));
    publishRaw(changeId, donePushData(changeId, row.get().appId(), row.get().kind(), counts));
  }

  private void auditResults(ChangeRow row, List<ResultRow> results) {
    Map<String, Object> after = new LinkedHashMap<>();
    Map<String, Object> byInstance = new LinkedHashMap<>();
    Map<String, Integer> counts = new TreeMap<>();
    for (ResultRow r : results) {
      counts.merge(r.status(), 1, Integer::sum);
      if (byInstance.size() < AUDIT_INSTANCES) {
        Map<String, Object> one = new LinkedHashMap<>();
        one.put("status", r.status());
        one.put("rejected_keys", r.rejectedKeys());
        if (r.error() != null) {
          one.put("error", r.error());
        }
        byInstance.put(Integer.toString(r.instanceId()), one);
      }
    }
    after.put("kind", row.kind());
    after.put("counts", counts);
    after.put("instances", byInstance);
    try {
      audit.record(
          AuditEvent.CONFIG_CHANGE_APPLIED,
          Actor.system(),
          new Target("config_change", row.id().toString(), null),
          AuditResult.success(),
          null,
          after);
    } catch (RuntimeException e) {
      LOG.debug("An audit record failed: {}", e.getClass().getSimpleName());
    }
    auditCustomMethods(row, results);
  }

  // --- Hello -----------------------------------------------------------------------------------

  /**
   * {@code ServerHello.config}: the server-held values of the app whose keys the Hello's allow list
   * names; the instance's undelivered results are then resolved on the worker.
   */
  @Override
  public Map<String, String> configFor(int appId, int instanceId, List<String> remoteConfigAllow) {
    AllowPatterns allow = AllowPatterns.of(remoteConfigAllow);
    Map<String, String> out = new TreeMap<>();
    for (AppConfigValue v : repository.appConfig(appId).values()) {
      Optional<AgentKeySchema.Key> k = AgentKeySchema.find(v.key());
      if (k.isPresent()
          && k.get().remotable()
          && !AgentKeySchema.immutablePrefix(v.key())
          && allow.matches(v.key())) {
        out.put(v.key(), v.value());
      }
    }
    long now = clock.millis();
    submit(() -> delivered(instanceId, allow, now), "hello");
    return out;
  }

  /**
   * The results of {@code instanceId} that were pending or offline: its Hello carried the
   * server-held values, so the keys its list names are applied, the others rejected by the server
   * (worker).
   */
  void delivered(int instanceId, AllowPatterns allow, long atMs) {
    for (ResultRow r : repository.undeliveredOf(instanceId)) {
      Optional<ChangeRow> change = repository.find(r.changeId());
      if (change.isEmpty()) {
        continue;
      }
      List<String> applied = new ArrayList<>();
      List<String> refused = new ArrayList<>();
      jsonNulls(change.get().after())
          .forEach(
              (k, v) -> {
                if (v != null) {
                  (allow.matches(k) ? applied : refused).add(k);
                }
              });
      String status =
          refused.isEmpty()
              ? ConfigChangeRepository.RESULT_APPLIED
              : applied.isEmpty()
                  ? ConfigChangeRepository.RESULT_REJECTED
                  : ConfigChangeRepository.RESULT_PARTIAL;
      repository.upsertResult(
          new ResultRow(
              r.changeId(),
              instanceId,
              status,
              applied,
              refused,
              rejectedJson(serverRejections(refused), List.of()),
              null,
              null,
              Instant.ofEpochMilli(atMs),
              null,
              r.pushedAt()));
      publishResult(change.get(), instanceId, status, refused);
      finishIfDone(r.changeId());
    }
  }

  // --- Reads -----------------------------------------------------------------------------------

  /** A change with its results ({@code GET /admin/config/changes/{changeId}}). */
  public Change change(UUID changeId) {
    ChangeRow row =
        repository.find(changeId).orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
    return view(row, repository.results(changeId));
  }

  private static Change view(ChangeRow row, List<ResultRow> results) {
    Map<String, @Nullable String> after = jsonNulls(row.after());
    return new Change(
        row, diff(row.before(), after), previewRejections(row.previewJson()), results);
  }

  /** The changes of an app, newest first, by keyset cursor. */
  public Page list(int appId, @Nullable String cursor, int limit) {
    if (repository.app(appId).isEmpty()) {
      throw new ApiException(ErrorCode.NOT_FOUND);
    }
    int n = Math.max(1, Math.min(MAX_LIST, limit));
    String scope = "admin_config_changes|" + appId;
    Instant afterTs = null;
    UUID afterId = null;
    if (cursor != null && !cursor.isBlank()) {
      List<String> v = cursors.decode(scope, cursor);
      try {
        afterTs = Instant.parse(v.get(0));
        afterId = UUID.fromString(v.get(1));
      } catch (RuntimeException e) {
        throw new ApiException(ErrorCode.FILTER_INVALID).detail("cursor", "invalid");
      }
    }
    List<ChangeRow> rows = repository.listByApp(appId, afterTs, afterId, n + 1);
    boolean more = rows.size() > n;
    List<ChangeRow> page = more ? rows.subList(0, n) : rows;
    List<Change> out = new ArrayList<>(page.size());
    for (ChangeRow r : page) {
      out.add(view(r, repository.results(r.id())));
    }
    String next = null;
    if (more) {
      ChangeRow last = page.get(page.size() - 1);
      next = cursors.encode(scope, List.of(last.requestedAt().toString(), last.id().toString()));
    }
    return new Page(out, next);
  }

  /** {@code GET /admin/apps/{appId}/config}. */
  public AppConfig appConfig(int appId) {
    if (repository.app(appId).isEmpty()) {
      throw new ApiException(ErrorCode.NOT_FOUND);
    }
    List<InstanceRow> instances = repository.instancesOf(appId);
    Map<Integer, AgentSession> live = new HashMap<>();
    sessions.byApp(appId).forEach(s -> live.put(s.instanceId(), s));
    List<InstanceAllow> byInstance = new ArrayList<>();
    Map<Integer, AllowPatterns> allows = new HashMap<>();
    for (InstanceRow i : instances) {
      AgentSession s = live.get(i.id());
      List<String> patterns = s != null ? s.remoteConfigAllow() : i.remoteConfigAllow();
      AllowPatterns allow = AllowPatterns.of(patterns);
      allows.put(i.id(), allow);
      byInstance.add(new InstanceAllow(i.id(), i.name(), s != null, allow.patterns()));
    }
    List<String> common = new ArrayList<>();
    if (!instances.isEmpty()) {
      for (String key : AgentKeySchema.remotableKeys()) {
        if (allows.values().stream().allMatch(a -> a.matches(key))) {
          common.add(key);
        }
      }
    }
    Map<String, AppConfigValue> held = repository.appConfig(appId);
    Map<String, String> current = new TreeMap<>();
    held.values().forEach(v -> current.put(v.key(), ConfigValueMasking.mask(v.key(), v.value())));
    List<Drift> drift = new ArrayList<>(hashDrift(live.values()));
    Map<UUID, Map<Integer, ResultRow>> results = resultsByChange(held);
    for (InstanceRow i : instances) {
      for (AppConfigValue v : held.values()) {
        String state = keyState(v, i.id(), allows.get(i.id()), results);
        if (state != null && !ConfigChangeRepository.RESULT_APPLIED.equals(state)) {
          drift.add(new Drift(i.id(), v.key(), ConfigValueMasking.mask(v.key(), v.value()), state));
        }
      }
    }
    return new AppConfig(common, byInstance, current, drift);
  }

  /** {@code GET /instances/{instanceId}/effective-config}. */
  public List<Effective> effective(int instanceId) {
    InstanceRow instance =
        repository.instance(instanceId).orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
    Optional<AgentSession> live = sessions.find(instanceId);
    AllowPatterns allow =
        AllowPatterns.of(
            live.map(AgentSession::remoteConfigAllow).orElse(instance.remoteConfigAllow()));
    Map<String, AppConfigValue> held = repository.appConfig(instance.appId());
    Map<UUID, Map<Integer, ResultRow>> results = resultsByChange(held);
    Set<String> keys = new LinkedHashSet<>(AgentKeySchema.remotableKeys());
    keys.addAll(new TreeMap<>(held).keySet());
    List<Effective> out = new ArrayList<>();
    for (String key : keys) {
      AppConfigValue v = held.get(key);
      boolean allowed = allow.matches(key);
      if (v == null) {
        out.add(new Effective(key, null, null, allowed, null, null));
        continue;
      }
      String state = keyState(v, instanceId, allow, results);
      String masked = ConfigValueMasking.mask(key, v.value());
      boolean runs = ConfigChangeRepository.RESULT_APPLIED.equals(state);
      out.add(
          new Effective(key, runs ? masked : null, runs ? "server" : null, allowed, masked, state));
    }
    return out;
  }

  private Map<UUID, Map<Integer, ResultRow>> resultsByChange(Map<String, AppConfigValue> held) {
    Set<UUID> ids = new HashSet<>();
    held.values()
        .forEach(
            v -> {
              if (v.changeId() != null) {
                ids.add(v.changeId());
              }
            });
    Map<UUID, Map<Integer, ResultRow>> out = new HashMap<>();
    for (ResultRow r : repository.resultsOfChanges(ids)) {
      out.computeIfAbsent(r.changeId(), k -> new HashMap<>()).put(r.instanceId(), r);
    }
    return out;
  }

  /**
   * The state of a server-held value on an instance: {@code not_allowed}, {@code applied}, {@code
   * pending}, {@code rejected}. An instance without a result of the change that set the value
   * received it in a later Hello (it was not a target or did not exist yet): {@code applied}.
   */
  static String keyState(
      AppConfigValue v,
      int instanceId,
      AllowPatterns allow,
      Map<UUID, Map<Integer, ResultRow>> results) {
    if (!allow.matches(v.key())) {
      return "not_allowed";
    }
    ResultRow r =
        v.changeId() == null ? null : results.getOrDefault(v.changeId(), Map.of()).get(instanceId);
    if (r == null) {
      return ConfigChangeRepository.RESULT_APPLIED;
    }
    if (ConfigChangeRepository.PENDING.equals(r.status())
        || ConfigChangeRepository.RESULT_OFFLINE.equals(r.status())) {
      return "pending";
    }
    if (r.rejectedKeys().contains(v.key()) || !r.appliedKeys().contains(v.key())) {
      return ConfigChangeRepository.RESULT_REJECTED;
    }
    return ConfigChangeRepository.RESULT_APPLIED;
  }

  /** 12 §4.2 drift of the live sessions: a configuration hash other than the app's majority. */
  static List<Drift> hashDrift(java.util.Collection<AgentSession> live) {
    Map<String, Integer> counts = new HashMap<>();
    for (AgentSession s : live) {
      String h = s.configHash();
      if (h != null && !h.isBlank()) {
        counts.merge(h, 1, Integer::sum);
      }
    }
    Optional<String> majority =
        counts.entrySet().stream()
            .max(
                Comparator.comparing(Map.Entry<String, Integer>::getValue)
                    .thenComparing(Map.Entry::getKey, Comparator.reverseOrder()))
            .map(Map.Entry::getKey);
    List<Drift> out = new ArrayList<>();
    for (AgentSession s : live) {
      String h = s.configHash();
      if (h != null && !h.isBlank() && majority.isPresent() && !h.equals(majority.get())) {
        out.add(new Drift(s.instanceId(), null, h, "config_hash"));
      }
    }
    out.sort(Comparator.comparingInt(Drift::instanceId));
    return out;
  }

  // --- Helpers ---------------------------------------------------------------------------------

  /**
   * The error detail field of a key: {@code changes.<key>} for a plain key, else {@code changes}.
   */
  static String field(String key) {
    return AgentKeySchema.plain(key) ? "changes." + key : "changes";
  }

  private static void requireAdmin(ArgosPrincipal principal) {
    if (!principal.hasRole(Role.ADMIN)) {
      throw new ApiException(
          ErrorCode.FORBIDDEN, "Configuration changes require the administrator role");
    }
  }

  private Object lock(int appId) {
    return appLocks[Math.floorMod(appId, LOCKS)];
  }

  private void submit(Runnable task, String what) {
    try {
      worker.execute(
          () -> {
            try {
              task.run();
            } catch (RuntimeException e) {
              LOG.warn("A configuration change task failed: {}", e.getClass().getName());
            }
          });
    } catch (RejectedExecutionException e) {
      dropped("busy_" + what);
    }
  }

  private void dropped(String reason) {
    meters.counter("argos.config.results.dropped", "reason", reason).increment();
  }

  private static Target target(UUID changeId, AppRow app) {
    return new Target("config_change", changeId.toString(), app.name());
  }

  private static ResultRow result(
      UUID changeId,
      int instanceId,
      String status,
      List<String> applied,
      List<String> refused,
      @Nullable Instant appliedAt,
      @Nullable Instant pushedAt) {
    return new ResultRow(
        changeId,
        instanceId,
        status,
        applied,
        refused,
        rejectedJson(serverRejections(refused), List.of()),
        null,
        null,
        appliedAt,
        null,
        pushedAt);
  }

  private static List<ObjectNode> serverRejections(List<String> keys) {
    List<ObjectNode> out = new ArrayList<>();
    for (String k : keys) {
      ObjectNode n = JSON.createObjectNode();
      n.put("key", k);
      n.put("code", "not_allowed");
      n.put("by", BY_SERVER);
      out.add(n);
    }
    return out;
  }

  private static List<ObjectNode> serverEntries(@Nullable String json) {
    List<ObjectNode> out = new ArrayList<>();
    if (json == null || json.isBlank()) {
      return out;
    }
    JsonNode node = JSON.readTree(json);
    for (JsonNode n : node) {
      if (n instanceof ObjectNode o && BY_SERVER.equals(o.path("by").asString())) {
        out.add(o);
      }
    }
    return out;
  }

  private static @Nullable String rejectedJson(List<ObjectNode> server, List<ObjectNode> agent) {
    if (server.isEmpty() && agent.isEmpty()) {
      return null;
    }
    ArrayNode a = JSON.createArrayNode();
    server.forEach(a::add);
    agent.forEach(a::add);
    return JSON.writeValueAsString(a);
  }

  /** The stored preview: {@code {diff, rejected_keys_by_instance}}. */
  private static String previewJson(List<DiffEntry> diff, List<InstanceKeys> rejected) {
    ObjectNode p = JSON.createObjectNode();
    ArrayNode d = p.putArray("diff");
    for (DiffEntry e : diff) {
      ObjectNode n = d.addObject();
      n.put("key", e.key());
      n.put("before", e.before());
      n.put("after", e.after());
    }
    ArrayNode r = p.putArray("rejected_keys_by_instance");
    for (InstanceKeys k : rejected) {
      ObjectNode n = r.addObject();
      n.put("instance_id", k.instanceId());
      ArrayNode keys = n.putArray("keys");
      k.keys().forEach(keys::add);
    }
    return JSON.writeValueAsString(p);
  }

  private static List<InstanceKeys> previewRejections(@Nullable String json) {
    if (json == null || json.isBlank()) {
      return List.of();
    }
    List<InstanceKeys> out = new ArrayList<>();
    for (JsonNode n : JSON.readTree(json).path("rejected_keys_by_instance")) {
      List<String> keys = new ArrayList<>();
      n.path("keys").forEach(k -> keys.add(k.asString()));
      out.add(new InstanceKeys(n.path("instance_id").asInt(), List.copyOf(keys)));
    }
    return out;
  }

  private static List<Map<String, @Nullable String>> maskedDiff(List<DiffEntry> diff) {
    List<Map<String, @Nullable String>> out = new ArrayList<>();
    for (DiffEntry e : diff) {
      Map<String, @Nullable String> m = new LinkedHashMap<>();
      // "name", not "key": audit masking hides the values of fields ending in "key".
      m.put("name", e.key());
      m.put("before", ConfigValueMasking.mask(e.key(), e.before()));
      m.put("after", ConfigValueMasking.mask(e.key(), e.after()));
      out.add(m);
    }
    return out;
  }

  /**
   * {@code config_changes.after} as stored: a JSON object whose {@code null} values are removals
   * (the repository's maps keep them).
   */
  private static Map<String, String> nullsAsJson(Map<String, @Nullable String> after) {
    Map<String, String> out = new LinkedHashMap<>();
    after.forEach(out::put);
    return out;
  }

  /** The stored {@code after} with its removals as null values, by key. */
  static Map<String, @Nullable String> jsonNulls(Map<String, String> stored) {
    Map<String, @Nullable String> out = new TreeMap<>();
    stored.forEach(out::put);
    return out;
  }

  private static @Nullable String jsonNull(@Nullable String stored) {
    return stored;
  }

  private void publishResult(ChangeRow change, int instanceId, String status, List<String> keys) {
    publishRaw(
        change.id(),
        resultPushData(change.id(), change.appId(), change.kind(), instanceId, status, keys));
  }

  private void publish(UUID changeId, int appId, String kind, String status) {
    ObjectNode data = baseData(changeId, appId, kind);
    data.put("status", status);
    publishRaw(changeId, JSON.writeValueAsString(data));
  }

  /**
   * The {@code data} of an {@code admin} push of one instance's result: {@code {change_id, app_id,
   * kind, status: applying, instance_id, result, rejected_keys}} (14 §5.2; key names only, no
   * values).
   */
  public static String resultPushData(
      UUID changeId,
      int appId,
      String kind,
      int instanceId,
      String result,
      List<String> rejectedKeys) {
    ObjectNode data = baseData(changeId, appId, kind);
    data.put("status", ConfigChangeRepository.APPLYING);
    data.put("instance_id", instanceId);
    data.put("result", result);
    ArrayNode k = data.putArray("rejected_keys");
    rejectedKeys.forEach(k::add);
    return JSON.writeValueAsString(data);
  }

  /**
   * The {@code data} of the {@code admin} push that ends a change: {@code {change_id, app_id, kind,
   * status: applied, results: {result: count}}} (14 §5.2), the counts in key order.
   */
  public static String donePushData(
      UUID changeId, int appId, String kind, Map<String, Integer> counts) {
    ObjectNode data = baseData(changeId, appId, kind);
    data.put("status", ConfigChangeRepository.APPLIED);
    ObjectNode c = data.putObject("results");
    new TreeMap<>(counts).forEach(c::put);
    return JSON.writeValueAsString(data);
  }

  private static ObjectNode baseData(UUID changeId, int appId, String kind) {
    ObjectNode data = JSON.createObjectNode();
    data.put("change_id", changeId.toString());
    data.put("app_id", appId);
    data.put("kind", kind);
    return data;
  }

  private void publishRaw(UUID changeId, String dataJson) {
    try {
      push.publish(changeId.toString(), null, clock.millis(), dataJson);
    } catch (RuntimeException e) {
      LOG.debug("An admin push failed: {}", e.getClass().getSimpleName());
    }
  }
}
