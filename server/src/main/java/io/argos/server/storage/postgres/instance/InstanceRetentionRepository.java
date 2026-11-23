/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.storage.postgres.instance;

import io.argos.server.session.InstanceRetentionStore;
import java.sql.Array;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Set;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * {@link InstanceRetentionStore} over PostgreSQL {@code instances} (V001; 12 §4.3, 13 retention
 * table): the daily archive and purge of silent instances. The live instance ids are passed as an
 * array parameter (bounded by the collector's session cap).
 */
@Repository
public class InstanceRetentionRepository implements InstanceRetentionStore {

  /** Seconds a statement may take. */
  static final int QUERY_TIMEOUT_SECONDS = 60;

  private static final String ARCHIVE =
      "UPDATE argos.instances SET archived_at = ?, status = 'DOWN', warn_flags = NULL"
          + " WHERE archived_at IS NULL AND COALESCE(last_seen_at, first_seen_at) <= ?"
          + " AND NOT (id = ANY (?))";

  private static final String PURGE =
      "DELETE FROM argos.instances i WHERE i.archived_at IS NOT NULL"
          + " AND COALESCE(i.last_seen_at, i.first_seen_at) <= ? AND NOT (i.id = ANY (?))"
          + " AND NOT EXISTS (SELECT 1 FROM argos.commands c WHERE c.instance_id = i.id)";

  private final JdbcTemplate jdbc;

  public InstanceRetentionRepository(DataSource dataSource) {
    JdbcTemplate t = new JdbcTemplate(dataSource);
    t.setQueryTimeout(QUERY_TIMEOUT_SECONDS);
    this.jdbc = t;
  }

  @Override
  public int archiveSilentSince(Instant silentSince, Set<Integer> live, Instant now) {
    Integer n =
        jdbc.execute(
            (Connection con) -> {
              try (PreparedStatement ps = con.prepareStatement(ARCHIVE)) {
                ps.setTimestamp(1, Timestamp.from(now));
                ps.setTimestamp(2, Timestamp.from(silentSince));
                ps.setArray(3, ids(con, live));
                return ps.executeUpdate();
              }
            });
    return n == null ? 0 : n;
  }

  @Override
  public int purgeSilentSince(Instant silentSince, Set<Integer> live) {
    Integer n =
        jdbc.execute(
            (Connection con) -> {
              try (PreparedStatement ps = con.prepareStatement(PURGE)) {
                ps.setTimestamp(1, Timestamp.from(silentSince));
                ps.setArray(2, ids(con, live));
                return ps.executeUpdate();
              }
            });
    return n == null ? 0 : n;
  }

  private static Array ids(Connection con, Set<Integer> live) throws java.sql.SQLException {
    return con.createArrayOf("integer", live.toArray(new Integer[0]));
  }
}
