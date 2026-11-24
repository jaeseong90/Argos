/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.storage.postgres.app;

import java.sql.Array;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Application and instance administration in PostgreSQL (WO-26; 14 §4.3, ADR-0010 C-153, V012):
 * creation, the settings of {@code PATCH /apps/{appId}} that are not the Apdex T ({@code
 * ingest.ApdexChangeService} writes that one with its event), the soft delete of an application,
 * and the archive and delete of an instance.
 *
 * <p>A deleted application keeps its row ({@code deleted_at}, V012): instances, agent tokens and
 * alert rules reference it without cascade, and ClickHouse keeps its rows until their TTL. The
 * delete appends {@code #deleted-<id>} to the stored name so that (name, env) can be registered
 * again, archives its instances, marks its recommended rules as applied (the setup job never
 * creates rules for it) and removes the rows that a hard delete would cascade: its approval
 * requests ({@code pending_registrations.home_app_id}), service Apdex overrides and mutes (02 data
 * classification: "앱 삭제 시 CASCADE").
 */
@Repository
public class AppAdminRepository {

  /** Seconds a statement may take. */
  static final int QUERY_TIMEOUT_SECONDS = 10;

  /** Seconds a transaction may take before it is rolled back. */
  static final int TRANSACTION_TIMEOUT_SECONDS = 20;

  /** {@code apps.app_group VARCHAR(64)}. */
  public static final int MAX_GROUP_CHARS = 64;

  /** The suffix that a delete appends to the stored name: {@code #deleted-<id>}. */
  static final String DELETED_SUFFIX = "#deleted-";

  private static final String INSERT =
      "INSERT INTO argos.apps (name, env, app_group, apdex_t_ms, auto_register)"
          + " VALUES (?, ?, ?, ?, ?) ON CONFLICT (name, env) DO NOTHING";

  private static final String FIND_ID =
      "SELECT id FROM argos.apps WHERE name = ? AND env = ? AND deleted_at IS NULL";

  private static final String LOCK_LIVE =
      "SELECT id, name, env FROM argos.apps WHERE id = ? AND deleted_at IS NULL FOR UPDATE";

  private static final String SET_GROUP = "UPDATE argos.apps SET app_group = ? WHERE id = ?";

  private static final String SET_AUTO_REGISTER =
      "UPDATE argos.apps SET auto_register = ? WHERE id = ?";

  private static final String SET_PACKAGES = "UPDATE argos.apps SET app_packages = ? WHERE id = ?";

  private static final String SOFT_DELETE =
      "UPDATE argos.apps SET deleted_at = ?, recommended_rules_applied = TRUE,"
          + " name = left(name, ? - length(?)) || ? WHERE id = ?";

  private static final String ARCHIVE_APP_INSTANCES =
      "UPDATE argos.instances SET archived_at = COALESCE(archived_at, ?), status = 'DOWN',"
          + " warn_flags = NULL WHERE app_id = ?";

  private static final String DELETE_REQUESTS =
      "DELETE FROM argos.pending_registrations WHERE home_app_id = ?";

  private static final String DELETE_OVERRIDES =
      "DELETE FROM argos.service_apdex_overrides WHERE app_id = ?";

  private static final String DELETE_MUTES = "DELETE FROM argos.mutes WHERE app_id = ?";

  private static final String ARCHIVE_INSTANCE =
      "UPDATE argos.instances SET archived_at = ?, status = 'DOWN', warn_flags = NULL"
          + " WHERE id = ? AND archived_at IS NULL";

  private static final String LOCK_INSTANCE =
      "SELECT id, app_id, name, archived_at FROM argos.instances WHERE id = ? FOR UPDATE";

  private static final String DELETE_INSTANCE_COMMANDS =
      "DELETE FROM argos.commands WHERE instance_id = ?";

  private static final String DELETE_INSTANCE = "DELETE FROM argos.instances WHERE id = ?";

  private final JdbcTemplate jdbc;
  private final TransactionTemplate transactions;

  public AppAdminRepository(DataSource dataSource) {
    JdbcTemplate t = new JdbcTemplate(dataSource);
    t.setQueryTimeout(QUERY_TIMEOUT_SECONDS);
    this.jdbc = t;
    TransactionTemplate tx = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
    tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    tx.setTimeout(TRANSACTION_TIMEOUT_SECONDS);
    this.transactions = tx;
  }

  /**
   * Creates the application (name, env).
   *
   * @param group {@code app_group}, or null
   * @param apdexTMs Apdex T
   * @param autoRegister {@code auto_register}: true {@code auto}, false {@code approval}, null none
   *     (the environment default applies, ADR-0010 C-231 (7))
   * @return the new id, empty when a live application (name, env) exists already
   */
  public Optional<Integer> create(
      String name, String env, String group, int apdexTMs, Boolean autoRegister) {
    if (name.length() > AppRepository.MAX_NAME_CHARS
        || env.length() > AppRepository.MAX_ENV_CHARS
        || (group != null && group.length() > MAX_GROUP_CHARS)) {
      throw new IllegalArgumentException("A value is longer than its column");
    }
    Integer id =
        transactions.execute(
            status -> {
              int inserted = jdbc.update(INSERT, name, env, group, apdexTMs, autoRegister);
              if (inserted == 0) {
                return null;
              }
              return jdbc.queryForObject(FIND_ID, Integer.class, name, env);
            });
    return Optional.ofNullable(id);
  }

  /**
   * Applies the settings of a {@code PATCH}; null arguments stay as they are. One transaction.
   *
   * @param setGroup whether the group changes
   * @param group the new group, null to clear it (ignored unless {@code setGroup})
   * @param autoRegister the new {@code auto_register}, or null
   * @param packages the new {@code app_packages}, or null
   * @return false when the application does not exist or is deleted
   */
  public boolean update(
      int appId, boolean setGroup, String group, Boolean autoRegister, List<String> packages) {
    Boolean found =
        transactions.execute(
            status -> {
              if (jdbc.query(LOCK_LIVE, (rs, row) -> rs.getInt(1), appId).isEmpty()) {
                return false;
              }
              if (setGroup) {
                jdbc.update(SET_GROUP, group, appId);
              }
              if (autoRegister != null) {
                jdbc.update(SET_AUTO_REGISTER, autoRegister, appId);
              }
              if (packages != null) {
                jdbc.execute(
                    (Connection con) -> {
                      try (PreparedStatement ps = con.prepareStatement(SET_PACKAGES)) {
                        Array array = con.createArrayOf("text", packages.toArray());
                        ps.setArray(1, array);
                        ps.setInt(2, appId);
                        return ps.executeUpdate();
                      }
                    });
              }
              return true;
            });
    return Boolean.TRUE.equals(found);
  }

  /**
   * Soft-deletes the application (see the class comment). One transaction; the row is locked first.
   *
   * @return the name and environment it had, empty when it does not exist or is deleted already
   */
  public Optional<DeletedApp> softDelete(int appId, Instant now) {
    Timestamp ts = Timestamp.from(Objects.requireNonNull(now, "now"));
    DeletedApp deleted =
        transactions.execute(
            status -> {
              List<DeletedApp> rows =
                  jdbc.query(
                      LOCK_LIVE,
                      (rs, row) -> new DeletedApp(rs.getInt(1), rs.getString(2), rs.getString(3)),
                      appId);
              if (rows.isEmpty()) {
                return null;
              }
              String suffix = DELETED_SUFFIX + appId;
              jdbc.update(SOFT_DELETE, ts, AppRepository.MAX_NAME_CHARS, suffix, suffix, appId);
              jdbc.update(ARCHIVE_APP_INSTANCES, ts, appId);
              jdbc.update(DELETE_REQUESTS, appId);
              jdbc.update(DELETE_OVERRIDES, appId);
              jdbc.update(DELETE_MUTES, appId);
              return rows.get(0);
            });
    return Optional.ofNullable(deleted);
  }

  /**
   * Archives instance {@code instanceId} ({@code archived_at} = now, {@code DOWN}, no WARN flags).
   *
   * @return false when it was archived already
   */
  public boolean archiveInstance(int instanceId, Instant now) {
    return jdbc.update(ARCHIVE_INSTANCE, Timestamp.from(now), instanceId) > 0;
  }

  /**
   * Deletes instance {@code instanceId} if it is archived, with its commands (and their results,
   * {@code ON DELETE CASCADE}); its events and ClickHouse rows stay until their retention ends
   * (ADR-0010 C-215 (5)). One transaction; the row is locked first.
   */
  public InstanceDeletion deleteInstance(int instanceId) {
    InstanceDeletion result =
        transactions.execute(
            status -> {
              List<Boolean> archived =
                  jdbc.query(
                      LOCK_INSTANCE,
                      (ResultSet rs, int row) -> rs.getTimestamp(4) != null,
                      instanceId);
              if (archived.isEmpty()) {
                return InstanceDeletion.NOT_FOUND;
              }
              if (!archived.get(0)) {
                return InstanceDeletion.NOT_ARCHIVED;
              }
              jdbc.update(DELETE_INSTANCE_COMMANDS, instanceId);
              jdbc.update(DELETE_INSTANCE, instanceId);
              return InstanceDeletion.DELETED;
            });
    return Objects.requireNonNull(result, "deletion");
  }

  /**
   * A soft-deleted application as it was.
   *
   * @param id id
   * @param name its name before the delete
   * @param env its environment
   */
  public record DeletedApp(int id, String name, String env) {}

  /** The outcome of {@link #deleteInstance}. */
  public enum InstanceDeletion {
    /** The instance and its commands are gone. */
    DELETED,
    /** No such instance. */
    NOT_FOUND,
    /** The instance is not archived: nothing changed. */
    NOT_ARCHIVED
  }
}
