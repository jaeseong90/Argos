/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.storage.postgres.alert.channel;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * {@code notification_deliveries} (V001, a plain table, C-111 (2); 12 §5.5, F-S27): one row per
 * alert event and channel, updated by every attempt of the delivery. {@code error} holds an error
 * code ({@code DeliveryException}), never a response body or URL. The daily cleanup deletes rows
 * older than the retention ({@link #deleteOlderThan}).
 */
@Repository
public class DeliveryRepository {

  /** Seconds a statement may take. */
  static final int QUERY_TIMEOUT_SECONDS = 10;

  /** Rows deleted per statement of the cleanup (short locks). */
  static final int DELETE_BATCH = 10_000;

  private final JdbcTemplate jdbc;

  public DeliveryRepository(DataSource dataSource) {
    JdbcTemplate t = new JdbcTemplate(dataSource);
    t.setQueryTimeout(QUERY_TIMEOUT_SECONDS);
    this.jdbc = t;
  }

  /**
   * Inserts the rows of one delivery: one per event ({@code pending}, attempt 0), or one without an
   * event when {@code eventIds} is empty.
   *
   * @return the row ids, in the order of {@code eventIds}
   */
  public List<UUID> insertPending(List<UUID> eventIds, UUID channelId, Instant now) {
    List<UUID> events = eventIds.isEmpty() ? java.util.Collections.singletonList(null) : eventIds;
    List<UUID> ids = new ArrayList<>(events.size());
    List<Object[]> args = new ArrayList<>(events.size());
    for (UUID event : events) {
      UUID id = UUID.randomUUID();
      ids.add(id);
      args.add(new Object[] {id, event, channelId, Timestamp.from(now)});
    }
    jdbc.batchUpdate(
        "INSERT INTO argos.notification_deliveries (id, alert_event_id, channel_id, attempt,"
            + " status, http_status, error, sent_at) VALUES (?, ?, ?, 0, 'pending', NULL, NULL, ?)",
        args);
    return ids;
  }

  /**
   * Records an attempt on the rows of one delivery.
   *
   * @param attempt attempts so far (1 for the first)
   * @param status {@code pending} (a retry follows), {@code sent}, {@code failed} or {@code
   *     dropped}
   * @param httpStatus the HTTP status or SMTP reply code of the attempt, or null
   * @param error the error code, or null
   */
  public void recordAttempt(
      List<UUID> rowIds, int attempt, String status, Integer httpStatus, String error, Instant at) {
    if (rowIds.isEmpty()) {
      return;
    }
    jdbc.update(
        "UPDATE argos.notification_deliveries SET attempt = ?, status = ?, http_status = ?,"
            + " error = ?, sent_at = ? WHERE id = ANY (?)",
        ps -> {
          ps.setInt(1, attempt);
          ps.setString(2, status);
          if (httpStatus == null) {
            ps.setNull(3, java.sql.Types.INTEGER);
          } else {
            ps.setInt(3, httpStatus);
          }
          ps.setString(4, error);
          ps.setTimestamp(5, Timestamp.from(at));
          ps.setArray(6, ps.getConnection().createArrayOf("uuid", rowIds.toArray()));
        });
  }

  /** Deletes rows sent before {@code cutoff}, in batches; the number deleted. */
  public long deleteOlderThan(Instant cutoff) {
    long total = 0;
    while (true) {
      int n =
          jdbc.update(
              "DELETE FROM argos.notification_deliveries WHERE id IN (SELECT id FROM"
                  + " argos.notification_deliveries WHERE sent_at < ? LIMIT ?)",
              Timestamp.from(cutoff),
              DELETE_BATCH);
      total += n;
      if (n < DELETE_BATCH) {
        return total;
      }
    }
  }
}
