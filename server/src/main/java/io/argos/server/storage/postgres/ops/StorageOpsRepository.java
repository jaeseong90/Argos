/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.storage.postgres.ops;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * The PostgreSQL side of the storage health job and the emergency retention (WO-28; 13 §8, F-S23):
 * the {@code events} rows of type {@value #EMERGENCY_TYPE} (one per dropped partition, server-wide:
 * {@code app_id} NULL) and the last observed TTL run ({@code settings} key {@value #TTL_LAST_RUN},
 * a JSON string with an ISO-8601 instant), as the runtime account.
 */
@Repository
public class StorageOpsRepository {

  /** {@code events.type} of an emergency drop (V001 comment). */
  public static final String EMERGENCY_TYPE = "retention_emergency";

  /** {@code settings} key of the last observed TTL run. */
  public static final String TTL_LAST_RUN = "storage_ttl_last_run_at";

  /** Seconds a statement may take. */
  static final int QUERY_TIMEOUT_SECONDS = 5;

  private final JdbcTemplate jdbc;

  public StorageOpsRepository(DataSource dataSource) {
    JdbcTemplate t = new JdbcTemplate(dataSource);
    t.setQueryTimeout(QUERY_TIMEOUT_SECONDS);
    this.jdbc = t;
  }

  /**
   * Inserts one server-wide {@value #EMERGENCY_TYPE} event.
   *
   * @param title short text (table and day)
   * @param detailJson the {@code detail} JSON object text
   */
  public void insertEmergencyEvent(UUID id, String title, String detailJson, Instant ts) {
    jdbc.update(
        "INSERT INTO argos.events (id, app_id, env, instance_id, type, title, detail, ts)"
            + " VALUES (?, NULL, NULL, NULL, ?, ?, CAST(? AS jsonb), ?)",
        id,
        EMERGENCY_TYPE,
        title,
        detailJson,
        Timestamp.from(ts));
  }

  /** Stores the last observed TTL run. */
  public void saveTtlLastRun(Instant at) {
    jdbc.update(
        "INSERT INTO argos.settings (key, value, updated_by, updated_at)"
            + " VALUES (?, to_jsonb(CAST(? AS text)), NULL, now())"
            + " ON CONFLICT (key) DO UPDATE SET value = EXCLUDED.value, updated_by = NULL,"
            + " updated_at = now()",
        TTL_LAST_RUN,
        at.toString());
  }

  /** The last observed TTL run, or empty when none was stored (or the value is not an instant). */
  public Optional<Instant> ttlLastRun() {
    List<String> values =
        jdbc.queryForList(
            "SELECT value #>> '{}' FROM argos.settings WHERE key = ?", String.class, TTL_LAST_RUN);
    if (values.isEmpty() || values.get(0) == null) {
      return Optional.empty();
    }
    try {
      return Optional.of(Instant.parse(values.get(0)));
    } catch (DateTimeParseException e) {
      return Optional.empty();
    }
  }
}
