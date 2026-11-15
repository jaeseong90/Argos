/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.api.transactions;

import io.argos.server.auth.ArgosPrincipal;
import io.argos.server.auth.Role;
import io.argos.server.testing.ServerTestEnvironment;
import io.argos.server.testing.ServerTestEnvironment.PgAccount;
import io.argos.server.testing.auth.ApiResponse;
import io.argos.server.testing.auth.ApiTestClient;
import io.argos.server.testing.auth.TestUsers;
import io.argos.server.testing.querydata.QueryTestData;
import io.argos.storage.clickhouse.Account;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Shared steps of the transaction API integration tests (WO-23 lane transactions-contract): the
 * shared query test data ({@link QueryTestData#shared()}: tiny generator data, real ClickHouse and
 * PostgreSQL), signed-in clients per role, and PostgreSQL rows for hand-made applications.
 *
 * <p>Hand-made applications of this lane use ids 950 to 989 (the generator never uses 900 and up;
 * other WO-23 tests use 900 to 949), each test class its own range.
 */
public final class TxApiFixture {

  private TxApiFixture() {}

  /** The shared data. */
  public static QueryTestData.Shared shared() {
    return QueryTestData.shared();
  }

  /** The environment of the shared data. */
  public static ServerTestEnvironment env() {
    return shared().env();
  }

  /** A browser signed in with a new user of {@code role}. */
  public static ApiTestClient client(Role role) {
    return client(role, false);
  }

  /** A browser signed in with a new user of {@code role}, with {@code pii.view} when asked. */
  public static ApiTestClient client(Role role, boolean piiView) {
    TestUsers users = new TestUsers(env());
    TestUsers.TestUser user =
        piiView
            ? users.create(role, o -> o.permission(ArgosPrincipal.PII_VIEW))
            : users.create(role);
    ApiTestClient client = shared().server().client();
    ApiResponse login = client.login(user.username(), user.password());
    if (login.status() != 200) {
      throw new IllegalStateException("login failed: " + login);
    }
    return client;
  }

  /**
   * An application with one instance in PostgreSQL (as the collector would register them).
   *
   * @param appId application id (950..989)
   * @param name application name
   * @param instanceId instance id
   * @param instanceName instance name
   * @param ipMode {@code full}, {@code masked}, {@code off}
   * @param userIdMode {@code plain}, {@code hashed}, {@code off}
   * @param sqlParamMode {@code masked}, {@code partial}, {@code full}
   */
  public static void app(
      int appId,
      String name,
      int instanceId,
      String instanceName,
      String ipMode,
      String userIdMode,
      String sqlParamMode)
      throws SQLException {
    try (Connection c = env().connect(PgAccount.APP)) {
      try (PreparedStatement ps =
          c.prepareStatement(
              "INSERT INTO argos.apps (id, name, env, apdex_t_ms, slow_threshold_ms)"
                  + " VALUES (?, ?, 'prod', 500, 1000) ON CONFLICT DO NOTHING")) {
        ps.setInt(1, appId);
        ps.setString(2, name);
        ps.executeUpdate();
      }
      instance(c, appId, instanceId, instanceName, ipMode, userIdMode, sqlParamMode);
    }
  }

  /** One more instance of an application. */
  public static void instance(
      int appId,
      int instanceId,
      String instanceName,
      String ipMode,
      String userIdMode,
      String sqlParamMode)
      throws SQLException {
    try (Connection c = env().connect(PgAccount.APP)) {
      instance(c, appId, instanceId, instanceName, ipMode, userIdMode, sqlParamMode);
    }
  }

  private static void instance(
      Connection c,
      int appId,
      int instanceId,
      String instanceName,
      String ipMode,
      String userIdMode,
      String sqlParamMode)
      throws SQLException {
    try (PreparedStatement ps =
        c.prepareStatement(
            "INSERT INTO argos.instances (id, app_id, name, host, pid, ip_mode, user_id_mode,"
                + " sql_param_mode) VALUES (?, ?, ?, 'host-a', 1, ?, ?, ?) ON CONFLICT DO"
                + " NOTHING")) {
      ps.setInt(1, instanceId);
      ps.setInt(2, appId);
      ps.setString(3, instanceName);
      ps.setString(4, ipMode);
      ps.setString(5, userIdMode);
      ps.setString(6, sqlParamMode);
      ps.executeUpdate();
    }
  }

  /** Dictionary texts of an application: kind ({@code service}, {@code sql}, ...) to keys. */
  public static void dictionary(int appId, String kind, Map<Long, String> entries)
      throws SQLException {
    try (Connection c = env().connect(PgAccount.APP);
        PreparedStatement ps =
            c.prepareStatement(
                "INSERT INTO argos.dictionary (app_id, kind, hash64, text) VALUES (?,"
                    + " ?::argos.dict_kind, ?, ?) ON CONFLICT DO NOTHING")) {
      for (Map.Entry<Long, String> e : entries.entrySet()) {
        ps.setInt(1, appId);
        ps.setString(2, kind);
        ps.setLong(3, e.getKey());
        ps.setString(4, e.getValue());
        ps.addBatch();
      }
      ps.executeBatch();
    }
  }

  /** A query of {@code argos_reader} as rows of strings. */
  public static List<List<String>> reader(String sql) {
    return env().clickHouse(Account.READER).rows(sql);
  }

  /** A count of {@code argos_reader}. */
  public static long count(String sql) {
    String value = env().clickHouse(Account.READER).scalar(sql).strip();
    return value.isEmpty() ? 0 : Long.parseLong(value);
  }

  /**
   * The finished ClickHouse statements of a request ({@code system.query_log}; flushed every 7.5
   * s): query id, user, query text.
   */
  public static List<List<String>> queryLog(String requestId, int expected) throws Exception {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(45);
    List<List<String>> rows = new ArrayList<>();
    while (System.nanoTime() < deadline) {
      rows =
          reader(
              "SELECT query_id, user, replaceAll(query, '\\n', ' ') FROM system.query_log WHERE"
                  + " type = 'QueryFinish' AND event_date >= yesterday() AND startsWith(query_id, '"
                  + requestId.replaceAll("[^A-Za-z0-9_-]", "")
                  + "-') ORDER BY event_time_microseconds");
      if (rows.size() >= expected) {
        return rows;
      }
      TimeUnit.MILLISECONDS.sleep(500);
    }
    return rows;
  }
}
