/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.api.traces;

import io.argos.server.api.error.ApiException;
import io.argos.server.api.error.ErrorCode;
import io.argos.server.api.query.QuerySources;
import io.argos.server.api.query.UntrustedStrings;
import io.argos.server.api.transactions.Ids;
import io.argos.server.config.ArgosProperties;
import io.argos.server.storage.query.DictionaryNames;
import io.argos.server.storage.query.DictionaryNames.Kind;
import io.argos.server.storage.query.QueryExecutor;
import io.argos.server.storage.query.QueryOptions;
import io.argos.server.storage.query.QueryParams;
import io.argos.server.storage.query.QueryRow;
import io.argos.server.storage.query.lookup.AppDirectory;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * Reads a trace (14 §4.7, 13 §6.3 (6), Q06). The searched range (DSN-14-07):
 *
 * <ul>
 *   <li>{@code window} given ({@code 1h}, {@code 24h}, {@code 7d}, {@code 30d}): that long until
 *       now ({@code 30d} is a heavy request, charged by the controller);
 *   <li>{@code ?ts} given: +-1 h around it;
 *   <li>an Argos-generated id (Unix seconds in its first 4 bytes, plausible within the raw
 *       retention): +-1 h around that time, then the 24 h default when nothing is there (an
 *       external id can look like one);
 *   <li>otherwise the last 24 hours.
 * </ul>
 *
 * At most {@value #MAX_NODES} transactions ({@code truncated}). A callee's caller is the caller
 * application's transaction covering its start ({@code caller_app_id}, sent only to hosts in {@code
 * propagation.argos-state.hosts}) or, without one, the transaction whose {@code span_id} is the
 * callee's {@code parent_span_id} (the default configuration, ADR-0010 C-223 (1)).
 */
@Component
public class TraceService {

  /** Most transactions of a trace (14 §4.7). */
  public static final int MAX_NODES = 500;

  /** Most outgoing call steps read. */
  static final int MAX_STEPS = 20_000;

  static final Duration HINT = Duration.ofHours(1);
  static final Duration DEFAULT_WINDOW = Duration.ofHours(24);
  static final Duration TOLERANCE = Duration.ofSeconds(1);
  static final Duration NOT_YET_COLLECTED = Duration.ofMinutes(2);

  /** The windows of the {@code window} parameter. */
  static final Map<String, Duration> WINDOWS =
      Map.of(
          "1h", Duration.ofHours(1),
          "24h", Duration.ofHours(24),
          "7d", Duration.ofDays(7),
          "30d", Duration.ofDays(30));

  private final QueryExecutor executor;
  private final DictionaryNames names;
  private final AppDirectory directory;
  private final QuerySources sources;
  private final ArgosProperties.Retention retention;

  public TraceService(
      QueryExecutor executor,
      DictionaryNames names,
      AppDirectory directory,
      QuerySources sources,
      ArgosProperties properties) {
    this.executor = executor;
    this.names = names;
    this.directory = directory;
    this.sources = sources;
    this.retention = properties.retention();
  }

  /**
   * A trace, whether its transactions were cut at {@value #MAX_NODES} and whether the searched
   * range reaches past the raw retention.
   *
   * @param view the trace
   * @param truncated more transactions than shown
   * @param retentionExceeded the searched range starts before {@code now - argos.retention.xlog}
   *     (D-03, DSN-14-04): older transactions of the trace may have been deleted
   */
  public record Result(TraceView view, boolean truncated, boolean retentionExceeded) {}

  /** The duration of a {@code window} value; 400 for an unknown one. */
  public static Duration window(String value) {
    Duration d = WINDOWS.get(value);
    if (d == null) {
      throw new ApiException(ErrorCode.INVALID_REQUEST).detail("window", "unsupported_value");
    }
    return d;
  }

  /**
   * Reads a trace.
   *
   * @param traceHex 32 lower-case hex digits
   * @param tsHint {@code ?ts}, or null
   * @param window the {@code window} parameter, or null
   * @throws ApiException 404 {@code not_found} ({@code trace_id/not_in_window}) when the range
   *     holds no transaction of the trace
   */
  public Result trace(String traceHex, Instant tsHint, String window) {
    Instant now = sources.now().truncatedTo(ChronoUnit.MILLIS);
    List<QueryRow> rows;
    TraceView.Window searched;
    if (window != null) {
      searched = new TraceView.Window(now.minus(window(window)), now, "window");
      rows = nodes(traceHex, searched);
    } else if (tsHint != null) {
      searched = new TraceView.Window(tsHint.minus(HINT), tsHint.plus(HINT), "ts_hint");
      rows = nodes(traceHex, searched);
    } else {
      Instant t0 =
          Ids.traceTimeHint(traceHex, now.minus(Duration.ofDays(31)), now.plus(Duration.ofDays(1)));
      if (t0 != null) {
        searched = new TraceView.Window(t0.minus(HINT), t0.plus(HINT), "id_hint");
        rows = nodes(traceHex, searched);
        if (rows.isEmpty()) {
          searched = new TraceView.Window(now.minus(DEFAULT_WINDOW), now, "id_hint_then_default");
          rows = nodes(traceHex, searched);
        }
      } else {
        searched = new TraceView.Window(now.minus(DEFAULT_WINDOW), now, "default");
        rows = nodes(traceHex, searched);
      }
    }
    if (rows.isEmpty()) {
      throw new ApiException(
              ErrorCode.NOT_FOUND,
              "No transaction of this trace in the searched range; widen it with window or ?ts")
          .detail("trace_id", "not_in_window");
    }
    boolean truncated = rows.size() > MAX_NODES;
    if (truncated) {
      rows = rows.subList(0, MAX_NODES);
    }
    // The same rule as the XLog and the transaction search: the raw xlog retention.
    boolean exceeded = searched.from().isBefore(sources.now().minus(retention.xlog()));
    return new Result(build(traceHex, rows, searched, now), truncated, exceeded);
  }

  /**
   * The transactions of a trace in a range, at most {@value #MAX_NODES} + 1 (global search too).
   */
  public List<QueryRow> nodes(String traceHex, TraceView.Window window) {
    return executor
        .query(
            "traces/nodes",
            QueryParams.create()
                .dateTime64("from", window.from())
                .dateTime64("to", window.to())
                .string("trace", traceHex)
                .uint32("limit", MAX_NODES + 1),
            QueryOptions.defaults().withMaxResultRows(MAX_NODES + 1))
        .rows();
  }

  /** A transaction of the trace as read. */
  private record Tx(
      QueryRow row, String txId, int appId, long start, long end, int callerApp, int index) {}

  /** An outgoing HTTP call of a transaction. */
  private record Call(int appId, long callerTx, long start, long end, QueryRow row) {}

  private TraceView build(
      String traceHex, List<QueryRow> rows, TraceView.Window window, Instant now) {
    List<Tx> txs = new ArrayList<>(rows.size());
    Set<Integer> apps = new LinkedHashSet<>();
    Set<Long> txIds = new LinkedHashSet<>();
    for (QueryRow r : rows) {
      long start = r.getLong("ts_ms");
      int appId = (int) r.getLong("app_id");
      txs.add(
          new Tx(
              r,
              Ids.text(r.getLong("tx_id")),
              appId,
              start,
              start + r.getLong("elapsed_ms"),
              (int) r.getLong("caller_app_id"),
              txs.size()));
      apps.add(appId);
      txIds.add(r.getLong("tx_id"));
    }
    Map<Long, List<Call>> callsByTx = new HashMap<>();
    Map<Long, Long> txStart = new HashMap<>();
    for (Tx t : txs) {
      txStart.put(t.row().getLong("tx_id"), t.start());
    }
    for (QueryRow s :
        executor.query(
            "traces/http_steps",
            QueryParams.create()
                .int64Array("apps", apps.stream().mapToLong(Integer::longValue).toArray())
                .uint64Array("txs", List.copyOf(txIds))
                .dateTime64("from", window.from().minus(Duration.ofDays(1)))
                .dateTime64("to", window.to().plus(Duration.ofDays(1)))
                .uint32("limit", MAX_STEPS),
            QueryOptions.defaults().withMaxResultRows(MAX_STEPS))) {
      long tx = s.getLong("tx_id");
      Long base = txStart.get(tx);
      if (base == null) {
        continue;
      }
      long start = base + s.getLong("start_offset_ms");
      callsByTx
          .computeIfAbsent(tx, k -> new ArrayList<>())
          .add(new Call((int) s.getLong("app_id"), tx, start, start + s.getLong("elapsed_ms"), s));
    }

    // Callers: the caller app's transaction whose run covers the callee's start. Without a caller
    // application (the default agent configuration sends no Argos tracestate entry, so
    // caller_app_id is 0), the incoming traceparent's parent-id is the caller transaction's own
    // span id (ADR-0010 C-223 (1)(2)): the transaction of the trace with that span_id.
    long tol = TOLERANCE.toMillis();
    Map<Long, Tx> bySpan = new HashMap<>();
    for (Tx t : txs) {
      long span = t.row().getLong("span_id");
      if (span != 0) {
        bySpan.putIfAbsent(span, t);
      }
    }
    String[] parent = new String[txs.size()];
    TraceView.CallerStep[] steps = new TraceView.CallerStep[txs.size()];
    Set<Call> usedCalls = new HashSet<>();
    List<TraceView.Missing> missing = new ArrayList<>();
    Map<Integer, List<Integer>> children = new HashMap<>();
    for (Tx c : txs) {
      boolean incoming = c.row().getBoolean("incoming_trace");
      Tx best = null;
      if (c.callerApp() == 0) {
        long parentSpan = c.row().getLong("parent_span_id");
        if (!incoming || parentSpan == 0) {
          continue;
        }
        Tx bySpanId = bySpan.get(parentSpan);
        if (bySpanId != null && bySpanId.index() != c.index()) {
          best = bySpanId;
        }
      } else {
        long bestScore = Long.MAX_VALUE;
        for (Tx p : txs) {
          if (p.index() == c.index() || p.appId() != c.callerApp()) {
            continue;
          }
          long score = cover(p.start(), p.end(), c.start(), tol);
          if (score < bestScore) {
            best = p;
            bestScore = score;
          }
        }
      }
      if (best == null) {
        missing.add(new TraceView.Missing(null, c.txId(), reason(c, now)));
        continue;
      }
      parent[c.index()] = best.txId();
      children.computeIfAbsent(best.index(), k -> new ArrayList<>()).add(c.index());
      Call call = null;
      long callScore = Long.MAX_VALUE;
      for (Call k : callsByTx.getOrDefault(best.row().getLong("tx_id"), List.of())) {
        if (usedCalls.contains(k)) {
          continue;
        }
        long score = cover(k.start(), k.end(), c.start(), tol);
        if (score < callScore) {
          call = k;
          callScore = score;
        }
      }
      if (call != null) {
        usedCalls.add(call);
        long callTime = call.end() - call.start();
        steps[c.index()] =
            new TraceView.CallerStep(callTime, Math.max(0, callTime - (c.end() - c.start())));
      }
    }

    // Names, per application (dictionary keys are per application).
    Map<Integer, AppDirectory.AppInfo> appInfo = directory.apps(apps);
    Set<Integer> instanceIds = new LinkedHashSet<>();
    Map<Integer, Set<Long>> services = new HashMap<>();
    Map<Integer, Set<Long>> versions = new HashMap<>();
    Map<Integer, Set<Long>> hosts = new HashMap<>();
    for (Tx t : txs) {
      instanceIds.add((int) t.row().getLong("instance_id"));
      services.computeIfAbsent(t.appId(), k -> new HashSet<>()).add(t.row().getLong("service_id"));
      versions
          .computeIfAbsent(t.appId(), k -> new HashSet<>())
          .add(t.row().getLong("app_version_id"));
    }
    for (List<Call> calls : callsByTx.values()) {
      for (Call k : calls) {
        if (!usedCalls.contains(k)) {
          long host = k.row().getLong("target_id");
          hosts
              .computeIfAbsent(k.appId(), x -> new HashSet<>())
              .add(host != 0 ? host : k.row().getLong("name_id"));
        }
      }
    }
    Map<Integer, AppDirectory.InstanceInfo> instanceInfo = directory.instances(instanceIds);
    Map<Integer, Map<Long, String>> serviceNames = resolve(services, Kind.SERVICE);
    Map<Integer, Map<Long, String>> versionNames = resolve(versions, Kind.APP_VERSION);
    Map<Integer, Map<Long, String>> hostNames = resolve(hosts, Kind.HOST);

    List<TraceView.Node> nodes = new ArrayList<>(txs.size());
    for (Tx t : txs) {
      QueryRow r = t.row();
      long service = r.getLong("service_id");
      long version = r.getLong("app_version_id");
      int instance = (int) r.getLong("instance_id");
      AppDirectory.AppInfo a = appInfo.get(t.appId());
      AppDirectory.InstanceInfo i = instanceInfo.get(instance);
      nodes.add(
          new TraceView.Node(
              t.txId(),
              t.appId(),
              a == null ? null : clean(a.name()),
              clean(r.getString("env")),
              clean(serviceNames.getOrDefault(t.appId(), Map.of()).get(service)),
              Ids.keyOrNull(service),
              instance,
              i == null ? null : clean(i.name()),
              Instant.ofEpochMilli(t.start()),
              r.getLong("elapsed_ms"),
              (int) r.getLong("http_status"),
              r.getBoolean("error"),
              clean(versionNames.getOrDefault(t.appId(), Map.of()).get(version)),
              parent[t.index()],
              steps[t.index()],
              r.getBoolean("profile_captured") ? "captured" : "sampled_out"));
    }

    List<TraceView.ExternalNode> external = new ArrayList<>();
    Map<Long, String> txText = new LinkedHashMap<>();
    for (Tx t : txs) {
      txText.put(t.row().getLong("tx_id"), t.txId());
    }
    List<Call> unused = new ArrayList<>();
    callsByTx.values().forEach(unused::addAll);
    unused.removeIf(usedCalls::contains);
    unused.sort((a, b) -> Long.compare(a.start(), b.start()));
    for (Call k : unused) {
      QueryRow s = k.row();
      long host = s.getLong("target_id") != 0 ? s.getLong("target_id") : s.getLong("name_id");
      int status = (int) s.getLong("status");
      String detail = s.getString("detail");
      external.add(
          new TraceView.ExternalNode(
              host == 0 ? null : clean(hostNames.getOrDefault(k.appId(), Map.of()).get(host)),
              detail == null || detail.isEmpty() ? null : clean(detail),
              k.end() - k.start(),
              status >= 100 ? status : null,
              s.getBoolean("error"),
              txText.get(k.callerTx())));
    }

    int root = -1;
    for (Tx t : txs) {
      if (parent[t.index()] == null) {
        root = t.index();
        break;
      }
    }
    if (root < 0 && !txs.isEmpty()) {
      root = 0;
    }
    List<String> critical = new ArrayList<>();
    Set<Integer> seen = new HashSet<>();
    for (int at = root; at >= 0 && seen.add(at); ) {
      critical.add(txs.get(at).txId());
      int next = -1;
      for (int child : children.getOrDefault(at, List.of())) {
        if (next < 0 || txs.get(child).end() > txs.get(next).end()) {
          next = child;
        }
      }
      at = next;
    }
    return new TraceView(
        traceHex,
        root < 0 ? null : txs.get(root).txId(),
        nodes,
        external,
        missing,
        critical,
        window);
  }

  /**
   * How well a run {@code [start, end]} covers the callee start {@code at}: the time since its
   * start when it contains {@code at} (smaller is closer), a large penalty plus the distance when
   * {@code at} lies within {@code tolerance} outside it (two hosts' clocks), {@link Long#MAX_VALUE}
   * otherwise.
   */
  static long cover(long start, long end, long at, long tolerance) {
    if (start <= at && at <= end) {
      return at - start;
    }
    long distance = at < start ? start - at : at - end;
    return distance <= tolerance ? (1L << 40) + distance : Long.MAX_VALUE;
  }

  private Map<Integer, Map<Long, String>> resolve(Map<Integer, Set<Long>> keys, Kind kind) {
    Map<Integer, Map<Long, String>> out = new HashMap<>();
    keys.forEach((app, ids) -> out.put(app, names.names(app, kind, ids)));
    return out;
  }

  private static String reason(Tx c, Instant now) {
    return now.toEpochMilli() - c.start() < NOT_YET_COLLECTED.toMillis()
        ? "not_yet_collected"
        : "not_instrumented";
  }

  private static String clean(String raw) {
    return raw == null ? null : UntrustedStrings.name(raw).value();
  }
}
