/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.api.transactions;

import io.argos.server.api.query.QuerySources;
import io.argos.server.api.query.UntrustedStrings;
import io.argos.server.audit.Actor;
import io.argos.server.audit.AuditEvent;
import io.argos.server.audit.AuditResult;
import io.argos.server.audit.AuditService;
import io.argos.server.audit.Target;
import io.argos.server.auth.ArgosPrincipal;
import io.argos.server.auth.Role;
import io.argos.server.config.ArgosProperties;
import io.argos.server.storage.query.DictionaryNames;
import io.argos.server.storage.query.DictionaryNames.Kind;
import io.argos.server.storage.query.QueryExecutor;
import io.argos.server.storage.query.QueryOptions;
import io.argos.server.storage.query.QueryParams;
import io.argos.server.storage.query.QueryRow;
import io.argos.server.storage.query.lookup.AppDirectory;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import org.springframework.stereotype.Component;

/**
 * Builds the transaction detail of 14 §4.6 from the rows that {@link TransactionLookup} found: the
 * names (dictionary, applications, instances), the profile steps with their state (D-07, ADR-0010
 * C-154), the bind parameters by mode and role (14 §7.3, DSN-14-16, C-13), the caller and the
 * links.
 *
 * <p>Bind parameters of an instance in {@code full} mode (or an unknown mode: fail closed, like
 * ADR-0010 C-148) are shown as they are only to an administrator with {@code pii.view}, and that
 * view is audited ({@code SENSITIVE_VIEW}, type {@code sql_params_full}, 16 §6.7, §11.1); everyone
 * else gets their type and length with {@code params_mode: masked_by_role} ({@link BindParams}).
 * Until the encrypted column exists (WO-71) the server stores no {@code full} values at all
 * (C-148), so such steps usually have no parameters.
 */
@Component
public class TransactionDetailService {

  /** Steps returned at most (the agent caps a profile at 1,000 steps plus summaries). */
  static final int MAX_STEPS = 5_000;

  /** Frames of a STACK or ERROR step (11: at most 64). */
  static final int MAX_FRAMES = 64;

  /** After this time without a step a captured profile is lost (ingest allowance, 14 §3). */
  static final Duration INGEST_ALLOWANCE = Duration.ofMinutes(2);

  /** The window of the caller lookup before the callee's start. */
  static final Duration CALLER_WINDOW = Duration.ofHours(1);

  private static final String[] STEP_TYPES = {
    "",
    "SQL",
    "DBC",
    "COMMIT",
    "ROLLBACK",
    "HTTP",
    "METHOD",
    "ASYNC",
    "ERROR",
    "STACK",
    "MESSAGE",
    "SUMMARY"
  };

  private static final String[] FAILURES = {
    null, "timeout", "connect_fail", "http_5xx", "other_error"
  };

  private final QueryExecutor executor;
  private final DictionaryNames names;
  private final AppDirectory directory;
  private final AuditService audit;
  private final QuerySources sources;
  private final String logLinkTemplate;

  public TransactionDetailService(
      QueryExecutor executor,
      DictionaryNames names,
      AppDirectory directory,
      AuditService audit,
      QuerySources sources,
      ArgosProperties properties) {
    this.executor = executor;
    this.names = names;
    this.directory = directory;
    this.audit = audit;
    this.sources = sources;
    this.logLinkTemplate = properties.ui() == null ? null : properties.ui().logLinkTemplate();
  }

  /**
   * The detail of a found transaction; records {@code SENSITIVE_VIEW} when {@code full} parameters
   * are shown.
   *
   * @param found the lookup result (the first row is shown, the others are duplicates)
   * @param principal the requesting user, or null
   * @param request the request (audit actor), or null
   */
  public TransactionDetail detail(
      TransactionLookup.Found found, ArgosPrincipal principal, HttpServletRequest request) {
    QueryRow x = found.first();
    int appId = (int) x.getLong("app_id");
    int instanceId = (int) x.getLong("instance_id");
    long txId = x.getLong("tx_id");
    Instant ts = Instant.ofEpochMilli(x.getLong("ts_ms"));
    long elapsed = x.getLong("elapsed_ms");
    boolean captured = x.getBoolean("profile_captured");

    AppDirectory.AppInfo app = directory.app(appId).orElse(null);
    AppDirectory.InstanceInfo instance = directory.instances(List.of(instanceId)).get(instanceId);

    List<QueryRow> stepRows = captured ? steps(appId, txId, ts) : List.of();
    Names n = names(appId, x, stepRows);

    boolean mayViewFull =
        principal != null
            && principal.hasRole(Role.ADMIN)
            && principal.hasPermission(ArgosPrincipal.PII_VIEW);
    String paramMode = instance == null ? null : instance.sqlParamMode();
    boolean fullMode = paramMode == null || !Set.of("masked", "partial").contains(paramMode);

    List<TransactionDetail.Step> steps = new ArrayList<>();
    long rootTime = 0;
    boolean fullShown = false;
    boolean stepTruncated = false;
    Long totalSteps = null;
    int count = 0;
    for (QueryRow s : stepRows) {
      if (++count > MAX_STEPS) {
        stepTruncated = true;
        break;
      }
      stepTruncated |= s.getBoolean("truncated");
      long total = s.getLong("total_steps");
      if (total > 0) {
        totalSteps = total;
      }
      int typeNo = typeNumber(s.getString("type"));
      String detail = s.getString("detail");
      String params = null;
      String stepParamsMode = null;
      if (typeNo == 1) {
        stepParamsMode = paramMode == null ? "full" : paramMode;
        if (detail != null && !detail.isEmpty()) {
          if (fullMode && !mayViewFull) {
            params = BindParams.mask(detail);
            stepParamsMode = "masked_by_role";
          } else {
            params = detail;
            fullShown |= fullMode;
          }
        } else if (fullMode && !mayViewFull) {
          stepParamsMode = "masked_by_role";
        }
      }
      TransactionDetail.Step step = step(s, typeNo, n, params, stepParamsMode);
      if (step.parentSeq() == 0 && typeNo != 11 && typeNo != 9 && typeNo != 8) {
        rootTime += step.elapsedMs();
      }
      steps.add(step);
    }

    Instant now = sources.now();
    Instant end = ts.plusMillis(elapsed);
    boolean expired = ts.isBefore(now.minus(sources.profileRetention()));
    String state;
    boolean pending = false;
    if (!captured) {
      state = "sampled_out";
    } else if (!steps.isEmpty()) {
      state = "captured";
    } else if (expired || end.isBefore(now.minus(INGEST_ALLOWANCE))) {
      state = "lost";
    } else {
      state = "captured";
      pending = true;
    }
    Long selfTime = steps.isEmpty() ? null : Math.max(0, elapsed - rootTime);
    Double selfRatio =
        selfTime == null || elapsed <= 0 ? null : Math.min(1.0, (double) selfTime / elapsed);
    TransactionDetail.Profile profile =
        new TransactionDetail.Profile(
            state,
            pending,
            captured && expired && steps.isEmpty(),
            steps,
            selfTime,
            selfRatio,
            stepTruncated,
            totalSteps,
            paramMode);

    if (fullShown && principal != null) {
      Map<String, Object> after = new LinkedHashMap<>();
      after.put("type", "sql_params_full");
      after.put("tx_id", Ids.text(txId));
      after.put("app_id", appId);
      audit.record(
          AuditEvent.SENSITIVE_VIEW,
          Actor.user(principal, request),
          new Target("transaction", Ids.text(txId), null),
          AuditResult.success(),
          null,
          after);
    }

    String traceHex = x.getString("trace_hex");
    boolean hasTrace = traceHex != null && !traceHex.chars().allMatch(c -> c == '0');
    int callerApp = (int) x.getLong("caller_app_id");
    TransactionDetail.Caller caller =
        callerApp == 0 ? null : caller(callerApp, hasTrace ? traceHex : null, ts);

    long cpu = x.getLong("cpu_time_ms");
    long alloc = x.getLong("alloc_bytes");
    long flags = x.getLong("flags");
    String url = x.getString("url_path");
    List<TransactionDetail.Duplicate> duplicates = new ArrayList<>();
    for (int i = 1; i < found.rows().size(); i++) {
      QueryRow d = found.rows().get(i);
      duplicates.add(
          new TransactionDetail.Duplicate(
              (int) d.getLong("app_id"),
              (int) d.getLong("instance_id"),
              Instant.ofEpochMilli(d.getLong("ts_ms"))));
    }
    return new TransactionDetail(
        Ids.text(txId),
        hasTrace ? traceHex : null,
        Ids.spanHex(x.getLong("span_id")),
        Ids.spanHex(x.getLong("parent_span_id")),
        new TransactionDetail.AppRef(
            appId, app == null ? null : app.name(), app == null ? x.getString("env") : app.env()),
        new TransactionDetail.InstanceRef(instanceId, instance == null ? null : instance.name()),
        x.getString("kind"),
        n.name(Kind.SERVICE, x.getLong("service_id")),
        Ids.keyOrNull(x.getLong("service_id")),
        url == null || url.isEmpty() ? null : UntrustedStrings.name(url).value(),
        x.getString("http_method"),
        (int) x.getLong("http_status"),
        ts,
        elapsed,
        x.getBoolean("error"),
        n.name(Kind.CLASS, x.getLong("error_class_id")),
        Ids.keyOrNull(x.getLong("error_class_id")),
        Ids.keyOrNull(x.getLong("error_group_id")),
        n.name(Kind.APP_VERSION, x.getLong("app_version_id")),
        n.name(Kind.THREAD, x.getLong("thread_name_id")),
        cpu < 0 ? null : cpu,
        alloc < 0 ? null : alloc,
        new TransactionDetail.Measured(cpu >= 0, alloc >= 0),
        new TransactionDetail.SqlTotals(
            x.getLong("sql_count"), x.getLong("sql_time_ms"), x.getLong("dbc_wait_ms")),
        new TransactionDetail.ExtTotals(x.getLong("ext_count"), x.getLong("ext_time_ms")),
        RemoteIps.display(x.getString("remote_ip_text")),
        x.getString("ip_mode"),
        emptyToNull(x.getString("user_id")),
        x.getString("user_id_mode"),
        emptyToNull(x.getString("request_id")),
        headers(x.getMap("headers")),
        caller,
        profile,
        new TransactionDetail.Flags(
            x.getBoolean("streaming"),
            x.getBoolean("timed_out"),
            x.getBoolean("late"),
            (flags & 1) != 0,
            (flags & 2) != 0,
            (flags & 4) != 0,
            x.getBoolean("incoming_trace"),
            (int) x.getLong("dispatch_count")),
        new TransactionDetail.Links(
            hasTrace ? "/traces/" + traceHex + "?ts=" + ts : null,
            LogLinks.transaction(
                logLinkTemplate,
                hasTrace ? traceHex : null,
                Ids.text(txId),
                ts,
                elapsed,
                app == null ? null : app.name(),
                instance == null ? null : instance.name())),
        found.path().value(),
        duplicates);
  }

  private List<QueryRow> steps(int appId, long txId, Instant ts) {
    return executor
        .query(
            "transactions/steps",
            QueryParams.create()
                .uint32("app", appId)
                .uint64("tx", txId)
                .dateTime64("from", ts.minus(Duration.ofDays(1)))
                .dateTime64("to", ts.plus(Duration.ofDays(1)))
                .uint32("limit", MAX_STEPS + 1),
            QueryOptions.defaults().withMaxResultRows(MAX_STEPS + 1))
        .rows();
  }

  private TransactionDetail.Caller caller(int callerApp, String traceHex, Instant calleeStart) {
    String appName = directory.app(callerApp).map(AppDirectory.AppInfo::name).orElse(null);
    if (traceHex == null) {
      return new TransactionDetail.Caller(callerApp, appName, null, null, null, null);
    }
    QueryRow best = null;
    long calleeMs = calleeStart.toEpochMilli();
    for (QueryRow r :
        executor.query(
            "transactions/caller",
            QueryParams.create()
                .uint32("caller", callerApp)
                .dateTime64("from", calleeStart.minus(CALLER_WINDOW))
                .dateTime64("to", calleeStart.plusSeconds(1))
                .string("trace", traceHex),
            QueryOptions.defaults().withMaxResultRows(50))) {
      long start = r.getLong("ts_ms");
      long end = start + r.getLong("elapsed_ms");
      // Clocks of two hosts differ: allow a second on both ends; the latest start wins.
      if (start <= calleeMs + 1_000 && end >= calleeMs - 1_000) {
        best = r;
      }
    }
    if (best == null) {
      return new TransactionDetail.Caller(callerApp, appName, null, null, null, null);
    }
    String service =
        UntrustedStrings.name(names.name(callerApp, Kind.SERVICE, best.getLong("service_id")))
            .value();
    return new TransactionDetail.Caller(
        callerApp,
        appName,
        service,
        (int) best.getLong("instance_id"),
        Ids.text(best.getLong("tx_id")),
        Instant.ofEpochMilli(best.getLong("ts_ms")));
  }

  private TransactionDetail.Step step(
      QueryRow s, int typeNo, Names n, String params, String paramsMode) {
    String type = s.getString("type");
    long nameId = s.getLong("name_id");
    long targetId = s.getLong("target_id");
    int status = (int) s.getLong("status");
    boolean summary = typeNo == 11;
    int nameType = summary ? status : typeNo;
    Kind nameKind = nameKind(nameType);
    Kind targetKind = targetKind(typeNo);
    String name = nameKind == null || nameKind == Kind.SQL ? null : n.name(nameKind, nameId);
    String target = targetKind == null ? null : n.name(targetKind, targetId);
    Integer outStatus = null;
    String failure = null;
    if (!summary) {
      if (typeNo == 5 && status >= 100) {
        outStatus = status;
      } else if (status != 0) {
        outStatus = status;
        failure = status < FAILURES.length ? FAILURES[status] : "other_error";
      }
    }
    boolean sqlStep = typeNo == 1 || (summary && status == 1);
    String sqlId = sqlStep ? Ids.keyOrNull(nameId) : null;
    // SQL text: the 64 KB cap of 14 §2.10 (names: 2 KB).
    UntrustedStrings.Text sql =
        sqlStep && nameId != 0 ? UntrustedStrings.sql(n.raw(Kind.SQL, nameId)) : null;
    String detail = s.getString("detail");
    List<String> frames = null;
    if (typeNo == 9 || typeNo == 8) {
      long[] stack = s.getLongArray("stack");
      frames = new ArrayList<>();
      for (int i = 0; i < stack.length && i < MAX_FRAMES; i++) {
        frames.add(n.name(Kind.FRAME, stack[i]));
      }
    }
    return new TransactionDetail.Step(
        (int) s.getLong("seq"),
        (int) s.getLong("parent_seq"),
        type,
        s.getLong("start_offset_ms"),
        s.getLong("elapsed_ms"),
        name,
        summary || !sqlStep ? Ids.keyOrNull(nameId) : null,
        target,
        outStatus,
        failure,
        s.getBoolean("error"),
        (int) Math.max(1, s.getLong("repeat_count")),
        typeNo == 1 ? s.getLong("exec_ms") : null,
        typeNo == 1 ? s.getLong("fetch_ms") : null,
        typeNo == 1 || typeNo == 5 ? s.getLong("step_rows") : null,
        typeNo == 1 && s.getLong("batch_size") > 0 ? s.getLong("batch_size") : null,
        sqlId,
        sql == null ? null : sql.value(),
        sql == null ? null : sql.truncated(),
        params,
        paramsMode,
        (typeNo == 5 || typeNo == 10) && detail != null && !detail.isEmpty()
            ? UntrustedStrings.name(detail).value()
            : null,
        frames,
        summary
            ? new TransactionDetail.Summary(
                status > 0 && status < STEP_TYPES.length ? STEP_TYPES[status] : "UNKNOWN",
                Math.max(1, s.getLong("repeat_count")),
                s.getLong("elapsed_ms"),
                s.getLong("fetch_ms"),
                s.getLong("step_rows"))
            : null);
  }

  /** The C-1 number of a step type name, 0 for an unknown one. */
  static int typeNumber(String type) {
    for (int i = 1; i < STEP_TYPES.length; i++) {
      if (STEP_TYPES[i].equals(type)) {
        return i;
      }
    }
    return 0;
  }

  /** The dictionary of a step's {@code name_id} by step type (proto ProfileStep, C-143 (4)). */
  static Kind nameKind(int type) {
    return switch (type) {
      case 1 -> Kind.SQL;
      case 2 -> Kind.POOL;
      case 5, 10 -> Kind.HOST;
      case 6, 7 -> Kind.METHOD;
      case 8 -> Kind.CLASS;
      default -> null;
    };
  }

  /** The dictionary of a step's {@code target_id} by step type. */
  static Kind targetKind(int type) {
    return switch (type) {
      case 1, 2, 3, 4 -> Kind.DB_TARGET;
      case 5, 10 -> Kind.HOST;
      default -> null;
    };
  }

  private Names names(int appId, QueryRow x, List<QueryRow> steps) {
    Map<Kind, Set<Long>> keys = new EnumMap<>(Kind.class);
    add(keys, Kind.SERVICE, x.getLong("service_id"));
    add(keys, Kind.CLASS, x.getLong("error_class_id"));
    add(keys, Kind.APP_VERSION, x.getLong("app_version_id"));
    add(keys, Kind.THREAD, x.getLong("thread_name_id"));
    for (QueryRow s : steps) {
      int type = typeNumber(s.getString("type"));
      int nameType = type == 11 ? (int) s.getLong("status") : type;
      Kind nk = nameKind(nameType);
      if (nk != null) {
        add(keys, nk, s.getLong("name_id"));
      }
      Kind tk = targetKind(type);
      if (tk != null) {
        add(keys, tk, s.getLong("target_id"));
      }
      if (type == 8 || type == 9) {
        long[] stack = s.getLongArray("stack");
        for (int i = 0; i < stack.length && i < MAX_FRAMES; i++) {
          add(keys, Kind.FRAME, stack[i]);
        }
      }
    }
    Map<Kind, Map<Long, String>> resolved = new EnumMap<>(Kind.class);
    keys.forEach((kind, ids) -> resolved.put(kind, names.names(appId, kind, ids)));
    return new Names(resolved);
  }

  private static void add(Map<Kind, Set<Long>> keys, Kind kind, long id) {
    if (id != 0) {
      keys.computeIfAbsent(kind, k -> new HashSet<>()).add(id);
    }
  }

  private static Map<String, String> headers(Map<String, Object> stored) {
    Map<String, String> out = new TreeMap<>();
    stored.forEach(
        (k, v) ->
            out.put(
                UntrustedStrings.name(k).value(),
                v == null ? null : UntrustedStrings.name(String.valueOf(v)).value()));
    return out;
  }

  private static String emptyToNull(String value) {
    return value == null || value.isEmpty() ? null : value;
  }

  /** Resolved names by kind; the 2 KB name cap of 14 §2.10 applied on read. */
  private record Names(Map<Kind, Map<Long, String>> byKind) {

    String raw(Kind kind, long id) {
      if (id == 0) {
        return null;
      }
      Map<Long, String> m = byKind.get(kind);
      String text = m == null ? null : m.get(id);
      return text == null ? DictionaryNames.unknown(id) : text;
    }

    String name(Kind kind, long id) {
      String raw = raw(kind, id);
      return raw == null ? null : UntrustedStrings.name(raw).value();
    }
  }
}
