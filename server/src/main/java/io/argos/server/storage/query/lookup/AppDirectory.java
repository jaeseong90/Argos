/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.storage.query.lookup;

import io.argos.server.api.error.ApiException;
import io.argos.server.api.error.ErrorCode;
import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Reads the PostgreSQL rows that describe applications and instances for the transaction API
 * (WO-23): names, the slow threshold of the XLog status (14 §4.5), and the collection modes of an
 * instance ({@code ip_mode}, {@code user_id_mode}, {@code sql_param_mode}; 14 §7.3, C-152). Reads
 * only, as the runtime account; statements time out after 5 s and a failure is 503 {@code
 * storage_unavailable}.
 */
@Component
public class AppDirectory {

  private static final Logger LOG = LoggerFactory.getLogger(AppDirectory.class);

  /** Keys per statement. */
  static final int BATCH = 1_000;

  private static final String APPS =
      "SELECT id, name, env, slow_threshold_ms, apdex_t_ms FROM argos.apps WHERE id = ANY(?)";

  private static final String INSTANCES =
      "SELECT id, app_id, name, ip_mode, user_id_mode, sql_param_mode FROM argos.instances"
          + " WHERE id = ANY(?)";

  private static final String INSTANCES_OF_APP =
      "SELECT id, app_id, name, ip_mode, user_id_mode, sql_param_mode FROM argos.instances"
          + " WHERE app_id = ? ORDER BY id LIMIT 10000";

  private static final String SEARCH_INSTANCES =
      "SELECT i.id, i.app_id, i.name, i.ip_mode, i.user_id_mode, i.sql_param_mode"
          + " FROM argos.instances i JOIN argos.apps a ON a.id = i.app_id"
          + " WHERE i.name ILIKE ? ESCAPE '\\' AND (? = 0 OR i.app_id = ?)"
          + " AND (? = '' OR a.env = ?) ORDER BY i.name, i.id LIMIT ?";

  /**
   * An application.
   *
   * @param appId {@code apps.id}
   * @param name name
   * @param env environment
   * @param slowThresholdMs the XLog slow threshold (14 §4.5)
   * @param apdexTMs Apdex T
   */
  public record AppInfo(int appId, String name, String env, int slowThresholdMs, int apdexTMs) {}

  /**
   * An instance.
   *
   * @param instanceId {@code instances.id}
   * @param appId its application
   * @param name the stored name (C-157 (4): as stored)
   * @param ipMode {@code full}, {@code masked}, {@code off}, or null when unknown
   * @param userIdMode {@code plain}, {@code hashed}, {@code off}, or null
   * @param sqlParamMode {@code masked}, {@code partial}, {@code full}, or null
   */
  public record InstanceInfo(
      int instanceId,
      int appId,
      String name,
      String ipMode,
      String userIdMode,
      String sqlParamMode) {}

  private final JdbcTemplate jdbc;

  public AppDirectory(DataSource dataSource) {
    JdbcTemplate t = new JdbcTemplate(dataSource);
    t.setQueryTimeout(5);
    this.jdbc = t;
  }

  /** The application {@code appId}. */
  public Optional<AppInfo> app(int appId) {
    return Optional.ofNullable(apps(List.of(appId)).get(appId));
  }

  /** The applications of {@code ids} that exist, by id. */
  public Map<Integer, AppInfo> apps(Collection<Integer> ids) {
    Map<Integer, AppInfo> out = new LinkedHashMap<>();
    for (List<Integer> batch : batches(ids)) {
      query(
          APPS,
          batch,
          rs ->
              out.put(
                  rs.getInt(1),
                  new AppInfo(
                      rs.getInt(1), rs.getString(2), rs.getString(3), rs.getInt(4), rs.getInt(5))));
    }
    return out;
  }

  /** The instances of {@code ids} that exist, by id. */
  public Map<Integer, InstanceInfo> instances(Collection<Integer> ids) {
    Map<Integer, InstanceInfo> out = new LinkedHashMap<>();
    for (List<Integer> batch : batches(ids)) {
      query(INSTANCES, batch, rs -> out.put(rs.getInt(1), instance(rs)));
    }
    return out;
  }

  /** The instances of an application (at most 10,000), by id. */
  public Map<Integer, InstanceInfo> instancesOfApp(int appId) {
    Map<Integer, InstanceInfo> out = new LinkedHashMap<>();
    try {
      jdbc.query(
          INSTANCES_OF_APP,
          ps -> ps.setInt(1, appId),
          rs -> {
            out.put(rs.getInt(1), instance(rs));
          });
    } catch (DataAccessException e) {
      throw unavailable(e);
    }
    return out;
  }

  /** The {@code user_id_mode} values of the instances of an application (C-152). */
  public Set<String> userIdModes(int appId) {
    Set<String> modes = new LinkedHashSet<>();
    for (InstanceInfo i : instancesOfApp(appId).values()) {
      if (i.userIdMode() != null) {
        modes.add(i.userIdMode());
      }
    }
    return modes;
  }

  /**
   * Instances whose name contains {@code text} (case-insensitive), for the global search (14
   * §4.11).
   *
   * @param text the searched text, matched literally
   * @param appId restrict to an application, 0 for all
   * @param env restrict to an environment, empty for all
   * @param limit most rows
   */
  public List<InstanceInfo> searchInstances(String text, int appId, String env, int limit) {
    List<InstanceInfo> out = new ArrayList<>();
    String pattern = "%" + DictionaryTextSearch.escapeLike(text) + "%";
    String e = env == null ? "" : env;
    try {
      jdbc.query(
          SEARCH_INSTANCES,
          ps -> {
            ps.setString(1, pattern);
            ps.setInt(2, appId);
            ps.setInt(3, appId);
            ps.setString(4, e);
            ps.setString(5, e);
            ps.setInt(6, limit);
          },
          rs -> {
            out.add(instance(rs));
          });
    } catch (DataAccessException ex) {
      throw unavailable(ex);
    }
    return out;
  }

  private static InstanceInfo instance(ResultSet rs) throws SQLException {
    return new InstanceInfo(
        rs.getInt(1),
        rs.getInt(2),
        rs.getString(3),
        rs.getString(4),
        rs.getString(5),
        rs.getString(6));
  }

  private interface RowHandler {
    void row(ResultSet rs) throws SQLException;
  }

  private void query(String sql, List<Integer> ids, RowHandler handler) {
    try {
      jdbc.query(
          sql,
          ps -> {
            Array array = ps.getConnection().createArrayOf("integer", ids.toArray());
            ps.setArray(1, array);
          },
          rs -> {
            handler.row(rs);
          });
    } catch (DataAccessException e) {
      throw unavailable(e);
    }
  }

  private static List<List<Integer>> batches(Collection<Integer> ids) {
    List<Integer> distinct = new ArrayList<>(new LinkedHashSet<>(ids));
    distinct.removeIf(id -> id == null);
    List<List<Integer>> out = new ArrayList<>();
    for (int from = 0; from < distinct.size(); from += BATCH) {
      out.add(distinct.subList(from, Math.min(distinct.size(), from + BATCH)));
    }
    return out;
  }

  private static ApiException unavailable(DataAccessException e) {
    LOG.warn("Cannot read applications or instances from PostgreSQL: {}", e.getClass().getName());
    return new ApiException(ErrorCode.STORAGE_UNAVAILABLE);
  }
}
