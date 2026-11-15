/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.testing.querydata;

import io.argos.storage.generator.RunRange;
import io.argos.storage.generator.model.Dictionary;
import io.argos.storage.generator.model.Topology;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Locale;

/**
 * Seeds the PostgreSQL rows that describe the generated estate, as the collector would have written
 * them (WO-21): {@code apps} and {@code instances} with the generator's ids (the ClickHouse rows
 * carry them), the {@code dictionary} texts of the recorded keys and the {@code error_groups}. Runs
 * as the runtime account {@code argos_app} (DML only); the id sequences are advanced past the
 * seeded ids with {@code nextval} (no {@code setval} privilege needed), so an agent that registers
 * afterwards gets a fresh id. Every insert is idempotent ({@code ON CONFLICT DO NOTHING}).
 */
final class PgSeed {

  private static final int BATCH = 500;

  private PgSeed() {}

  /** Instance name of the generator's instance {@code i} (index in app {@code j}). */
  static String instanceName(Topology.Instance i) {
    return i.app.name + "-" + (i.indexInApp + 1) + ":8080";
  }

  /**
   * Seeds everything.
   *
   * @return the dictionary rows inserted (or already present)
   */
  static int seed(Connection c, Topology topology, RunRange range) throws SQLException {
    boolean auto = c.getAutoCommit();
    c.setAutoCommit(false);
    try {
      apps(c, topology);
      instances(c, topology, range);
      int entries = dictionary(c, topology);
      errorGroups(c, topology, range);
      c.commit();
      advance(c, "argos.apps", "argos.apps_id_seq");
      advance(c, "argos.instances", "argos.instances_id_seq");
      c.commit();
      return entries;
    } catch (SQLException | RuntimeException e) {
      c.rollback();
      throw e;
    } finally {
      c.setAutoCommit(auto);
    }
  }

  private static void apps(Connection c, Topology topology) throws SQLException {
    String sql =
        "INSERT INTO argos.apps (id, name, env, apdex_t_ms, slow_threshold_ms, auto_register)"
            + " VALUES (?, ?, ?, ?, ?, TRUE) ON CONFLICT DO NOTHING";
    try (PreparedStatement ps = c.prepareStatement(sql)) {
      for (Topology.App app : topology.apps) {
        ps.setInt(1, app.id);
        ps.setString(2, app.name);
        ps.setString(3, app.env);
        ps.setInt(4, app.apdexTMs);
        ps.setInt(5, topology.config.slowThresholdMs);
        ps.addBatch();
      }
      ps.executeBatch();
    }
  }

  private static void instances(Connection c, Topology topology, RunRange range)
      throws SQLException {
    String sql =
        "INSERT INTO argos.instances (id, app_id, name, host, pid, jvm_version, jvm_vendor, os,"
            + " cgroup, virtual_threads, agent_version, protocol_version, version_tag, ip_mode,"
            + " user_id_mode, query_string_mode, sql_param_mode, first_seen_at, last_seen_at,"
            + " started_at) VALUES (?, ?, ?, ?, ?, '21.0.8', 'Eclipse Adoptium', 'Linux', 'v2', ?,"
            + " '0.0.0-SNAPSHOT', 1, ?, 'masked', 'hashed', 'masked', 'masked', ?, ?, ?)"
            + " ON CONFLICT DO NOTHING";
    Timestamp start = Timestamp.from(Instant.ofEpochMilli(range.start()));
    Timestamp end = Timestamp.from(Instant.ofEpochMilli(range.end()));
    try (PreparedStatement ps = c.prepareStatement(sql)) {
      for (Topology.Instance i : topology.instances) {
        ps.setInt(1, i.id);
        ps.setInt(2, i.app.id);
        ps.setString(3, instanceName(i));
        ps.setString(4, i.app.name + "-" + (i.indexInApp + 1));
        ps.setInt(5, 1000 + i.id);
        ps.setBoolean(6, i.app.virtualThreads);
        ps.setString(7, "1.0." + i.app.index);
        ps.setTimestamp(8, start);
        ps.setTimestamp(9, end);
        ps.setTimestamp(10, start);
        ps.addBatch();
      }
      ps.executeBatch();
    }
  }

  /**
   * The recorded dictionary entries; an entry of app 0 (shared external hosts) is written for every
   * app, as each app's agents would send it.
   */
  private static int dictionary(Connection c, Topology topology) throws SQLException {
    String sql =
        "INSERT INTO argos.dictionary (app_id, kind, hash64, text) VALUES (?, ?::argos.dict_kind,"
            + " ?, ?) ON CONFLICT DO NOTHING";
    int rows = 0;
    try (PreparedStatement ps = c.prepareStatement(sql)) {
      int pending = 0;
      for (Dictionary.Entry e : topology.dictionary.entries()) {
        String kind = e.kind().name().toLowerCase(Locale.ROOT);
        if (e.appId() == 0) {
          for (Topology.App app : topology.apps) {
            add(ps, app.id, kind, e.id(), e.text());
            pending++;
          }
        } else {
          add(ps, e.appId(), kind, e.id(), e.text());
          pending++;
        }
        if (pending >= BATCH) {
          rows += inserted(ps.executeBatch());
          pending = 0;
        }
      }
      if (pending > 0) {
        rows += inserted(ps.executeBatch());
      }
    }
    return rows;
  }

  /**
   * Rows a batch inserted: the generator records some texts more than once (same key), which {@code
   * ON CONFLICT DO NOTHING} skips.
   */
  private static int inserted(int[] counts) {
    int n = 0;
    for (int c : counts) {
      n += Math.max(0, c);
    }
    return n;
  }

  private static void add(PreparedStatement ps, int appId, String kind, long hash, String text)
      throws SQLException {
    ps.setInt(1, appId);
    ps.setString(2, kind);
    ps.setLong(3, hash);
    ps.setString(4, text);
    ps.addBatch();
  }

  private static void errorGroups(Connection c, Topology topology, RunRange range)
      throws SQLException {
    String sql =
        "INSERT INTO argos.error_groups (app_id, group_id, error_class, message_pattern,"
            + " first_seen, last_seen, count_total) VALUES (?, ?, ?, ?, ?, ?, 0)"
            + " ON CONFLICT DO NOTHING";
    Timestamp start = Timestamp.from(Instant.ofEpochMilli(range.start()));
    Timestamp end = Timestamp.from(Instant.ofEpochMilli(range.end()));
    try (PreparedStatement ps = c.prepareStatement(sql)) {
      for (Topology.App app : topology.apps) {
        for (Topology.ErrorGroup g : app.errorGroups) {
          ps.setInt(1, app.id);
          ps.setLong(2, g.id());
          ps.setString(3, g.className());
          ps.setString(4, g.message());
          ps.setTimestamp(5, start);
          ps.setTimestamp(6, end);
          ps.addBatch();
        }
      }
      ps.executeBatch();
    }
  }

  /**
   * Moves {@code sequence} to at least the largest id of {@code table}, so that its next value is
   * free: {@code nextval} only (USAGE privilege of {@code argos_app}; no {@code setval}, no SELECT
   * on the sequence). Both names are constants of this class.
   */
  private static void advance(Connection c, String table, String sequence) throws SQLException {
    try (Statement st = c.createStatement()) {
      st.execute(
          "DO $$ DECLARE m bigint; v bigint; BEGIN SELECT COALESCE(max(id), 0) INTO m FROM "
              + table
              + "; LOOP v := nextval('"
              + sequence
              + "'); EXIT WHEN v >= m; END LOOP; END $$");
    }
  }
}
