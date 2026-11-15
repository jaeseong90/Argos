/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.storage.postgres.ingest;

import io.argos.server.ingest.IngestStore;
import java.sql.Array;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import javax.sql.DataSource;
import org.springframework.stereotype.Component;

/**
 * {@link IngestStore} on PostgreSQL with the runtime pool ({@code argos_app}, C-11): {@code
 * dictionary} (13 §12.3, kinds C-16 plus {@code pool}), {@code error_groups} and {@code
 * apps.apdex_t_ms}. Every write is one transaction of batched statements.
 */
@Component
public class JdbcIngestStore implements IngestStore {

  private static final String UPSERT_DICTIONARY =
      "INSERT INTO argos.dictionary (app_id, kind, hash64, text, truncated, norm_version)"
          + " VALUES (?, ?::argos.dict_kind, ?, ?, ?, ?)"
          + " ON CONFLICT (app_id, kind, hash64) DO UPDATE SET last_seen = now()";

  private static final String EXISTING_DICTIONARY =
      "SELECT hash64 FROM argos.dictionary WHERE app_id = ? AND kind = ?::argos.dict_kind"
          + " AND hash64 = ANY (?)";

  private static final String COUNT_DICTIONARY =
      "SELECT count(*) FROM argos.dictionary WHERE app_id = ?";

  /**
   * One error group: the class and top frame texts come from the dictionary when it has them (the
   * group id never depends on them, ADR-0010 C-131); an existing row keeps its texts and pattern
   * and gets the wider time range and the added count.
   */
  private static final String UPSERT_ERROR_GROUP =
      "INSERT INTO argos.error_groups (app_id, group_id, error_class, message_pattern, top_frame,"
          + " first_seen, last_seen, count_total) VALUES (?, ?,"
          + " (SELECT left(d.text, 512) FROM argos.dictionary d"
          + "   WHERE d.app_id = ? AND d.kind = 'class' AND d.hash64 = ? AND ? <> 0),"
          + " ?,"
          + " (SELECT left(d.text, 1024) FROM argos.dictionary d"
          + "   WHERE d.app_id = ? AND d.kind = 'frame' AND d.hash64 = ? AND ? <> 0),"
          + " ?, ?, ?)"
          + " ON CONFLICT (app_id, group_id) DO UPDATE SET"
          + " first_seen = LEAST(argos.error_groups.first_seen, EXCLUDED.first_seen),"
          + " last_seen = GREATEST(argos.error_groups.last_seen, EXCLUDED.last_seen),"
          + " count_total = argos.error_groups.count_total + EXCLUDED.count_total,"
          + " error_class = COALESCE(argos.error_groups.error_class, EXCLUDED.error_class),"
          + " top_frame = COALESCE(argos.error_groups.top_frame, EXCLUDED.top_frame),"
          + " message_pattern = COALESCE(argos.error_groups.message_pattern,"
          + " EXCLUDED.message_pattern)";

  private static final String APDEX = "SELECT id, apdex_t_ms FROM argos.apps";

  private final DataSource dataSource;

  /**
   * @param dataSource the runtime pool ({@code argos_app})
   */
  public JdbcIngestStore(DataSource dataSource) {
    this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
  }

  @Override
  public void upsertDictionary(List<DictionaryRow> rows) throws SQLException {
    if (rows.isEmpty()) {
      return;
    }
    try (Connection c = dataSource.getConnection()) {
      boolean auto = c.getAutoCommit();
      c.setAutoCommit(false);
      try (PreparedStatement st = c.prepareStatement(UPSERT_DICTIONARY)) {
        for (DictionaryRow row : rows) {
          st.setInt(1, row.appId());
          st.setString(2, row.kind());
          st.setLong(3, row.hash());
          st.setString(4, row.text());
          st.setBoolean(5, row.truncated());
          if (row.normVersion() == null) {
            st.setNull(6, Types.SMALLINT);
          } else {
            st.setShort(6, (short) Math.min(row.normVersion(), Short.MAX_VALUE));
          }
          st.addBatch();
        }
        st.executeBatch();
        c.commit();
      } catch (SQLException e) {
        c.rollback();
        throw e;
      } finally {
        c.setAutoCommit(auto);
      }
    }
  }

  @Override
  public Set<Long> existingDictionary(int appId, String kind, Collection<Long> hashes)
      throws SQLException {
    Set<Long> found = new HashSet<>();
    if (hashes.isEmpty()) {
      return found;
    }
    try (Connection c = dataSource.getConnection();
        PreparedStatement st = c.prepareStatement(EXISTING_DICTIONARY)) {
      Array array = c.createArrayOf("bigint", hashes.toArray(new Long[0]));
      try {
        st.setInt(1, appId);
        st.setString(2, kind);
        st.setArray(3, array);
        try (ResultSet rs = st.executeQuery()) {
          while (rs.next()) {
            found.add(rs.getLong(1));
          }
        }
      } finally {
        array.free();
      }
    }
    return found;
  }

  @Override
  public long dictionaryCount(int appId) throws SQLException {
    try (Connection c = dataSource.getConnection();
        PreparedStatement st = c.prepareStatement(COUNT_DICTIONARY)) {
      st.setInt(1, appId);
      try (ResultSet rs = st.executeQuery()) {
        return rs.next() ? rs.getLong(1) : 0;
      }
    }
  }

  @Override
  public void upsertErrorGroups(List<ErrorGroupRow> rows) throws SQLException {
    if (rows.isEmpty()) {
      return;
    }
    try (Connection c = dataSource.getConnection()) {
      boolean auto = c.getAutoCommit();
      c.setAutoCommit(false);
      try (PreparedStatement st = c.prepareStatement(UPSERT_ERROR_GROUP)) {
        for (ErrorGroupRow row : rows) {
          int i = 1;
          st.setInt(i++, row.appId());
          st.setLong(i++, row.groupId());
          st.setInt(i++, row.appId());
          st.setLong(i++, row.errorClassId());
          st.setLong(i++, row.errorClassId());
          st.setString(i++, row.messagePattern());
          st.setInt(i++, row.appId());
          st.setLong(i++, row.topFrameId());
          st.setLong(i++, row.topFrameId());
          st.setTimestamp(i++, Timestamp.from(row.firstSeen()));
          st.setTimestamp(i++, Timestamp.from(row.lastSeen()));
          st.setLong(i, row.count());
          st.addBatch();
        }
        st.executeBatch();
        c.commit();
      } catch (SQLException e) {
        c.rollback();
        throw e;
      } finally {
        c.setAutoCommit(auto);
      }
    }
  }

  @Override
  public Map<Integer, Integer> apdexByApp() throws SQLException {
    Map<Integer, Integer> values = new HashMap<>();
    try (Connection c = dataSource.getConnection();
        PreparedStatement st = c.prepareStatement(APDEX);
        ResultSet rs = st.executeQuery()) {
      while (rs.next()) {
        values.put(rs.getInt(1), rs.getInt(2));
      }
    }
    return values;
  }
}
