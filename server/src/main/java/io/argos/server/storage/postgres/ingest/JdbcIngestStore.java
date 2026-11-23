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
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.stereotype.Component;

/**
 * {@link IngestStore} on PostgreSQL with the runtime pool ({@code argos_app}, C-11): {@code
 * dictionary} (13 §12.3, kinds C-16 plus {@code pool}), {@code error_groups} (with the
 * representative stack, WO-27), {@code apps.apdex_t_ms}, {@code service_apdex_overrides} and the
 * {@code apdex_t_change} events (WO-27). Every write is one transaction of batched statements.
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

  /** Every app's overrides, at most {@code ?} per app (the rank cuts the rest). */
  private static final String OVERRIDES =
      "SELECT app_id, service_id, apdex_t_ms FROM (SELECT app_id, service_id, apdex_t_ms,"
          + " row_number() OVER (PARTITION BY app_id ORDER BY service_id) AS n"
          + " FROM argos.service_apdex_overrides) o WHERE n <= ? ORDER BY app_id, service_id";

  private static final String LOCK_APP =
      "SELECT apdex_t_ms, env FROM argos.apps WHERE id = ? FOR UPDATE";

  private static final String SET_APP_APDEX = "UPDATE argos.apps SET apdex_t_ms = ? WHERE id = ?";

  private static final String OVERRIDE_OF =
      "SELECT apdex_t_ms FROM argos.service_apdex_overrides WHERE app_id = ? AND service_id = ?";

  private static final String COUNT_OVERRIDES =
      "SELECT count(*) FROM argos.service_apdex_overrides WHERE app_id = ?";

  private static final String UPSERT_OVERRIDE =
      "INSERT INTO argos.service_apdex_overrides (app_id, service_id, apdex_t_ms, updated_at,"
          + " updated_by) VALUES (?, ?, ?, ?, ?) ON CONFLICT (app_id, service_id) DO UPDATE SET"
          + " apdex_t_ms = EXCLUDED.apdex_t_ms, updated_at = EXCLUDED.updated_at,"
          + " updated_by = EXCLUDED.updated_by";

  private static final String DELETE_OVERRIDE =
      "DELETE FROM argos.service_apdex_overrides WHERE app_id = ? AND service_id = ?";

  private static final String INSERT_EVENT =
      "INSERT INTO argos.events (id, app_id, env, instance_id, type, title, detail, ts, created_by)"
          + " VALUES (?, ?, ?, NULL, 'apdex_t_change', ?, CAST(? AS jsonb), ?, ?)";

  private static final String WITHOUT_STACK =
      "SELECT app_id, group_id FROM argos.error_groups WHERE sample_stack IS NULL"
          + " AND last_seen >= ? ORDER BY last_seen DESC, app_id, group_id LIMIT ?";

  private static final String DICTIONARY_TEXTS =
      "SELECT hash64, text FROM argos.dictionary WHERE app_id = ? AND kind = ?::argos.dict_kind"
          + " AND hash64 = ANY (?)";

  private static final String FILL_STACK =
      "UPDATE argos.error_groups SET sample_stack = ? WHERE app_id = ? AND group_id = ?"
          + " AND sample_stack IS NULL";

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

  @Override
  public Map<Integer, Map<Long, Integer>> serviceApdexOverrides(int perAppLimit)
      throws SQLException {
    Map<Integer, Map<Long, Integer>> values = new HashMap<>();
    try (Connection c = dataSource.getConnection();
        PreparedStatement st = c.prepareStatement(OVERRIDES)) {
      st.setInt(1, Math.max(perAppLimit, 0) + 1);
      try (ResultSet rs = st.executeQuery()) {
        while (rs.next()) {
          values
              .computeIfAbsent(rs.getInt(1), k -> new LinkedHashMap<>())
              .put(rs.getLong(2), rs.getInt(3));
        }
      }
    }
    return values;
  }

  @Override
  public Optional<ApdexChange> changeAppApdex(int appId, int tMs, UUID actor, Instant ts)
      throws SQLException {
    try (Connection c = dataSource.getConnection()) {
      boolean auto = c.getAutoCommit();
      c.setAutoCommit(false);
      try {
        AppRow app = lockApp(c, appId);
        if (app == null) {
          c.rollback();
          return Optional.empty();
        }
        ApdexChange change;
        if (app.apdexTMs() == tMs) {
          change = new ApdexChange(appId, null, tMs, tMs, null, ts);
        } else {
          try (PreparedStatement st = c.prepareStatement(SET_APP_APDEX)) {
            st.setInt(1, tMs);
            st.setInt(2, appId);
            st.executeUpdate();
          }
          change = new ApdexChange(appId, null, app.apdexTMs(), tMs, UUID.randomUUID(), ts);
          insertEvent(c, app.env(), change, actor);
        }
        c.commit();
        return Optional.of(change);
      } catch (SQLException | RuntimeException e) {
        c.rollback();
        throw e;
      } finally {
        c.setAutoCommit(auto);
      }
    }
  }

  @Override
  public Optional<ApdexChange> changeServiceApdex(
      int appId, long serviceId, Integer tMs, UUID actor, Instant ts, int maxPerApp)
      throws SQLException, OverrideLimitException {
    try (Connection c = dataSource.getConnection()) {
      boolean auto = c.getAutoCommit();
      c.setAutoCommit(false);
      try {
        AppRow app = lockApp(c, appId);
        if (app == null) {
          c.rollback();
          return Optional.empty();
        }
        Integer current = null;
        try (PreparedStatement st = c.prepareStatement(OVERRIDE_OF)) {
          st.setInt(1, appId);
          st.setLong(2, serviceId);
          try (ResultSet rs = st.executeQuery()) {
            if (rs.next()) {
              current = rs.getInt(1);
            }
          }
        }
        int before = current != null ? current : app.apdexTMs();
        int after = tMs != null ? tMs : app.apdexTMs();
        if (tMs == null) {
          if (current != null) {
            try (PreparedStatement st = c.prepareStatement(DELETE_OVERRIDE)) {
              st.setInt(1, appId);
              st.setLong(2, serviceId);
              st.executeUpdate();
            }
          }
        } else if (!tMs.equals(current)) {
          if (current == null && countOverrides(c, appId) >= maxPerApp) {
            c.rollback();
            throw new OverrideLimitException(
                "App " + appId + " already has " + maxPerApp + " service Apdex overrides");
          }
          try (PreparedStatement st = c.prepareStatement(UPSERT_OVERRIDE)) {
            st.setInt(1, appId);
            st.setLong(2, serviceId);
            st.setInt(3, tMs);
            st.setTimestamp(4, Timestamp.from(ts));
            st.setObject(5, actor);
            st.executeUpdate();
          }
        }
        ApdexChange change =
            new ApdexChange(
                appId, serviceId, before, after, before == after ? null : UUID.randomUUID(), ts);
        if (change.changed()) {
          insertEvent(c, app.env(), change, actor);
        }
        c.commit();
        return Optional.of(change);
      } catch (SQLException | RuntimeException e) {
        c.rollback();
        throw e;
      } finally {
        c.setAutoCommit(auto);
      }
    }
  }

  private record AppRow(int apdexTMs, String env) {}

  private static AppRow lockApp(Connection c, int appId) throws SQLException {
    try (PreparedStatement st = c.prepareStatement(LOCK_APP)) {
      st.setInt(1, appId);
      try (ResultSet rs = st.executeQuery()) {
        return rs.next() ? new AppRow(rs.getInt(1), rs.getString(2)) : null;
      }
    }
  }

  private static long countOverrides(Connection c, int appId) throws SQLException {
    try (PreparedStatement st = c.prepareStatement(COUNT_OVERRIDES)) {
      st.setInt(1, appId);
      try (ResultSet rs = st.executeQuery()) {
        return rs.next() ? rs.getLong(1) : 0;
      }
    }
  }

  private static void insertEvent(Connection c, String env, ApdexChange change, UUID actor)
      throws SQLException {
    try (PreparedStatement st = c.prepareStatement(INSERT_EVENT)) {
      st.setObject(1, change.eventId());
      st.setInt(2, change.appId());
      st.setString(3, env);
      st.setString(4, change.title());
      st.setString(5, change.detailJson());
      st.setTimestamp(6, Timestamp.from(change.ts()));
      st.setObject(7, actor);
      st.executeUpdate();
    }
  }

  @Override
  public List<StackCandidate> groupsWithoutStack(Instant lastSeenFrom, int limit)
      throws SQLException {
    List<StackCandidate> out = new ArrayList<>();
    try (Connection c = dataSource.getConnection();
        PreparedStatement st = c.prepareStatement(WITHOUT_STACK)) {
      st.setTimestamp(1, Timestamp.from(lastSeenFrom));
      st.setInt(2, limit);
      try (ResultSet rs = st.executeQuery()) {
        while (rs.next()) {
          out.add(new StackCandidate(rs.getInt(1), rs.getLong(2)));
        }
      }
    }
    return out;
  }

  @Override
  public Map<Long, String> dictionaryTexts(int appId, String kind, Collection<Long> hashes)
      throws SQLException {
    Map<Long, String> found = new HashMap<>();
    if (hashes.isEmpty()) {
      return found;
    }
    try (Connection c = dataSource.getConnection();
        PreparedStatement st = c.prepareStatement(DICTIONARY_TEXTS)) {
      Array array = c.createArrayOf("bigint", hashes.toArray(new Long[0]));
      try {
        st.setInt(1, appId);
        st.setString(2, kind);
        st.setArray(3, array);
        try (ResultSet rs = st.executeQuery()) {
          while (rs.next()) {
            found.put(rs.getLong(1), rs.getString(2));
          }
        }
      } finally {
        array.free();
      }
    }
    return found;
  }

  @Override
  public boolean fillSampleStack(int appId, long groupId, String stack) throws SQLException {
    try (Connection c = dataSource.getConnection();
        PreparedStatement st = c.prepareStatement(FILL_STACK)) {
      st.setString(1, stack);
      st.setInt(2, appId);
      st.setLong(3, groupId);
      return st.executeUpdate() == 1;
    }
  }
}
