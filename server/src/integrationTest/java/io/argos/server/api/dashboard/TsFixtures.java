/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.api.dashboard;

import io.argos.server.auth.Role;
import io.argos.server.testing.ServerTestEnvironment;
import io.argos.server.testing.ServerTestEnvironment.PgAccount;
import io.argos.server.testing.auth.ApiResponse;
import io.argos.server.testing.auth.ApiTestClient;
import io.argos.server.testing.auth.ArgosTestServer;
import io.argos.server.testing.auth.TestUsers;
import io.argos.storage.clickhouse.Account;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * Test data of the time-series and statistics tests (WO-23, lane timeseries-stats) on the shared
 * query test environment: PostgreSQL rows of the applications 910 to 919 (never generated; {@code
 * QueryTestData.FIRST_FREE_APP_ID} = 900, 900 and 901 belong to the query-core tests), ground truth
 * read from ClickHouse as {@code argos_reader}, and {@code system.query_log} (T-ST-053).
 */
final class TsFixtures {

  static final DateTimeFormatter CH_MS =
      DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS", Locale.ROOT).withZone(ZoneOffset.UTC);

  static final DateTimeFormatter CH_S =
      DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss", Locale.ROOT).withZone(ZoneOffset.UTC);

  private TsFixtures() {}

  /** An application row (idempotent). */
  static void app(ServerTestEnvironment env, int id, String name) throws SQLException {
    try (Connection c = env.connect(PgAccount.APP);
        PreparedStatement ps =
            c.prepareStatement(
                "INSERT INTO argos.apps (id, name, env, auto_register) VALUES (?, ?, 'prod', TRUE)"
                    + " ON CONFLICT DO NOTHING")) {
      ps.setInt(1, id);
      ps.setString(2, name);
      ps.executeUpdate();
    }
  }

  /** An instance row (idempotent). */
  static void instance(ServerTestEnvironment env, int id, int appId, String name)
      throws SQLException {
    try (Connection c = env.connect(PgAccount.APP);
        PreparedStatement ps =
            c.prepareStatement(
                "INSERT INTO argos.instances (id, app_id, name, host, pid, agent_version,"
                    + " config_hash) VALUES (?, ?, ?, 'host-' || ?, 4242, '0.0.0-SNAPSHOT',"
                    + " 'cafebabecafebabe') ON CONFLICT DO NOTHING")) {
      ps.setInt(1, id);
      ps.setInt(2, appId);
      ps.setString(3, name);
      ps.setInt(4, id);
      ps.executeUpdate();
    }
  }

  /** A dictionary entry (idempotent). */
  static void dictionary(ServerTestEnvironment env, int appId, String kind, long hash, String text)
      throws SQLException {
    try (Connection c = env.connect(PgAccount.APP);
        PreparedStatement ps =
            c.prepareStatement(
                "INSERT INTO argos.dictionary (app_id, kind, hash64, text) VALUES (?,"
                    + " ?::argos.dict_kind, ?, ?) ON CONFLICT DO NOTHING")) {
      ps.setInt(1, appId);
      ps.setString(2, kind);
      ps.setLong(3, hash);
      ps.setString(4, text);
      ps.executeUpdate();
    }
  }

  /** An event row ({@code detail} JSON text or null). */
  static void event(
      ServerTestEnvironment env,
      Integer appId,
      Integer instanceId,
      String type,
      Instant ts,
      String title,
      String detailJson)
      throws SQLException {
    try (Connection c = env.connect(PgAccount.APP);
        PreparedStatement ps =
            c.prepareStatement(
                "INSERT INTO argos.events (id, app_id, env, instance_id, type, title, detail, ts)"
                    + " VALUES (?, ?, 'prod', ?, ?, ?, ?::jsonb, ?)")) {
      ps.setObject(1, UUID.randomUUID());
      ps.setObject(2, appId);
      ps.setObject(3, instanceId);
      ps.setString(4, type);
      ps.setString(5, title);
      ps.setString(6, detailJson);
      ps.setTimestamp(7, Timestamp.from(ts));
      ps.executeUpdate();
    }
  }

  /** A logged-in client of a new user of {@code role}. */
  static ApiTestClient login(ArgosTestServer server, ServerTestEnvironment env, Role role) {
    TestUsers.TestUser user = new TestUsers(env).create(role);
    ApiTestClient client = server.client();
    ApiResponse r = client.login(user.username(), user.password());
    if (r.status() != 200) {
      throw new IllegalStateException("login failed: " + r);
    }
    return client;
  }

  /** A count of ClickHouse rows, read as argos_reader (test code: times formatted here). */
  static long count(ServerTestEnvironment env, String sql) {
    return env.clickHouse(Account.READER).scalarLong(sql);
  }

  /** {@code '<time>'} with milliseconds. */
  static String ms(Instant t) {
    return "toDateTime64('" + CH_MS.format(t) + "', 3, 'UTC')";
  }

  /** {@code '<time>'} in seconds. */
  static String s(Instant t) {
    return "toDateTime('" + CH_S.format(t) + "', 'UTC')";
  }

  /**
   * One finished statement of {@code system.query_log}.
   *
   * @param queryId query id
   * @param user the account
   * @param query the SQL text
   * @param maxExecutionTime the Settings value
   * @param maxRowsToRead the Settings value
   * @param maxResultRows the Settings value
   * @param maxMemoryUsage the Settings value
   */
  record LogRow(
      String queryId,
      String user,
      String query,
      String maxExecutionTime,
      String maxRowsToRead,
      String maxResultRows,
      String maxMemoryUsage) {}

  /**
   * The finished statements of the requests ({@code query_id} = {@code <request id>-<n>}), by
   * request id; waits for the query log flush (7.5 s) until every request has a statement, up to 60
   * s.
   */
  static Map<String, List<LogRow>> queryLog(ServerTestEnvironment env, Collection<String> requests)
      throws InterruptedException {
    List<String> ids = new ArrayList<>();
    for (String id : requests) {
      if (!id.matches("[A-Za-z0-9_-]{1,64}")) {
        throw new IllegalArgumentException("Unexpected request id");
      }
      ids.add("'" + id + "'");
    }
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);
    Map<String, List<LogRow>> out = new HashMap<>();
    while (true) {
      out.clear();
      for (List<String> r :
          env.clickHouse(Account.READER)
              .rows(
                  "SELECT query_id, user, replaceAll(query, '\n', ' '),"
                      + " Settings['max_execution_time'], Settings['max_rows_to_read'],"
                      + " Settings['max_result_rows'], Settings['max_memory_usage']"
                      + " FROM system.query_log WHERE type = 'QueryFinish'"
                      + " AND event_date >= yesterday()"
                      + " AND substring(query_id, 1, position(query_id, '-') - 1) IN ("
                      + String.join(",", ids)
                      + ") ORDER BY event_time")) {
        LogRow row =
            new LogRow(r.get(0), r.get(1), r.get(2), r.get(3), r.get(4), r.get(5), r.get(6));
        out.computeIfAbsent(
                row.queryId().substring(0, row.queryId().indexOf('-')), k -> new ArrayList<>())
            .add(row);
      }
      if (out.size() >= ids.size() || System.nanoTime() > deadline) {
        return out;
      }
      TimeUnit.MILLISECONDS.sleep(1_000);
    }
  }

  /** {@code start + minutes}. */
  static Instant plusMinutes(Instant start, long minutes) {
    return start.plus(Duration.ofMinutes(minutes));
  }
}
