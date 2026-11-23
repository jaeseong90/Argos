/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.storage.postgres.app;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Deploy markers and custom events written through the API, PostgreSQL {@code events} (V001; 14
 * §4.12, ADR-0010 C-153, 02 C-12): {@code POST /events} and {@code DELETE /events/{eventId}}. The
 * automatic events (start, stop, state, config, Apdex) are written by their own services.
 */
@Repository
public class EventWriteRepository {

  /** Seconds a statement may take. */
  static final int QUERY_TIMEOUT_SECONDS = 5;

  private static final String INSERT =
      "INSERT INTO argos.events (id, app_id, env, type, title, detail, version, ts, created_by)"
          + " VALUES (?, ?, ?, ?, ?, CAST(? AS jsonb), ?, ?, ?)";

  private static final String FIND = "SELECT app_id, type FROM argos.events WHERE id = ?";

  private static final String DELETE = "DELETE FROM argos.events WHERE id = ? AND type = ?";

  private final JdbcTemplate jdbc;

  public EventWriteRepository(DataSource dataSource) {
    JdbcTemplate t = new JdbcTemplate(dataSource);
    t.setQueryTimeout(QUERY_TIMEOUT_SECONDS);
    this.jdbc = t;
  }

  /**
   * Inserts one event.
   *
   * @param detailJson the {@code detail} JSON text, or null
   * @param createdBy the user who created it, or null (an agent token)
   */
  public void insert(
      UUID id,
      int appId,
      String env,
      String type,
      String title,
      @Nullable String detailJson,
      @Nullable String version,
      Instant ts,
      @Nullable UUID createdBy) {
    jdbc.update(
        INSERT, id, appId, env, type, title, detailJson, version, Timestamp.from(ts), createdBy);
  }

  /** The application and stored type of event {@code id}. */
  public Optional<StoredEvent> find(UUID id) {
    return jdbc
        .query(
            FIND,
            (rs, row) -> {
              int app = rs.getInt(1);
              return new StoredEvent(rs.wasNull() ? null : app, rs.getString(2));
            },
            id)
        .stream()
        .findFirst();
  }

  /**
   * Deletes event {@code id} if its stored type is still {@code type}.
   *
   * @return whether a row was deleted
   */
  public boolean delete(UUID id, String type) {
    return jdbc.update(DELETE, id, type) > 0;
  }

  /**
   * An event's application and stored type.
   *
   * @param appId application, or null for a server event
   * @param type stored {@code events.type}
   */
  public record StoredEvent(@Nullable Integer appId, String type) {}
}
