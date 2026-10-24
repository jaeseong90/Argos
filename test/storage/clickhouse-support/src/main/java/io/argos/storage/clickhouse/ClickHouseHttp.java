/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.storage.clickhouse;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Minimal ClickHouse HTTP client (java.net.http, no third-party dependency) bound to one account.
 *
 * <p>Statements go in the POST body, settings in the URL (ClickHouse HTTP interface). Inserts put
 * the {@code INSERT ... FORMAT x} statement in the {@code query} parameter and the data in the
 * body. Results use the default HTTP format (TabSeparated). Credentials travel in the {@code
 * X-ClickHouse-User}/{@code X-ClickHouse-Key} headers, never in the URL. A non-200 response raises
 * {@link ClickHouseException}.
 */
public final class ClickHouseHttp {

  private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);
  private static final Duration DEFAULT_REQUEST_TIMEOUT = Duration.ofMinutes(10);

  private final HttpClient client;
  private final URI base;
  private final String user;
  private final String password;
  private final Duration requestTimeout;

  /**
   * @param base server URL, e.g. {@code http://127.0.0.1:8123/}
   * @param user ClickHouse user
   * @param password plain password (sent in a header)
   */
  public ClickHouseHttp(URI base, String user, String password) {
    this(base, user, password, DEFAULT_REQUEST_TIMEOUT);
  }

  private ClickHouseHttp(URI base, String user, String password, Duration requestTimeout) {
    this.client = HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build();
    this.base = base.toString().endsWith("/") ? base : URI.create(base + "/");
    this.user = user;
    this.password = password;
    this.requestTimeout = requestTimeout;
  }

  /** Same server and account with another request timeout. */
  public ClickHouseHttp withRequestTimeout(Duration timeout) {
    return new ClickHouseHttp(base, user, password, timeout);
  }

  /** The ClickHouse user of this client. */
  public String user() {
    return user;
  }

  /** Runs a statement and returns the response body. */
  public String execute(String sql) {
    return execute(sql, Map.of()).body();
  }

  /** Runs a statement with settings passed in the URL. */
  public Response execute(String sql, Map<String, String> settings) {
    return send(settings, HttpRequest.BodyPublishers.ofString(sql, StandardCharsets.UTF_8));
  }

  /**
   * Inserts data: {@code insertStatement} is {@code INSERT INTO t (cols) FORMAT f}, {@code data}
   * the rows in that format.
   */
  public Response insert(String insertStatement, String data, Map<String, String> settings) {
    return insert(insertStatement, data.getBytes(StandardCharsets.UTF_8), settings);
  }

  /** Binary variant of {@link #insert(String, String, Map)} (RowBinary, Native, ...). */
  public Response insert(String insertStatement, byte[] data, Map<String, String> settings) {
    Map<String, String> params = new LinkedHashMap<>(settings);
    params.put("query", insertStatement);
    return send(params, HttpRequest.BodyPublishers.ofByteArray(data));
  }

  /** Runs a query and returns the raw response bytes (for RowBinary or Native output). */
  public byte[] queryBytes(String sql) {
    HttpResponse<byte[]> response =
        exchange(
            Map.of(),
            HttpRequest.BodyPublishers.ofString(sql, StandardCharsets.UTF_8),
            HttpResponse.BodyHandlers.ofByteArray());
    if (response.statusCode() != 200) {
      throw error(
          response.statusCode(), response, new String(response.body(), StandardCharsets.UTF_8));
    }
    return response.body();
  }

  /** Runs a query and parses the TabSeparated result into rows of unescaped fields. */
  public List<List<String>> rows(String sql) {
    return parseTabSeparated(execute(sql));
  }

  /** Runs a query that returns one value. */
  public String scalar(String sql) {
    List<List<String>> rows = rows(sql);
    if (rows.size() != 1 || rows.get(0).size() != 1) {
      throw new IllegalStateException("Expected one value, got " + rows + " for: " + sql);
    }
    return rows.get(0).get(0);
  }

  /** Runs a query that returns one integer. */
  public long scalarLong(String sql) {
    return Long.parseLong(scalar(sql));
  }

  /** Runs a statement and returns the ClickHouse error it raises, if any. */
  public Optional<ClickHouseException> failure(String sql) {
    return failure(sql, Map.of());
  }

  /** Runs a statement with settings and returns the ClickHouse error it raises, if any. */
  public Optional<ClickHouseException> failure(String sql, Map<String, String> settings) {
    try {
      execute(sql, settings);
      return Optional.empty();
    } catch (ClickHouseException e) {
      return Optional.of(e);
    }
  }

  private Response send(Map<String, String> params, HttpRequest.BodyPublisher body) {
    HttpResponse<String> response =
        exchange(params, body, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    if (response.statusCode() != 200) {
      throw error(response.statusCode(), response, response.body());
    }
    return new Response(
        response.body(),
        response.headers().firstValue("X-ClickHouse-Query-Id").orElse(""),
        response.headers().firstValue("X-ClickHouse-Summary").orElse(""));
  }

  private <T> HttpResponse<T> exchange(
      Map<String, String> params,
      HttpRequest.BodyPublisher body,
      HttpResponse.BodyHandler<T> handler) {
    HttpRequest request =
        HttpRequest.newBuilder(base.resolve(queryString(params)))
            .timeout(requestTimeout)
            .header("X-ClickHouse-User", user)
            .header("X-ClickHouse-Key", password)
            .POST(body)
            .build();
    try {
      return client.send(request, handler);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new UncheckedIOException(new InterruptedIOException("Interrupted: " + base));
    } catch (IOException e) {
      throw new UncheckedIOException("ClickHouse request failed: " + base, e);
    }
  }

  private static ClickHouseException error(int status, HttpResponse<?> response, String body) {
    return new ClickHouseException(
        status, response.headers().firstValue("X-ClickHouse-Exception-Code").orElse(null), body);
  }

  private static String queryString(Map<String, String> params) {
    if (params.isEmpty()) {
      return "";
    }
    StringBuilder sb = new StringBuilder("?");
    for (Map.Entry<String, String> e : params.entrySet()) {
      if (sb.length() > 1) {
        sb.append('&');
      }
      sb.append(URLEncoder.encode(e.getKey(), StandardCharsets.UTF_8))
          .append('=')
          .append(URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8));
    }
    return sb.toString();
  }

  /** Parses TabSeparated output (escapes \t \n \\ and the other ClickHouse escapes). */
  static List<List<String>> parseTabSeparated(String text) {
    List<List<String>> rows = new ArrayList<>();
    if (text.isEmpty()) {
      return rows;
    }
    for (String line : text.split("\n", -1)) {
      if (line.isEmpty()) {
        continue;
      }
      List<String> fields = new ArrayList<>();
      for (String field : line.split("\t", -1)) {
        fields.add(unescape(field));
      }
      rows.add(fields);
    }
    return rows;
  }

  static String unescape(String field) {
    if (field.indexOf('\\') < 0) {
      return field;
    }
    StringBuilder sb = new StringBuilder(field.length());
    for (int i = 0; i < field.length(); i++) {
      char c = field.charAt(i);
      if (c != '\\' || i + 1 == field.length()) {
        sb.append(c);
        continue;
      }
      char next = field.charAt(++i);
      switch (next) {
        case 't' -> sb.append('\t');
        case 'n' -> sb.append('\n');
        case 'r' -> sb.append('\r');
        case 'b' -> sb.append('\b');
        case 'f' -> sb.append('\f');
        case '0' -> sb.append('\0');
        default -> sb.append(next);
      }
    }
    return sb.toString();
  }

  /**
   * A successful response.
   *
   * @param body response body (TabSeparated for queries, empty for most statements)
   * @param queryId server query id
   * @param summary {@code X-ClickHouse-Summary} header (JSON with read_rows, written_rows, ...)
   */
  public record Response(String body, String queryId, String summary) {

    private static final Pattern FIELD = Pattern.compile("\"([a-z_]+)\":\"(\\d+)\"");

    /** A numeric field of the summary header, e.g. {@code read_rows}; -1 when absent. */
    public long summaryValue(String name) {
      Matcher m = FIELD.matcher(summary);
      while (m.find()) {
        if (m.group(1).equals(name)) {
          return Long.parseLong(m.group(2));
        }
      }
      return -1;
    }
  }
}
