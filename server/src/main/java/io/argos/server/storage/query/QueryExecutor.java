/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.storage.query;

import com.clickhouse.client.api.Client;
import com.clickhouse.client.api.query.QueryResponse;
import com.clickhouse.client.api.query.QuerySettings;
import com.clickhouse.data.ClickHouseFormat;
import io.argos.server.api.error.ApiException;
import io.argos.server.api.error.ErrorCode;
import io.argos.server.api.openapi.ArgosLimits;
import io.argos.server.config.ArgosProperties;
import io.argos.server.storage.clickhouse.ClickHouseClients;
import io.argos.server.web.RequestIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.stereotype.Component;
import org.springframework.util.ClassUtils;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerMapping;

/**
 * Runs the reader queries of the API (13 §6.3, 14 §3, SEC-76, ADR-0007 C-64) — the only way the API
 * reads ClickHouse:
 *
 * <ul>
 *   <li>always as {@code argos_reader} ({@code argos.clickhouse.reader}, read-only profile of 13
 *       §7.3), one client per server;
 *   <li>a rendered template of {@link ReaderQueries} plus a SQL {@code SETTINGS} clause with {@code
 *       max_execution_time} (the endpoint's {@code @ArgosLimits(timeoutS)} or 10 s), {@code
 *       max_rows_to_read}, {@code max_result_rows} and {@code max_memory_usage}, each clamped to
 *       the reader profile (read once with {@code getSetting}); in the SQL text, not the URL, so
 *       that a refused setting is in {@code system.query_log} too (C-64);
 *   <li>values only as query parameters ({@link QueryParams}); a declared parameter without a value
 *       is a defect (500), never an empty string;
 *   <li>{@code query_id} = {@code <request id>-<n>}: every statement of a request can be found in
 *       {@code system.query_log} by the request id of its response and log lines (T-ST-053);
 *   <li>{@code wait_end_of_query}: ClickHouse buffers the result, so an error is an HTTP error with
 *       its code, not a cut stream;
 *   <li>failures become {@link ApiException}s by {@link ClickHouseErrors}: 504 {@code
 *       query_timeout}, 503 {@code storage_unavailable}, 500 {@code internal}; the log line names
 *       the template, the query id and the ClickHouse code, never a parameter value.
 * </ul>
 *
 * <p>The request id travels in {@code query_id} only, never in {@code log_comment} (ADR-0010
 * C-174): the reader profile of {@code deploy/clickhouse/users.d/argos.xml} does not let {@code
 * argos_reader} change {@code log_comment} ({@code readonly = 1}; ClickHouse 26.8 answers 164
 * {@code READONLY}), and the {@code query_id} correlation finds the same statements.
 */
@Component
public class QueryExecutor implements DisposableBean {

  /**
   * Result format: one JSON array per row, after a names and a types line. Set through the client
   * ({@code QuerySettings.setFormat}): client-v2 sends its own format, a {@code FORMAT} clause in
   * the SQL text would not win.
   */
  static final ClickHouseFormat FORMAT = ClickHouseFormat.JSONCompactEachRowWithNamesAndTypes;

  /** Time the client waits beyond {@code max_execution_time} for the answer. */
  static final Duration CLIENT_MARGIN = Duration.ofSeconds(5);

  /** Connect timeout of the reader client. */
  static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);

  private static final Logger LOG = LoggerFactory.getLogger(QueryExecutor.class);

  /** A request id that may go into a query id. */
  private static final Pattern SAFE_ID = Pattern.compile("[A-Za-z0-9_-]{1,64}");

  private static final AtomicLong SEQUENCE = new AtomicLong();

  private final ArgosProperties.ClickHouse clickhouse;
  private final ReaderQueries queries;
  private final Object lock = new Object();
  private Client client; // guarded by lock
  private boolean closed; // guarded by lock
  private volatile ReaderProfile profile;

  /** The executor of the server. */
  @Autowired
  public QueryExecutor(ArgosProperties properties, ReaderQueries queries) {
    this(properties.clickhouse(), queries);
  }

  /**
   * An executor.
   *
   * @param clickhouse URL and the {@code reader} account
   * @param queries templates and rendering
   */
  public QueryExecutor(ArgosProperties.ClickHouse clickhouse, ReaderQueries queries) {
    this.clickhouse = clickhouse;
    this.queries = queries;
  }

  /** The templates of this executor. */
  public ReaderQueries queries() {
    return queries;
  }

  /** Runs the template {@code name} and collects its rows. */
  public QueryResult query(String name, QueryParams params, QueryOptions options) {
    return query(queries.get(name), params, options);
  }

  /** Runs a template and collects its rows. */
  public QueryResult query(ReaderQuery query, QueryParams params, QueryOptions options) {
    List<QueryRow> rows = new ArrayList<>();
    Execution e =
        run(query.name(), queries.render(query), query.parameters(), params, options, rows::add);
    return new QueryResult(e.queryId(), e.header().names(), e.header().types(), rows);
  }

  /**
   * Runs a template and hands every row to {@code sink} as it is read (large results such as XLog
   * points, without a second copy).
   *
   * @return the query id
   */
  public String forEach(
      ReaderQuery query, QueryParams params, QueryOptions options, Consumer<QueryRow> sink) {
    return run(query.name(), queries.render(query), query.parameters(), params, options, sink)
        .queryId();
  }

  /** Runs a segmented query over {@code segments} and collects its rows. */
  public QueryResult query(
      SegmentedQuery query, List<TimeSegment> segments, QueryParams params, QueryOptions options) {
    ReaderQueries.Rendered rendered = queries.render(query, segments, params);
    Map<String, String> declared = new LinkedHashMap<>(query.outer().parameters());
    for (int i = 0; i < segments.size(); i++) {
      ReaderQuery fragment = query.fragment(segments.get(i).level());
      for (Map.Entry<String, String> p : fragment.parameters().entrySet()) {
        String name =
            p.getKey().equals("from") || p.getKey().equals("to")
                ? p.getKey() + "_s" + i
                : p.getKey();
        declared.putIfAbsent(name, p.getValue());
      }
    }
    List<QueryRow> rows = new ArrayList<>();
    Execution e =
        run(query.outer().name(), rendered.sql(), declared, rendered.params(), options, rows::add);
    return new QueryResult(e.queryId(), e.header().names(), e.header().types(), rows);
  }

  private record Execution(String queryId, CompactJsonRows.Header header) {}

  private Execution run(
      String name,
      String sql,
      Map<String, String> declared,
      QueryParams params,
      QueryOptions options,
      Consumer<QueryRow> sink) {
    for (String parameter : declared.keySet()) {
      if (!params.has(parameter)) {
        throw new IllegalStateException(
            "SQL template " + name + ": parameter " + parameter + " has no value");
      }
    }
    String requestId = currentRequestId();
    String queryId = queryId(requestId);
    Client c;
    ReaderProfile limits;
    try {
      c = client();
      limits = profile(c);
    } catch (ApiException e) {
      throw e;
    } catch (RuntimeException e) {
      throw fail(name, queryId, e);
    }
    int timeout = limits.timeout(resolveTimeout(options));
    String statement = sql + "\nSETTINGS " + settingsClause(limits, options, timeout);
    QuerySettings settings = settings(queryId);
    long started = System.nanoTime();
    CompletableFuture<QueryResponse> future;
    try {
      future = c.query(statement, params.asMap(), settings);
    } catch (RuntimeException e) {
      throw fail(name, queryId, e);
    }
    QueryResponse response = null;
    try {
      response = future.get(timeout + CLIENT_MARGIN.toSeconds(), TimeUnit.SECONDS);
      CompactJsonRows.Header header = CompactJsonRows.read(response.getInputStream(), sink);
      if (LOG.isDebugEnabled()) {
        LOG.debug(
            "Query {} ({}) in {} ms",
            name,
            queryId,
            TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started));
      }
      return new Execution(queryId, header);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      future.cancel(true);
      throw new ApiException(ErrorCode.STORAGE_UNAVAILABLE);
    } catch (TimeoutException e) {
      future.cancel(true);
      throw fail(name, queryId, e);
    } catch (ExecutionException e) {
      throw fail(name, queryId, e.getCause() == null ? e : e.getCause());
    } catch (IOException | RuntimeException e) {
      throw fail(name, queryId, e);
    } finally {
      close(response);
    }
  }

  /**
   * The {@code SETTINGS} clause: limits clamped to the reader profile, the numbers formatted by the
   * server; no request value (the request id is in the query id, ADR-0010 C-174).
   */
  static String settingsClause(ReaderProfile profile, QueryOptions options, int timeoutSeconds) {
    StringBuilder sb = new StringBuilder();
    sb.append("max_execution_time = ").append(timeoutSeconds);
    sb.append(", max_rows_to_read = ").append(profile.rowsToRead(options.maxRowsToRead()));
    sb.append(", max_result_rows = ").append(profile.resultRows(options.maxResultRows()));
    sb.append(", max_memory_usage = ").append(profile.memory(options.maxMemoryBytes()));
    return sb.toString();
  }

  /** {@code options.timeoutSeconds}, else the endpoint's {@code @ArgosLimits}, else 10. */
  static int resolveTimeout(QueryOptions options) {
    if (options.timeoutSeconds() > 0) {
      return options.timeoutSeconds();
    }
    int endpoint = endpointTimeout();
    return endpoint > 0
        ? Math.min(endpoint, QueryOptions.MAX_TIMEOUT_SECONDS)
        : QueryOptions.MAX_TIMEOUT_SECONDS;
  }

  /** {@code timeoutS} of the {@code @ArgosLimits} of the handler of the current request, or 0. */
  private static int endpointTimeout() {
    RequestAttributes attributes = RequestContextHolder.getRequestAttributes();
    if (!(attributes instanceof ServletRequestAttributes servlet)) {
      return 0;
    }
    HttpServletRequest request = servlet.getRequest();
    Object handler = request.getAttribute(HandlerMapping.BEST_MATCHING_HANDLER_ATTRIBUTE);
    if (!(handler instanceof HandlerMethod method)) {
      return 0;
    }
    ArgosLimits limits =
        AnnotatedElementUtils.findMergedAnnotation(method.getMethod(), ArgosLimits.class);
    if (limits == null) {
      limits =
          AnnotatedElementUtils.findMergedAnnotation(
              ClassUtils.getUserClass(method.getBeanType()), ArgosLimits.class);
    }
    return limits == null ? 0 : limits.timeoutS();
  }

  /** The request id of the current request (MDC), or null outside a request. */
  static String currentRequestId() {
    String id = MDC.get(RequestIdFilter.MDC_KEY);
    return id != null && SAFE_ID.matcher(id).matches() ? id : null;
  }

  /** {@code <request id>-<n>}, or {@code internal-<n>} outside a request. */
  static String queryId(String requestId) {
    String n = Long.toString(SEQUENCE.incrementAndGet(), 36);
    return (requestId == null ? "internal" : requestId) + "-" + n;
  }

  private ApiException fail(String name, String queryId, Throwable failure) {
    ClickHouseErrors.Outcome outcome = ClickHouseErrors.classify(failure);
    if (outcome.errorCode() == ErrorCode.INTERNAL) {
      if (outcome.clickHouseCode() > 0) {
        LOG.error(
            "Query {} ({}) failed: ClickHouse code {}", name, queryId, outcome.clickHouseCode());
      } else {
        LOG.error("Query {} ({}) failed: {}", name, queryId, failure.getClass().getName(), failure);
      }
    } else {
      LOG.warn(
          "Query {} ({}) answered {}: {}",
          name,
          queryId,
          outcome.errorCode().code(),
          outcome.cause());
    }
    return outcome.toApiException();
  }

  private Client client() {
    synchronized (lock) {
      if (closed) {
        throw new ApiException(ErrorCode.STORAGE_UNAVAILABLE);
      }
      if (client == null) {
        try {
          client =
              ClickHouseClients.open(
                  clickhouse,
                  ClickHouseClients.Account.READER,
                  CONNECT_TIMEOUT,
                  Duration.ofSeconds(QueryOptions.MAX_TIMEOUT_SECONDS).plus(CLIENT_MARGIN));
        } catch (IllegalStateException e) {
          LOG.error("Cannot create the ClickHouse reader client: {}", e.getMessage());
          throw new ApiException(ErrorCode.STORAGE_UNAVAILABLE);
        }
      }
      return client;
    }
  }

  /** The client settings of a statement: query id, result format, buffered answer. */
  private static QuerySettings settings(String queryId) {
    return new QuerySettings().setQueryId(queryId).setFormat(FORMAT).waitEndOfQuery(true);
  }

  /**
   * The statement that reads the reader profile's limits ({@code getSetting}). It carries its own
   * {@code SETTINGS} clause like every statement of the API (ADR-0007 C-64): {@code
   * max_execution_time = 10}, the maximum of the reader constraint (1 to 10) and the shipped
   * profile value, so {@code t} reads at most 10; the other limits are read as the profile sets
   * them.
   */
  static final String PROFILE_PROBE_SQL =
      "SELECT toString(getSetting('max_execution_time')) AS t,"
          + " toString(getSetting('max_rows_to_read')) AS r,"
          + " toString(getSetting('max_result_rows')) AS n,"
          + " toString(getSetting('max_memory_usage')) AS m"
          + "\nSETTINGS max_execution_time = "
          + QueryOptions.MAX_TIMEOUT_SECONDS;

  /**
   * The limits of the reader profile, read once ({@link #PROFILE_PROBE_SQL}). The probe is the
   * server's own statement, not the request's: its query id is {@code internal-<n>} even when a
   * request triggers it, so the statements under a request id are only the request's (T-ST-053).
   */
  private ReaderProfile profile(Client c) {
    ReaderProfile p = profile;
    if (p != null) {
      return p;
    }
    String sql = PROFILE_PROBE_SQL;
    QueryResponse response = null;
    String queryId = queryId(null);
    try {
      CompletableFuture<QueryResponse> f = c.query(sql, settings(queryId));
      response =
          f.get(QueryOptions.MAX_TIMEOUT_SECONDS + CLIENT_MARGIN.toSeconds(), TimeUnit.SECONDS);
      List<QueryRow> rows = new ArrayList<>();
      CompactJsonRows.read(response.getInputStream(), rows::add);
      if (rows.size() != 1) {
        throw new IOException("Reader profile probe returned " + rows.size() + " rows");
      }
      QueryRow row = rows.get(0);
      p =
          new ReaderProfile(
              number(row.getString("t")),
              number(row.getString("r")),
              number(row.getString("n")),
              number(row.getString("m")));
      profile = p;
      LOG.info(
          "ClickHouse reader profile: max_execution_time {} s, max_rows_to_read {},"
              + " max_result_rows {}, max_memory_usage {}",
          p.maxTimeoutSeconds(),
          p.maxRowsToRead(),
          p.maxResultRows(),
          p.maxMemoryBytes());
      return p;
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new ApiException(ErrorCode.STORAGE_UNAVAILABLE);
    } catch (ExecutionException e) {
      throw fail("reader-profile", queryId, e.getCause() == null ? e : e.getCause());
    } catch (TimeoutException | IOException | RuntimeException e) {
      throw fail("reader-profile", queryId, e);
    } finally {
      close(response);
    }
  }

  private static long number(String text) {
    if (text == null || text.isBlank()) {
      return 0;
    }
    try {
      return (long) Double.parseDouble(text.strip().toLowerCase(Locale.ROOT));
    } catch (NumberFormatException e) {
      return 0;
    }
  }

  private static void close(QueryResponse response) {
    if (response == null) {
      return;
    }
    try {
      response.close();
    } catch (Exception e) {
      LOG.debug("Closing a ClickHouse response failed: {}", e.getClass().getName());
    }
  }

  @Override
  public void destroy() {
    synchronized (lock) {
      closed = true;
      if (client != null) {
        client.close();
        client = null;
      }
    }
  }

  /**
   * The limits of the {@code argos_reader} profile (13 §7.3: a query may only lower them; the
   * constraints refuse a larger value, ClickHouse code 452). Zero means "no limit in the profile".
   *
   * @param maxTimeoutSeconds {@code max_execution_time}
   * @param maxRowsToRead {@code max_rows_to_read}
   * @param maxResultRows {@code max_result_rows}
   * @param maxMemoryBytes {@code max_memory_usage}
   */
  record ReaderProfile(
      long maxTimeoutSeconds, long maxRowsToRead, long maxResultRows, long maxMemoryBytes) {

    int timeout(int requested) {
      long t = Math.max(1, requested);
      return (int) (maxTimeoutSeconds > 0 ? Math.min(t, maxTimeoutSeconds) : t);
    }

    long rowsToRead(long requested) {
      return clamp(requested, maxRowsToRead, 2_000_000_000L);
    }

    long resultRows(long requested) {
      return clamp(requested, maxResultRows, 200_000L);
    }

    long memory(long requested) {
      return clamp(requested, maxMemoryBytes, 2L * 1024 * 1024 * 1024);
    }

    /** {@code requested} (or the profile value when 0) within the profile value. */
    private static long clamp(long requested, long profile, long fallback) {
      long cap = profile > 0 ? profile : fallback;
      return requested > 0 ? Math.min(requested, cap) : cap;
    }
  }
}
