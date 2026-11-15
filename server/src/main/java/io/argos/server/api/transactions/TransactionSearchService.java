/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.api.transactions;

import io.argos.server.api.error.ApiException;
import io.argos.server.api.error.ErrorCode;
import io.argos.server.api.query.CursorCodec;
import io.argos.server.api.query.QueryLimits;
import io.argos.server.api.query.QuerySources;
import io.argos.server.api.query.UntrustedStrings;
import io.argos.server.config.ArgosProperties;
import io.argos.server.storage.query.DictionaryNames;
import io.argos.server.storage.query.DictionaryNames.Kind;
import io.argos.server.storage.query.QueryExecutor;
import io.argos.server.storage.query.QueryOptions;
import io.argos.server.storage.query.QueryParams;
import io.argos.server.storage.query.QueryRow;
import io.argos.server.storage.query.lookup.AppDirectory;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.DateTimeException;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * The transaction search of 14 §4.6 ({@code POST /apps/{appId}/transactions/search}; 13 §6.3 (4),
 * (11), (12)): every filter of the table bound as ClickHouse query parameters, two sort orders with
 * a keyset cursor ({@link CursorCodec}, the scope binds the cursor to the filters), at most 1,000
 * rows a page, ranges up to 24 h ({@code user_id}, {@code request_id} and {@code trace_id} searches
 * default to the last hour), {@code meta.total} from a {@code count()} of the same filters.
 *
 * <p>The default hour of an id search without {@code from}/{@code to} is fixed by its first page:
 * the cursor carries that range (inside the MAC-protected payload, before the keyset values) and
 * the scope digest names the default range instead of the times, so a later page reads the same
 * hour although the clock moved on. A request with explicit {@code from}/{@code to} keeps them in
 * the scope digest; a cursor of the one kind is refused by the other ({@code cursor/invalid}).
 */
@Component
public class TransactionSearchService {

  /** Largest page (14 §4.6). */
  public static final int MAX_LIMIT = 1_000;

  /** Default page. */
  public static final int DEFAULT_LIMIT = 100;

  /** The default range of an id search without {@code from}/{@code to} (14 §4.6, N-24). */
  static final Duration ID_SEARCH_RANGE = Duration.ofHours(1);

  /** The operation id, the scope of its cursors. */
  static final String OPERATION = "transactions_search";

  /**
   * The scope digest's stand-in for the range of a default-range id search (it is in the cursor).
   */
  private static final String DEFAULT_RANGE = "default-range";

  private static final long UINT32_MAX = 0xFFFF_FFFFL;
  private static final Instant CURSOR_TOP = Instant.parse("2100-01-01T00:00:00Z");
  private static final Set<String> SORTS = Set.of("elapsed_ms", "ts");

  private final QueryExecutor executor;
  private final DictionaryNames names;
  private final AppDirectory directory;
  private final CursorCodec cursors;
  private final QuerySources sources;
  private final ArgosProperties.Retention retention;

  public TransactionSearchService(
      QueryExecutor executor,
      DictionaryNames names,
      AppDirectory directory,
      CursorCodec cursors,
      QuerySources sources,
      ArgosProperties properties) {
    this.executor = executor;
    this.names = names;
    this.directory = directory;
    this.cursors = cursors;
    this.sources = sources;
    this.retention = properties.retention();
  }

  /**
   * A validated search.
   *
   * @param appId application
   * @param from range start
   * @param to range end (exclusive)
   * @param kind kind filter
   * @param byElapsed sort {@code elapsed_ms:desc}, else {@code ts:desc}
   * @param limit page size
   * @param params the filter parameters (without range, cursor and limit)
   * @param filterNames the filters that are set (audit)
   * @param scope the cursor scope (operation and a digest of the request)
   * @param cursor the decoded keyset values of the cursor, or null for the first page
   * @param retentionExceeded part of the range is older than the raw retention
   * @param rangeInCursor the range is the default hour of an id search: the next cursor carries
   *     {@code from} and {@code to} ahead of the keyset values
   */
  public record Plan(
      int appId,
      Instant from,
      Instant to,
      QueryLimits.TxKind kind,
      boolean byElapsed,
      int limit,
      QueryParams params,
      List<String> filterNames,
      String scope,
      List<String> cursor,
      boolean retentionExceeded,
      boolean rangeInCursor) {}

  /**
   * One page.
   *
   * @param rows the rows
   * @param total rows matching the filters
   * @param nextCursor the cursor of the next page, null on the last page
   */
  public record Page(List<TransactionSearch.Row> rows, long total, String nextCursor) {}

  /**
   * Validates a request (14 §7.2 limits, ADR-0010 C-152, C-163).
   *
   * @throws ApiException 400 for invalid input, 422 for a {@code user_id} filter on an application
   *     in {@code hashed} mode
   */
  public Plan plan(int appId, TransactionSearch.Request body, int maxLimit) {
    TransactionSearch.Request req =
        body == null
            ? new TransactionSearch.Request(null, null, null, null, null, null, null)
            : body;
    TransactionSearch.Filters f =
        req.filters() == null ? TransactionSearch.Filters.none() : req.filters();
    String userId = QueryLimits.userIdFilter(QueryLimits.text("user_id", f.userId()));
    String requestId = QueryLimits.text("request_id", f.requestId());
    String trace =
        f.traceId() == null
            ? null
            : Ids.traceId("trace_id", QueryLimits.text("trace_id", f.traceId()));
    Instant from = QueryLimits.optionalTime("from", req.from());
    Instant to = QueryLimits.optionalTime("to", req.to());
    // An id search without from/to reads the hour before its first page; later pages take that
    // range from the cursor (below), not from the clock.
    boolean defaultRange =
        from == null && to == null && (userId != null || requestId != null || trace != null);
    if (!defaultRange) {
      if (from == null || to == null) {
        throw new ApiException(ErrorCode.RANGE_INVALID, "A time parameter is missing")
            .detail(from == null ? "from" : "to", "missing");
      }
      QueryLimits.range(from, to, QueryLimits.RAW_RANGE_MAX);
    }
    QueryLimits.TxKind kind = QueryLimits.kind(req.kind());
    QueryLimits.Sort sort =
        QueryLimits.sort(req.sort(), SORTS, new QueryLimits.Sort("elapsed_ms", true));
    if (!sort.descending()) {
      throw new ApiException(ErrorCode.UNSUPPORTED_SORT).detail("sort", "unknown_direction");
    }
    int limit =
        QueryLimits.limit("limit", req.limit(), Math.min(DEFAULT_LIMIT, maxLimit), maxLimit);

    List<String> set = new ArrayList<>();
    List<Long> services =
        Ids.parseUnsigned(
            "service_id",
            QueryLimits.array("service_id", f.serviceId(), QueryLimits.MAX_ARRAY_VALUES));
    List<Integer> instances =
        QueryLimits.array("instance_id", f.instanceId(), QueryLimits.MAX_ARRAY_VALUES);
    List<Integer> statuses =
        QueryLimits.array("http_status", f.httpStatus(), QueryLimits.MAX_ARRAY_VALUES);
    List<Long> classes =
        Ids.parseUnsigned(
            "error_class_id",
            QueryLimits.array("error_class_id", f.errorClassId(), QueryLimits.MAX_ARRAY_VALUES));
    List<Long> versions =
        Ids.parseUnsigned(
            "version_id",
            QueryLimits.array("version_id", f.versionId(), QueryLimits.MAX_ARRAY_VALUES));
    for (Integer i : instances) {
      if (i == null || i < 0) {
        throw new ApiException(ErrorCode.FILTER_INVALID).detail("instance_id", "invalid_value");
      }
    }
    for (Integer s : statuses) {
      if (s == null || s < 0 || s > 999) {
        throw new ApiException(ErrorCode.FILTER_INVALID).detail("http_status", "invalid_value");
      }
    }
    long group =
        f.errorGroupId() == null
            ? 0
            : Ids.parseUnsigned(
                "error_group_id", QueryLimits.text("error_group_id", f.errorGroupId()));
    long sql =
        f.sqlId() == null ? 0 : Ids.parseUnsigned("sql_id", QueryLimits.text("sql_id", f.sqlId()));
    long minMs = bound("min_ms", f.minMs(), 0);
    long maxMs = bound("max_ms", f.maxMs(), UINT32_MAX);
    if (minMs > maxMs) {
      throw new ApiException(ErrorCode.FILTER_INVALID).detail("max_ms", "below_min_ms");
    }
    RemoteIps.Filter ip =
        f.remoteIp() == null ? null : RemoteIps.filter(QueryLimits.text("remote_ip", f.remoteIp()));
    if (f.userId() != null) {
      if (userId == null) {
        throw new ApiException(ErrorCode.FILTER_INVALID).detail("user_id", "empty");
      }
      requireSearchableUserIds(appId);
    }

    note(set, "service_id", !services.isEmpty());
    note(set, "instance_id", !instances.isEmpty());
    note(set, "http_status", !statuses.isEmpty());
    note(set, "error", f.error() != null);
    note(set, "error_class_id", !classes.isEmpty());
    note(set, "error_group_id", group != 0);
    note(set, "min_ms", f.minMs() != null);
    note(set, "max_ms", f.maxMs() != null);
    note(set, "remote_ip", ip != null);
    note(set, "user_id", userId != null);
    note(set, "request_id", requestId != null);
    note(set, "sql_id", sql != 0);
    note(set, "version_id", !versions.isEmpty());
    note(set, "has_profile", f.hasProfile() != null);
    note(set, "trace_id", trace != null);

    boolean byElapsed = sort.field().equals("elapsed_ms");
    String scope =
        OPERATION
            + "|"
            + digest(
                appId,
                defaultRange ? DEFAULT_RANGE : from,
                defaultRange ? ID_SEARCH_RANGE : to,
                kind,
                sort.field(),
                services,
                instances,
                statuses,
                f.error(),
                classes,
                group,
                minMs,
                maxMs,
                ip == null ? null : ip.exact(),
                userId,
                requestId,
                sql,
                versions,
                f.hasProfile(),
                trace);
    List<String> cursor = null;
    if (req.cursor() != null && !req.cursor().isEmpty()) {
      List<String> values = cursors.decode(scope, req.cursor());
      if (values.size() != (byElapsed ? 3 : 2) + (defaultRange ? 2 : 0)) {
        throw invalidCursor();
      }
      if (defaultRange) {
        from = cursorTime(values.get(0));
        to = cursorTime(values.get(1));
        if (!Duration.between(from, to).equals(ID_SEARCH_RANGE)) {
          throw invalidCursor();
        }
        values = values.subList(2, values.size());
      }
      cursor = List.copyOf(values);
    }
    if (defaultRange && cursor == null) {
      to = sources.now().truncatedTo(ChronoUnit.MILLIS);
      from = to.minus(ID_SEARCH_RANGE);
    }

    QueryParams params =
        QueryParams.create()
            .uint32("app", appId)
            .dateTime64("from", from)
            .dateTime64("to", to)
            .stringArray("kinds", kind.storedValues())
            .uint64Array("services", services)
            .int64Array("instances", instances.stream().mapToLong(Integer::longValue).toArray())
            .int64Array("statuses", statuses.stream().mapToLong(Integer::longValue).toArray())
            .int64("error", f.error() == null ? -1 : (f.error() ? 1 : 0))
            .uint64Array("classes", classes)
            .uint64("group", group)
            .int64("min_ms", minMs)
            .int64("max_ms", maxMs)
            .int64("ip_filter", ip == null ? 0 : 1)
            .string("ip", ip == null ? "::" : ip.exact())
            .string("ip_masked", ip == null ? "::" : ip.masked())
            .string("uid", userId == null ? "" : userId)
            .string("rid", requestId == null ? "" : requestId)
            .uint64("sql", sql)
            .uint64Array("versions", versions)
            .int64("has_profile", f.hasProfile() == null ? -1 : (f.hasProfile() ? 1 : 0))
            .string("trace", trace == null ? "" : trace);
    boolean exceeded = from.isBefore(sources.now().minus(retention.xlog()));
    return new Plan(
        appId,
        from,
        to,
        kind,
        byElapsed,
        limit,
        params,
        List.copyOf(set),
        scope,
        cursor,
        exceeded,
        defaultRange);
  }

  /** A range bound carried by a default-range cursor (epoch milliseconds). */
  private static Instant cursorTime(String value) {
    try {
      return Instant.ofEpochMilli(Long.parseLong(value));
    } catch (NumberFormatException | DateTimeException e) {
      throw invalidCursor();
    }
  }

  private static ApiException invalidCursor() {
    return new ApiException(ErrorCode.FILTER_INVALID, "The cursor is invalid")
        .detail("cursor", "invalid");
  }

  /** C-152: a {@code user_id} filter needs an application in {@code plain} mode. */
  private void requireSearchableUserIds(int appId) {
    Set<String> modes = directory.userIdModes(appId);
    if (!modes.contains("plain") && modes.contains("hashed")) {
      throw new ApiException(
              ErrorCode.FILTER_UNPROCESSABLE,
              "The application stores hashed user ids; searching them needs the application salt")
          .detail("user_id", "user_id_hash_unavailable");
    }
  }

  /** The total of the plan's filters. */
  public long count(Plan plan) {
    return executor
        .query("transactions/count", plan.params().copy(), QueryOptions.defaults())
        .single()
        .getLong("total");
  }

  /** One page: {@code limit} rows after the cursor, the total and the next cursor. */
  public Page page(Plan plan) {
    List<QueryRow> rows = rows(plan, plan.limit() + 1);
    long total = count(plan);
    String next = null;
    if (rows.size() > plan.limit()) {
      rows = rows.subList(0, plan.limit());
      next = nextCursor(plan, rows.get(rows.size() - 1));
    }
    return new Page(convert(plan.appId(), rows), total, next);
  }

  /**
   * The cursor of the page after {@code last}: the range of a default-range id search ({@link
   * Plan#rangeInCursor()}, epoch milliseconds), then the keyset values of {@code last}.
   */
  String nextCursor(Plan plan, QueryRow last) {
    List<String> values = new ArrayList<>(5);
    if (plan.rangeInCursor()) {
      values.add(Long.toString(plan.from().toEpochMilli()));
      values.add(Long.toString(plan.to().toEpochMilli()));
    }
    if (plan.byElapsed()) {
      values.add(Long.toString(last.getLong("elapsed_ms")));
    }
    values.add(Long.toString(last.getLong("ts_ms")));
    values.add(Ids.text(last.getLong("tx_id")));
    return cursors.encode(plan.scope(), values);
  }

  /** At most {@code max} rows of the plan from its cursor (CSV: all rows up to the cap + 1). */
  public List<QueryRow> rows(Plan plan, int max) {
    QueryParams p = plan.params().copy();
    List<String> c = plan.cursor();
    try {
      if (plan.byElapsed()) {
        p.int64("c_elapsed", c == null ? UINT32_MAX : Long.parseLong(c.get(0)));
        p.dateTime64(
            "c_ts", c == null ? CURSOR_TOP : Instant.ofEpochMilli(Long.parseLong(c.get(1))));
        p.uint64("c_tx", c == null ? -1L : Long.parseUnsignedLong(c.get(2)));
      } else {
        p.dateTime64(
            "c_ts", c == null ? CURSOR_TOP : Instant.ofEpochMilli(Long.parseLong(c.get(0))));
        p.uint64("c_tx", c == null ? -1L : Long.parseUnsignedLong(c.get(1)));
      }
    } catch (NumberFormatException e) {
      throw invalidCursor();
    }
    p.uint32("limit", max);
    String template = plan.byElapsed() ? "transactions/search.elapsed" : "transactions/search.ts";
    return executor.query(template, p, QueryOptions.defaults().withMaxResultRows(max)).rows();
  }

  /** Rows with their names. */
  public List<TransactionSearch.Row> convert(int appId, List<QueryRow> rows) {
    Set<Long> services = new LinkedHashSet<>();
    Set<Long> classes = new LinkedHashSet<>();
    Set<Long> versions = new LinkedHashSet<>();
    Set<Integer> instances = new LinkedHashSet<>();
    for (QueryRow r : rows) {
      services.add(r.getLong("service_id"));
      classes.add(r.getLong("error_class_id"));
      versions.add(r.getLong("app_version_id"));
      instances.add((int) r.getLong("instance_id"));
    }
    Map<Long, String> serviceNames = names.names(appId, Kind.SERVICE, services);
    Map<Long, String> classNames = names.names(appId, Kind.CLASS, classes);
    Map<Long, String> versionNames = names.names(appId, Kind.APP_VERSION, versions);
    Map<Integer, AppDirectory.InstanceInfo> instanceInfo = directory.instances(instances);
    List<TransactionSearch.Row> out = new ArrayList<>(rows.size());
    for (QueryRow r : rows) {
      long service = r.getLong("service_id");
      long errorClass = r.getLong("error_class_id");
      long version = r.getLong("app_version_id");
      int instance = (int) r.getLong("instance_id");
      AppDirectory.InstanceInfo info = instanceInfo.get(instance);
      String userId = r.getString("user_id");
      out.add(
          new TransactionSearch.Row(
              Ids.text(r.getLong("tx_id")),
              Instant.ofEpochMilli(r.getLong("ts_ms")),
              name(serviceNames, service),
              Ids.keyOrNull(service),
              r.getLong("elapsed_ms"),
              (int) r.getLong("http_status"),
              r.getBoolean("error"),
              name(classNames, errorClass),
              Ids.keyOrNull(errorClass),
              info == null ? null : UntrustedStrings.name(info.name()).value(),
              instance,
              r.getLong("sql_count"),
              r.getLong("sql_time_ms"),
              r.getLong("ext_count"),
              r.getLong("ext_time_ms"),
              userId == null || userId.isEmpty() ? null : userId,
              r.getString("user_id_mode"),
              r.getBoolean("profile_captured"),
              r.getString("kind"),
              name(versionNames, version),
              Ids.keyOrNull(version)));
    }
    return out;
  }

  private static String name(Map<Long, String> names, long key) {
    String n = names.get(key);
    return n == null ? null : UntrustedStrings.name(n).value();
  }

  private static long bound(String field, Long value, long fallback) {
    if (value == null) {
      return fallback;
    }
    if (value < 0 || value > UINT32_MAX) {
      throw new ApiException(ErrorCode.FILTER_INVALID).detail(field, "out_of_range");
    }
    return value;
  }

  private static void note(List<String> set, String name, boolean present) {
    if (present) {
      set.add(name);
    }
  }

  /** SHA-256 of the request's normalized values (the cursor scope; never the values themselves). */
  private static String digest(Object... values) {
    StringBuilder sb = new StringBuilder();
    for (Object v : values) {
      sb.append(v == null ? "\u0001" : String.valueOf(v)).append('\u0000');
    }
    try {
      byte[] hash =
          MessageDigest.getInstance("SHA-256")
              .digest(sb.toString().getBytes(StandardCharsets.UTF_8));
      return HexFormat.of().formatHex(hash, 0, 16);
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 is not available", e);
    }
  }
}
