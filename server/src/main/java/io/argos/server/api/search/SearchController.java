/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.api.search;

import io.argos.server.api.error.ApiException;
import io.argos.server.api.error.ErrorCode;
import io.argos.server.api.openapi.ArgosLimits;
import io.argos.server.api.openapi.ArgosSource;
import io.argos.server.api.query.QueryLimits;
import io.argos.server.api.query.QuerySources;
import io.argos.server.api.query.ResponseEnvelope;
import io.argos.server.api.query.Source;
import io.argos.server.api.query.UntrustedStrings;
import io.argos.server.api.traces.TraceService;
import io.argos.server.api.traces.TraceView;
import io.argos.server.api.transactions.Ids;
import io.argos.server.api.transactions.TransactionLookup;
import io.argos.server.auth.AppAccessPolicy;
import io.argos.server.auth.ArgosPrincipal;
import io.argos.server.auth.RequiresRole;
import io.argos.server.auth.Role;
import io.argos.server.storage.query.DictionaryNames;
import io.argos.server.storage.query.DictionaryNames.Kind;
import io.argos.server.storage.query.QueryExecutor;
import io.argos.server.storage.query.QueryOptions;
import io.argos.server.storage.query.QueryParams;
import io.argos.server.storage.query.QueryRow;
import io.argos.server.storage.query.lookup.AppDirectory;
import io.argos.server.storage.query.lookup.DictionaryTextSearch;
import io.argos.server.web.security.RateLimitBucket;
import io.argos.server.web.security.RateLimited;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The global search (14 §4.11, F-U14): {@code type=auto} reads 19 or 20 digits as a transaction id,
 * 32 hex digits as a trace id and anything else as text. Id lookups reuse the transaction lookup
 * (ADR-0007 C-50) and the trace lookup; user and request id searches need {@code from} and {@code
 * to} (at most 24 h; N-24); text searches the dictionary through {@code pg_trgm}. The whole
 * endpoint is in the heavy bucket (16 §7.13 "검색(ID 조회·텍스트 검색)", ADR-0010 C-136 (2)).
 */
@RestController
@RequiresRole(Role.VIEWER)
public class SearchController {

  /** Matches of one search. */
  public static final int MAX_MATCHES = 100;

  /**
   * The digits that {@code type=auto} reads as a transaction id. 14 §4.11 says 19 to 20, which is
   * the length of an id over the Unix epoch; the id of 13 §2 / 10 §9.5 counts from 2026-01-01, so
   * an id of 2026 has 15 to 18 digits (19 from about 2029 on). Auto detection therefore takes 15 to
   * 20 digits whose time ({@link Ids#restoredTime}) is not in the future ({@link #looksLikeTxId});
   * {@code type=tx} takes any unsigned 64-bit decimal.
   */
  private static final Pattern TX_AUTO = Pattern.compile("[0-9]{15,20}");

  private static final Pattern TX = Pattern.compile("[0-9]{1,20}");
  private static final Set<String> TYPES =
      Set.of("auto", "tx", "trace", "user", "request", "service", "sql", "instance");

  private final TransactionLookup transactions;
  private final TraceService traces;
  private final QueryExecutor executor;
  private final DictionaryNames names;
  private final DictionaryTextSearch text;
  private final AppDirectory directory;
  private final AppAccessPolicy access;
  private final QuerySources sources;

  public SearchController(
      TransactionLookup transactions,
      TraceService traces,
      QueryExecutor executor,
      DictionaryNames names,
      DictionaryTextSearch text,
      AppDirectory directory,
      AppAccessPolicy access,
      QuerySources sources) {
    this.transactions = transactions;
    this.traces = traces;
    this.executor = executor;
    this.names = names;
    this.text = text;
    this.directory = directory;
    this.access = access;
    this.sources = sources;
  }

  /** The global search (14 §4.11). */
  @GetMapping(value = "/api/v1/search", produces = MediaType.APPLICATION_JSON_VALUE)
  @Operation(
      operationId = "search_find",
      summary = "Find transactions, traces, services, SQL and instances",
      description =
          "type=auto: 15-20 digits whose time is not in the future are a transaction id (an id"
              + " counts from 2026-01-01: 18 digits in 2026), 32 hex digits a trace id, anything"
              + " else text (services, SQL, instances; at least 3 characters). type=tx takes any"
              + " unsigned 64-bit decimal. user and request"
              + " searches need from and to (at most 24 h); user ids match applications in plain"
              + " mode (an application in hashed mode answers 422 user_id_hash_unavailable). q is"
              + " at most 1 KB. Heavy bucket: 10 a minute.")
  @RateLimited(RateLimitBucket.HEAVY)
  @ArgosLimits(rangeMax = "24h", rowsMax = MAX_MATCHES, timeoutS = 10)
  @ArgosSource({"xlog", "postgres:dictionary", "postgres:instances"})
  public ResponseEnvelope<SearchResult> search(
      @Parameter(description = "The searched text, at most 1 KB") @RequestParam("q") String q,
      @Parameter(
              description = "auto (default), tx, trace, user, request, service, sql, instance",
              schema =
                  @Schema(
                      allowableValues = {
                        "auto",
                        "tx",
                        "trace",
                        "user",
                        "request",
                        "service",
                        "sql",
                        "instance"
                      }))
          @RequestParam(value = "type", required = false)
          String type,
      @Parameter(description = "Restrict to an application")
          @RequestParam(value = "app", required = false)
          Integer app,
      @Parameter(description = "Restrict to an environment")
          @RequestParam(value = "env", required = false)
          String env,
      @RequestParam(value = "from", required = false) String from,
      @RequestParam(value = "to", required = false) String to) {
    ArgosPrincipal principal = ArgosPrincipal.current().orElse(null);
    String query = QueryLimits.text("q", q);
    if (query == null || query.isBlank()) {
      throw new ApiException(ErrorCode.FILTER_INVALID).detail("q", "missing");
    }
    query = query.strip();
    String t = type == null || type.isBlank() ? "auto" : type.strip().toLowerCase(Locale.ROOT);
    if (!TYPES.contains(t)) {
      throw new ApiException(ErrorCode.FILTER_INVALID).detail("type", "unsupported_value");
    }
    int appId = app == null ? 0 : app;
    if (appId < 0) {
      throw new ApiException(ErrorCode.INVALID_REQUEST).detail("app", "invalid_value");
    }
    if (appId > 0) {
      access.check(principal, appId);
    }
    String environment = env == null ? "" : QueryLimits.text("env", env).strip();
    if (environment.length() > 32) {
      throw new ApiException(ErrorCode.FILTER_INVALID).detail("env", "too_long");
    }
    if (t.equals("auto")) {
      t = looksLikeTxId(query, sources.now()) ? "tx" : Ids.isTraceId(query) ? "trace" : "text";
    }
    List<SearchResult.Match> matches =
        switch (t) {
          case "tx" -> tx(query, appId);
          case "trace" -> trace(query);
          case "user", "request" -> byValue(t, query, appId, environment, from, to);
          case "service" -> dictionary(query, List.of(Kind.SERVICE), appId, environment);
          case "sql" -> dictionary(query, List.of(Kind.SQL), appId, environment);
          case "instance" -> instances(query, appId, environment);
          default -> text(query, appId, environment);
        };
    ResponseEnvelope.Builder<SearchResult> envelope =
        ResponseEnvelope.builder(new SearchResult(t, matches));
    if (READS_RAW.contains(t)) {
      // Id, user and request searches read raw xlog rows (14 §2.5 source).
      envelope.source(Source.RAW);
    }
    return envelope.build();
  }

  /** The resolved types whose matches come from raw {@code xlog} rows. */
  private static final Set<String> READS_RAW = Set.of("tx", "trace", "user", "request");

  /**
   * Whether {@code type=auto} reads {@code query} as a transaction id: 15 to 20 digits, an unsigned
   * 64-bit value, and a time in the id ({@code tx_id >> 23} + 2026-01-01) no later than a day after
   * {@code now} (see {@link #TX_AUTO}).
   */
  static boolean looksLikeTxId(String query, Instant now) {
    if (!TX_AUTO.matcher(query).matches()) {
      return false;
    }
    long id;
    try {
      id = Long.parseUnsignedLong(query);
    } catch (NumberFormatException e) {
      return false;
    }
    return !Ids.restoredTime(id).isAfter(now.plus(Duration.ofDays(1)));
  }

  private List<SearchResult.Match> tx(String query, int appId) {
    if (!TX.matcher(query).matches()) {
      throw new ApiException(ErrorCode.FILTER_INVALID).detail("q", "invalid_format");
    }
    long id;
    try {
      id = Long.parseUnsignedLong(query);
    } catch (NumberFormatException e) {
      return List.of();
    }
    Optional<TransactionLookup.Found> found = transactions.tryFind(id, null, appId);
    if (found.isEmpty()) {
      return List.of();
    }
    QueryRow r = found.get().first();
    int app = (int) r.getLong("app_id");
    if (appId == 0) {
      // The found application (an app restriction was checked by search()). In 1.0 every user
      // sees every application, so this changes nothing yet; 1.x permissions rely on it (SEC-50).
      access.check(ArgosPrincipal.current().orElse(null), app);
    }
    Instant ts = Instant.ofEpochMilli(r.getLong("ts_ms"));
    String service = names.name(app, Kind.SERVICE, r.getLong("service_id"));
    Map<String, String> params = new LinkedHashMap<>();
    params.put("txId", Ids.text(id));
    params.put("ts", ts.toString());
    return List.of(
        new SearchResult.Match(
            "tx",
            label(service, r.getLong("elapsed_ms") + " ms", ts.toString()),
            new SearchResult.Target("/tx/:txId", params),
            1.0));
  }

  private List<SearchResult.Match> trace(String query) {
    String trace = Ids.traceId("q", query);
    Instant now = sources.now().truncatedTo(ChronoUnit.MILLIS);
    Instant t0 =
        Ids.traceTimeHint(trace, now.minus(Duration.ofDays(31)), now.plus(Duration.ofDays(1)));
    List<QueryRow> rows = List.of();
    if (t0 != null) {
      rows =
          traces.nodes(
              trace,
              new TraceView.Window(
                  t0.minus(Duration.ofHours(1)), t0.plus(Duration.ofHours(1)), "id_hint"));
    }
    if (rows.isEmpty()) {
      rows =
          traces.nodes(
              trace, new TraceView.Window(now.minus(Duration.ofHours(24)), now, "default"));
    }
    if (rows.isEmpty()) {
      return List.of();
    }
    // Every application of the trace, as traces_get checks its nodes. In 1.0 every user sees every
    // application, so this changes nothing yet; 1.x permissions rely on it (SEC-50).
    ArgosPrincipal principal = ArgosPrincipal.current().orElse(null);
    Set<Integer> apps = new LinkedHashSet<>();
    for (QueryRow node : rows) {
      apps.add((int) node.getLong("app_id"));
    }
    for (int a : apps) {
      access.check(principal, a);
    }
    QueryRow root = rows.get(0);
    int app = (int) root.getLong("app_id");
    Instant ts = Instant.ofEpochMilli(root.getLong("ts_ms"));
    String service = names.name(app, Kind.SERVICE, root.getLong("service_id"));
    Map<String, String> params = new LinkedHashMap<>();
    params.put("traceId", trace);
    params.put("ts", ts.toString());
    String count =
        rows.size() > TraceService.MAX_NODES
            ? TraceService.MAX_NODES + "+ transactions"
            : rows.size() + (rows.size() == 1 ? " transaction" : " transactions");
    return List.of(
        new SearchResult.Match(
            "trace",
            label(service, count, ts.toString()),
            new SearchResult.Target("/traces/:traceId", params),
            1.0));
  }

  private List<SearchResult.Match> byValue(
      String type, String query, int appId, String env, String from, String to) {
    Instant f = QueryLimits.time("from", from);
    Instant t = QueryLimits.time("to", to);
    QueryLimits.range(f, t, QueryLimits.RAW_RANGE_MAX);
    String uid = "";
    String rid = "";
    if (type.equals("user")) {
      String normalized = QueryLimits.userIdFilter(query);
      if (normalized == null) {
        throw new ApiException(ErrorCode.FILTER_INVALID).detail("q", "empty");
      }
      if (appId > 0) {
        Set<String> modes = directory.userIdModes(appId);
        if (!modes.contains("plain") && modes.contains("hashed")) {
          throw new ApiException(
                  ErrorCode.FILTER_UNPROCESSABLE,
                  "The application stores hashed user ids; searching them needs the application"
                      + " salt")
              .detail("user_id", "user_id_hash_unavailable");
        }
      }
      uid = normalized;
    } else {
      rid = query;
    }
    List<QueryRow> rows =
        executor
            .query(
                "search/by_value",
                QueryParams.create()
                    .uint32("app", appId)
                    .dateTime64("from", f)
                    .dateTime64("to", t)
                    .string("env", env)
                    .string("uid", uid)
                    .string("rid", rid)
                    .uint32("limit", MAX_MATCHES),
                QueryOptions.defaults().withMaxResultRows(MAX_MATCHES))
            .rows();
    Map<Integer, Set<Long>> services = new LinkedHashMap<>();
    for (QueryRow r : rows) {
      services
          .computeIfAbsent((int) r.getLong("app_id"), k -> new LinkedHashSet<>())
          .add(r.getLong("service_id"));
    }
    Map<Integer, Map<Long, String>> serviceNames = new LinkedHashMap<>();
    services.forEach((a, ids) -> serviceNames.put(a, names.names(a, Kind.SERVICE, ids)));
    List<SearchResult.Match> out = new ArrayList<>(rows.size());
    for (QueryRow r : rows) {
      int a = (int) r.getLong("app_id");
      if (appId == 0) {
        access.check(ArgosPrincipal.current().orElse(null), a);
      }
      Instant ts = Instant.ofEpochMilli(r.getLong("ts_ms"));
      Map<String, String> params = new LinkedHashMap<>();
      params.put("txId", Ids.text(r.getLong("tx_id")));
      params.put("ts", ts.toString());
      out.add(
          new SearchResult.Match(
              "tx",
              label(
                  serviceNames.getOrDefault(a, Map.of()).get(r.getLong("service_id")),
                  r.getLong("elapsed_ms") + " ms" + (r.getBoolean("error") ? " error" : ""),
                  ts.toString()),
              new SearchResult.Target("/tx/:txId", params),
              1.0));
    }
    return out;
  }

  private List<SearchResult.Match> dictionary(
      String query, List<Kind> kinds, int appId, String env) {
    List<SearchResult.Match> out = new ArrayList<>();
    for (DictionaryTextSearch.Hit h : text.search(query, kinds, appId, env, MAX_MATCHES)) {
      Map<String, String> params = new LinkedHashMap<>();
      params.put("appId", Integer.toString(h.appId()));
      boolean sql = h.kind() == Kind.SQL;
      params.put(sql ? "sqlId" : "serviceId", Ids.text(h.hash()));
      out.add(
          new SearchResult.Match(
              sql ? "sql" : "service",
              UntrustedStrings.name(h.text()).value(),
              new SearchResult.Target(
                  sql ? "/apps/:appId/sql/:sqlId" : "/apps/:appId/services/:serviceId", params),
              Math.max(0, Math.min(1, h.score()))));
    }
    return out;
  }

  private List<SearchResult.Match> instances(String query, int appId, String env) {
    List<SearchResult.Match> out = new ArrayList<>();
    if (query.codePointCount(0, query.length()) < DictionaryTextSearch.MIN_CHARS) {
      return out;
    }
    for (AppDirectory.InstanceInfo i : directory.searchInstances(query, appId, env, MAX_MATCHES)) {
      Map<String, String> params = new LinkedHashMap<>();
      params.put("instanceId", Integer.toString(i.instanceId()));
      out.add(
          new SearchResult.Match(
              "instance",
              UntrustedStrings.name(i.name()).value(),
              new SearchResult.Target("/instances/:instanceId", params),
              i.name().equalsIgnoreCase(query) ? 1.0 : 0.5));
    }
    return out;
  }

  private List<SearchResult.Match> text(String query, int appId, String env) {
    List<SearchResult.Match> all = new ArrayList<>();
    all.addAll(dictionary(query, List.of(Kind.SERVICE, Kind.SQL), appId, env));
    all.addAll(instances(query, appId, env));
    all.sort(Comparator.comparingDouble(SearchResult.Match::score).reversed());
    return all.size() > MAX_MATCHES ? List.copyOf(all.subList(0, MAX_MATCHES)) : all;
  }

  private static String label(String name, String what, String when) {
    String n = name == null ? "" : UntrustedStrings.name(name).value() + " · ";
    return n + what + " · " + when;
  }
}
