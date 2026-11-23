/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.storage.postgres.alert.channel;

import java.nio.charset.StandardCharsets;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * {@code notification_channels} and {@code webhook_signing_keys} (V001, V008; 12 §5.5, 16 §8.3,
 * §8.4). Secrets arrive and leave as envelope strings ({@code EnvelopeCipher}); this class never
 * sees plaintext. The envelopes are stored as their UTF-8 bytes in the BYTEA columns (C-98: V001's
 * {@code config_enc BYTEA} is kept).
 *
 * <p>A deleted channel keeps its row for the delivery history: {@code deleted_at} is set, it is
 * disabled, its {@code config_enc} emptied and its signing keys deleted ({@link #softDelete}).
 */
@Repository
public class ChannelRepository {

  /** Seconds a statement may take. */
  static final int QUERY_TIMEOUT_SECONDS = 10;

  private static final String COLUMNS =
      "c.id, c.name, c.type, c.config_enc, c.config_masked::text AS config_masked, c.enabled,"
          + " c.status, c.consecutive_failures, c.last_success_at, c.last_failure_at, c.created_at,"
          + " c.updated_at, (SELECT count(*) FROM argos.webhook_signing_keys k WHERE k.channel_id ="
          + " c.id AND (k.retire_after IS NULL OR k.retire_after > ?)) AS signing_keys";

  private final JdbcTemplate jdbc;
  private final TransactionTemplate tx;

  public ChannelRepository(DataSource dataSource) {
    JdbcTemplate t = new JdbcTemplate(dataSource);
    t.setQueryTimeout(QUERY_TIMEOUT_SECONDS);
    this.jdbc = t;
    TransactionTemplate template =
        new TransactionTemplate(new DataSourceTransactionManager(dataSource));
    template.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    template.setTimeout(QUERY_TIMEOUT_SECONDS);
    this.tx = template;
  }

  /**
   * Inserts a channel and, for a webhook, its first signing key, in one transaction.
   *
   * @param key the first signing key, or null
   */
  public void insert(
      UUID id,
      String name,
      String type,
      String configEnc,
      String configMasked,
      Instant now,
      SigningKeyRow key) {
    tx.executeWithoutResult(
        status -> {
          jdbc.update(
              "INSERT INTO argos.notification_channels (id, name, type, config_enc, config_masked,"
                  + " enabled, created_at, updated_at) VALUES (?, ?, ?, ?, CAST(? AS jsonb), TRUE,"
                  + " ?, ?)",
              id,
              name,
              type,
              bytes(configEnc),
              configMasked,
              ts(now),
              ts(now));
          if (key != null) {
            insertKey(key);
          }
        });
  }

  /** The channels that are not deleted, by name. */
  public List<ChannelRow> list(Instant now, int limit) {
    return jdbc.query(
        "SELECT "
            + COLUMNS
            + " FROM argos.notification_channels c WHERE c.deleted_at IS NULL"
            + " ORDER BY lower(c.name), c.id LIMIT ?",
        ChannelRepository::map,
        ts(now),
        limit);
  }

  /** A channel that is not deleted. */
  public Optional<ChannelRow> find(UUID id, Instant now) {
    return jdbc
        .query(
            "SELECT "
                + COLUMNS
                + " FROM argos.notification_channels c WHERE c.id = ? AND c.deleted_at IS NULL",
            ChannelRepository::map,
            ts(now),
            id)
        .stream()
        .findFirst();
  }

  /**
   * Changes a channel; null arguments keep the stored value.
   *
   * @return whether the channel exists (not deleted)
   */
  public boolean update(
      UUID id, String name, String configEnc, String configMasked, Boolean enabled, Instant now) {
    return jdbc.update(
            "UPDATE argos.notification_channels SET name = COALESCE(?, name),"
                + " config_enc = COALESCE(?, config_enc),"
                + " config_masked = COALESCE(CAST(? AS jsonb), config_masked),"
                + " enabled = COALESCE(?, enabled), updated_at = ?"
                + " WHERE id = ? AND deleted_at IS NULL",
            name,
            configEnc == null ? null : bytes(configEnc),
            configMasked,
            enabled,
            ts(now),
            id)
        > 0;
  }

  /**
   * Deletes a channel: the row stays (delivery history) without secrets, disabled, with {@code
   * deleted_at}; its signing keys go.
   *
   * @return whether a channel was deleted
   */
  public boolean softDelete(UUID id, Instant now) {
    Boolean done =
        tx.execute(
            status -> {
              int n =
                  jdbc.update(
                      "UPDATE argos.notification_channels SET deleted_at = ?, enabled = FALSE,"
                          + " config_enc = '\\x'::bytea, config_masked = '{}'::jsonb,"
                          + " updated_at = ? WHERE id = ? AND deleted_at IS NULL",
                      ts(now),
                      ts(now),
                      id);
              jdbc.update("DELETE FROM argos.webhook_signing_keys WHERE channel_id = ?", id);
              return n > 0;
            });
    return Boolean.TRUE.equals(done);
  }

  /**
   * Whether a rule that is not deleted names the channel in {@code spec.channels.warn} or {@code
   * critical} (14 §4.13 rule schema).
   */
  public boolean referencedByRule(UUID id) {
    Boolean found =
        jdbc.queryForObject(
            "SELECT EXISTS (SELECT 1 FROM argos.alert_rules r WHERE r.deleted_at IS NULL AND"
                + " (COALESCE(r.spec->'channels'->'warn', '[]'::jsonb) @> to_jsonb(CAST(? AS"
                + " text)) OR COALESCE(r.spec->'channels'->'critical', '[]'::jsonb) @>"
                + " to_jsonb(CAST(? AS text))))",
            Boolean.class,
            id.toString(),
            id.toString());
    return Boolean.TRUE.equals(found);
  }

  /** The enabled channels that are not deleted, except {@code except} (null: none excepted). */
  public List<UUID> enabledIdsExcept(UUID except) {
    return jdbc.query(
        "SELECT id FROM argos.notification_channels WHERE enabled AND deleted_at IS NULL"
            + " AND (CAST(? AS uuid) IS NULL OR id <> CAST(? AS uuid)) ORDER BY created_at, id",
        (rs, row) -> rs.getObject(1, UUID.class),
        except,
        except);
  }

  /** The outcome of a health update: the status before and after. */
  public record HealthChange(String before, String after, int consecutiveFailures) {}

  /** A successful delivery: failures reset, status {@code ok}. Empty when the channel is gone. */
  public Optional<HealthChange> recordSuccess(UUID id, Instant at) {
    return jdbc
        .query(
            "UPDATE argos.notification_channels c SET consecutive_failures = 0,"
                + " last_success_at = ?, status = 'ok'"
                + " FROM (SELECT id, status AS old_status FROM argos.notification_channels"
                + " WHERE id = ? FOR UPDATE) o WHERE c.id = o.id"
                + " RETURNING o.old_status, c.status, c.consecutive_failures",
            (rs, row) -> new HealthChange(rs.getString(1), rs.getString(2), rs.getInt(3)),
            ts(at),
            id)
        .stream()
        .findFirst();
  }

  /**
   * A failed delivery: one more consecutive failure; {@code failing} from {@code threshold} on.
   * Empty when the channel is gone.
   */
  public Optional<HealthChange> recordFailure(UUID id, Instant at, int threshold) {
    return jdbc
        .query(
            "UPDATE argos.notification_channels c SET consecutive_failures ="
                + " c.consecutive_failures + 1, last_failure_at = ?,"
                + " status = CASE WHEN c.consecutive_failures + 1 >= ? THEN 'failing'"
                + " ELSE c.status END"
                + " FROM (SELECT id, status AS old_status FROM argos.notification_channels"
                + " WHERE id = ? FOR UPDATE) o WHERE c.id = o.id"
                + " RETURNING o.old_status, c.status, c.consecutive_failures",
            (rs, row) -> new HealthChange(rs.getString(1), rs.getString(2), rs.getInt(3)),
            ts(at),
            threshold,
            id)
        .stream()
        .findFirst();
  }

  /** The valid signing keys of a channel, newest first. */
  public List<SigningKeyRow> validKeys(UUID channelId, Instant now) {
    return jdbc.query(
        "SELECT channel_id, key_id, key_enc, created_at, retire_after"
            + " FROM argos.webhook_signing_keys WHERE channel_id = ?"
            + " AND (retire_after IS NULL OR retire_after > ?) ORDER BY created_at DESC, key_id",
        (rs, row) ->
            new SigningKeyRow(
                rs.getObject(1, UUID.class),
                rs.getString(2),
                string(rs.getBytes(3)),
                instant(rs.getTimestamp(4)),
                instant(rs.getTimestamp(5))),
        channelId,
        ts(now));
  }

  /**
   * A rotation (16 §8.4): keys still in an earlier grace retire now, the current key gets {@code
   * graceEnd}, and {@code next} becomes the current key; one transaction.
   *
   * @return whether the channel exists
   */
  public boolean rotateKey(UUID channelId, SigningKeyRow next, Instant now, Instant graceEnd) {
    Boolean done =
        tx.execute(
            status -> {
              List<UUID> locked =
                  jdbc.query(
                      "SELECT id FROM argos.notification_channels WHERE id = ? AND deleted_at IS"
                          + " NULL FOR UPDATE",
                      (rs, row) -> rs.getObject(1, UUID.class),
                      channelId);
              if (locked.isEmpty()) {
                return false;
              }
              jdbc.update(
                  "UPDATE argos.webhook_signing_keys SET retire_after = ? WHERE channel_id = ?"
                      + " AND retire_after IS NOT NULL AND retire_after > ?",
                  ts(now),
                  channelId,
                  ts(now));
              jdbc.update(
                  "UPDATE argos.webhook_signing_keys SET retire_after = ? WHERE channel_id = ?"
                      + " AND retire_after IS NULL",
                  ts(graceEnd),
                  channelId);
              insertKey(next);
              jdbc.update(
                  "UPDATE argos.notification_channels SET updated_at = ? WHERE id = ?",
                  ts(now),
                  channelId);
              return true;
            });
    return Boolean.TRUE.equals(done);
  }

  /** Deletes signing keys whose grace ended before {@code now}; the number deleted. */
  public int deleteRetiredKeys(Instant now) {
    return jdbc.update(
        "DELETE FROM argos.webhook_signing_keys WHERE retire_after IS NOT NULL AND retire_after <= ?",
        ts(now));
  }

  private void insertKey(SigningKeyRow key) {
    jdbc.update(
        "INSERT INTO argos.webhook_signing_keys (channel_id, key_id, key_enc, created_at,"
            + " retire_after) VALUES (?, ?, ?, ?, ?)",
        key.channelId(),
        key.keyId(),
        bytes(key.keyEnc()),
        ts(key.createdAt()),
        key.retireAfter() == null ? null : ts(key.retireAfter()));
  }

  private static ChannelRow map(ResultSet rs, int row) throws SQLException {
    return new ChannelRow(
        rs.getObject("id", UUID.class),
        rs.getString("name"),
        rs.getString("type"),
        string(rs.getBytes("config_enc")),
        rs.getString("config_masked"),
        rs.getBoolean("enabled"),
        rs.getString("status"),
        rs.getInt("consecutive_failures"),
        instant(rs.getTimestamp("last_success_at")),
        instant(rs.getTimestamp("last_failure_at")),
        instant(rs.getTimestamp("created_at")),
        instant(rs.getTimestamp("updated_at")),
        rs.getInt("signing_keys"));
  }

  private static byte[] bytes(String envelope) {
    return envelope.getBytes(StandardCharsets.UTF_8);
  }

  private static String string(byte[] bytes) {
    return bytes == null ? null : new String(bytes, StandardCharsets.UTF_8);
  }

  private static Timestamp ts(Instant instant) {
    return Timestamp.from(instant);
  }

  private static Instant instant(Timestamp ts) {
    return ts == null ? null : ts.toInstant();
  }
}
