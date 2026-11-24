/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.storage.postgres.secrets;

import io.argos.server.secrets.EnvelopeCipher;
import io.argos.server.secrets.MasterKeyRing;
import io.argos.server.secrets.SecretField;
import io.argos.server.secrets.SecretUnavailableException;
import java.sql.Connection;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import javax.sql.DataSource;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Re-encrypts every stored secret under one key (16 §8.2 "교체", ADR-0010 C-226 (2); {@code
 * argos-admin secrets rekey}): for each {@link SecretField}, the rows whose envelope names another
 * key id are decrypted with whichever key of the ring has that id (the current key, the previous
 * key of the configuration, the key rotated away from) and encrypted again with the ring's current
 * key.
 *
 * <ul>
 *   <li>Batches of {@code batchSize} rows in key order, one transaction each; a row is written only
 *       if its value did not change since it was read (a server writing at the same time wins and
 *       its value is left for the next run), so the command can run next to a running server.
 *   <li>A row that cannot be decrypted (its key id is not loaded, wrong key or tampered value) is
 *       left as it is and counted as failed; the run goes on. Nothing secret is returned or logged:
 *       row ids (UUIDs, application ids) and key ids only.
 *   <li>Empty values (the destroyed configuration of a deleted channel) are not envelopes and are
 *       skipped.
 * </ul>
 */
public final class SecretsRekeyService {

  /** Default rows per batch. */
  public static final int DEFAULT_BATCH = 100;

  /** Largest batch. */
  public static final int MAX_BATCH = 10_000;

  /** Failed row ids kept per column for the report. */
  static final int MAX_FAILED_IDS = 20;

  /** Seconds a statement may take. */
  private static final int QUERY_TIMEOUT_SECONDS = 60;

  /** Progress of one batch (standard error of the command). */
  @FunctionalInterface
  public interface Progress {

    /** After each batch of {@code field}: totals so far. */
    void batch(SecretField field, long reencrypted, long failed);

    /** No progress output. */
    Progress NONE = (f, r, x) -> {};
  }

  /**
   * The outcome of one column.
   *
   * @param field the column
   * @param reencrypted rows written under the target key
   * @param changed rows that changed while they were re-encrypted (left for the next run)
   * @param failed rows that could not be decrypted
   * @param fromKids rows read per key id they named ({@code none} for a value that is not an
   *     envelope)
   * @param failedIds the ids of the first failed rows
   */
  public record FieldResult(
      SecretField field,
      long reencrypted,
      long changed,
      long failed,
      Map<String, Long> fromKids,
      List<String> failedIds) {

    /** Copies the collections. */
    public FieldResult {
      fromKids = Map.copyOf(fromKids);
      failedIds = List.copyOf(failedIds);
    }
  }

  /**
   * The outcome of a run.
   *
   * @param targetKid the key id every re-encrypted row names now
   * @param fields per column, in {@link SecretField} order
   */
  public record Result(String targetKid, List<FieldResult> fields) {

    /** Copies the list. */
    public Result {
      fields = List.copyOf(fields);
    }

    /** Rows re-encrypted in all columns. */
    public long reencrypted() {
      return fields.stream().mapToLong(FieldResult::reencrypted).sum();
    }

    /** Rows that could not be decrypted, in all columns. */
    public long failed() {
      return fields.stream().mapToLong(FieldResult::failed).sum();
    }

    /** Rows that changed during the run, in all columns. */
    public long changed() {
      return fields.stream().mapToLong(FieldResult::changed).sum();
    }
  }

  private final JdbcTemplate jdbc;
  private final TransactionTemplate transactions;
  private final EnvelopeCipher cipher;
  private final String targetKid;

  /**
   * The service over {@code dataSource} (the runtime account, which may update the secret columns)
   * with the keys of {@code ring}: its current key is the target.
   *
   * @throws IllegalArgumentException when the ring has no current key
   */
  public SecretsRekeyService(DataSource dataSource, MasterKeyRing ring) {
    Objects.requireNonNull(dataSource, "dataSource");
    this.targetKid =
        ring.current().orElseThrow(() -> new IllegalArgumentException("no target key")).kid();
    JdbcTemplate t = new JdbcTemplate(dataSource);
    t.setQueryTimeout(QUERY_TIMEOUT_SECONDS);
    this.jdbc = t;
    this.transactions = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
    this.cipher = new EnvelopeCipher(ring);
  }

  /** The service over one open connection, which the caller keeps and closes (tools and tests). */
  public static SecretsRekeyService over(Connection connection, MasterKeyRing ring) {
    return new SecretsRekeyService(new SingleConnectionDataSource(connection, true), ring);
  }

  /** The key id of the target key. */
  public String targetKid() {
    return targetKid;
  }

  /**
   * Re-encrypts every column.
   *
   * @param batchSize rows per batch, 1 to {@value #MAX_BATCH}
   */
  public Result rekey(int batchSize, Progress progress) {
    if (batchSize < 1 || batchSize > MAX_BATCH) {
      throw new IllegalArgumentException("batch size 1 to " + MAX_BATCH);
    }
    List<FieldResult> results = new ArrayList<>();
    for (SecretField field : SecretField.values()) {
      results.add(rekey(Column.of(field), batchSize, progress));
    }
    return new Result(targetKid, results);
  }

  /** Rows per key id of every column (for a dry look before and after a run; tests). */
  public Map<SecretField, Map<String, Long>> kidsInUse() {
    Map<SecretField, Map<String, Long>> out = new LinkedHashMap<>();
    for (SecretField field : SecretField.values()) {
      Column c = Column.of(field);
      Map<String, Long> kids = new LinkedHashMap<>();
      jdbc.query(
          "SELECT encode(substring("
              + c.value
              + " from 4 for 16), 'escape') AS kid, count(*)"
              + " FROM argos."
              + field.table()
              + " WHERE "
              + c.value
              + " IS NOT NULL AND"
              + " octet_length("
              + c.value
              + ") > 0 GROUP BY 1 ORDER BY 1",
          rs -> {
            String kid = SecretField.kidOf("v1:" + rs.getString(1) + ":x:x");
            kids.merge(kid == null ? "none" : kid, rs.getLong(2), Long::sum);
          });
      out.put(field, kids);
    }
    return out;
  }

  private FieldResult rekey(Column c, int batchSize, Progress progress) {
    long reencrypted = 0;
    long changed = 0;
    long failed = 0;
    Map<String, Long> fromKids = new LinkedHashMap<>();
    List<String> failedIds = new ArrayList<>();
    String after = "";
    while (true) {
      List<Row> rows =
          jdbc.query(
              c.select,
              (rs, n) -> new Row(rs.getString(1), rs.getBytes(2)),
              targetKid,
              after,
              batchSize);
      if (rows.isEmpty()) {
        break;
      }
      after = rows.get(rows.size() - 1).key();
      List<Update> updates = new ArrayList<>();
      for (Row row : rows) {
        String envelope = SecretField.envelope(row.value());
        String kid = SecretField.kidOf(envelope);
        fromKids.merge(kid == null ? "none" : kid, 1L, Long::sum);
        byte[] plain = null;
        try {
          plain = c.field.decrypt(cipher, envelope, row.key());
          String sealed = c.field.encrypt(cipher, plain, row.key());
          updates.add(new Update(row, SecretField.stored(sealed)));
        } catch (SecretUnavailableException e) {
          failed++;
          if (failedIds.size() < MAX_FAILED_IDS) {
            failedIds.add(row.key() + " (" + e.reason() + ")");
          }
        } finally {
          if (plain != null) {
            Arrays.fill(plain, (byte) 0);
          }
        }
      }
      if (!updates.isEmpty()) {
        int[] written =
            transactions.execute(
                status -> {
                  int[] counts = new int[updates.size()];
                  for (int i = 0; i < updates.size(); i++) {
                    Update u = updates.get(i);
                    counts[i] =
                        jdbc.update(c.update, c.bind(u.row().key(), u.value(), u.row().value()));
                  }
                  return counts;
                });
        for (int n : Objects.requireNonNull(written)) {
          if (n == 1) {
            reencrypted++;
          } else {
            changed++;
          }
        }
      }
      progress.batch(c.field, reencrypted, failed);
      if (rows.size() < batchSize) {
        break;
      }
    }
    return new FieldResult(c.field, reencrypted, changed, failed, fromKids, failedIds);
  }

  private static final class Row {
    private final String key;
    private final byte[] value;

    Row(String key, byte[] value) {
      this.key = key;
      this.value = value;
    }

    String key() {
      return key;
    }

    byte[] value() {
      return value;
    }
  }

  private static final class Update {
    private final Row row;
    private final byte[] value;

    Update(Row row, byte[] value) {
      this.row = row;
      this.value = value;
    }

    Row row() {
      return row;
    }

    byte[] value() {
      return value;
    }
  }

  /** The SQL of one column: the row key is the AAD row id. */
  private static final class Column {
    final SecretField field;
    final String value;
    final String select;
    final String update;

    private Column(SecretField field, String key, String value, String update) {
      this.field = field;
      this.value = value;
      // Rows that name another key id (or are no envelope), after the last key of the previous
      // batch, in key order.
      this.select =
          "SELECT "
              + key
              + " AS k, "
              + value
              + " FROM argos."
              + field.table()
              + " WHERE "
              + value
              + " IS NOT NULL AND octet_length("
              + value
              + ") > 0 AND encode(substring("
              + value
              + " from 4 for 16), 'escape') IS DISTINCT FROM ? AND "
              + key
              + " > ? ORDER BY "
              + key
              + " LIMIT ?";
      this.update = update;
    }

    static Column of(SecretField field) {
      return switch (field) {
        case CHANNEL_CONFIG ->
            new Column(
                field,
                "id::text",
                "config_enc",
                "UPDATE argos.notification_channels SET config_enc = ? WHERE id = ?::uuid AND"
                    + " config_enc = ?");
        case WEBHOOK_SIGNING_KEY ->
            new Column(
                field,
                "(channel_id::text || '/' || key_id)",
                "key_enc",
                "UPDATE argos.webhook_signing_keys SET key_enc = ? WHERE channel_id = ?::uuid AND"
                    + " key_id = ? AND key_enc = ?");
        case COMMAND_RESULT ->
            new Column(
                field,
                "command_id::text",
                "body_enc",
                "UPDATE argos.command_results SET body_enc = ? WHERE command_id = ?::uuid AND"
                    + " body_enc = ?");
        case APP_USER_ID_SALT ->
            new Column(
                field,
                "id::text",
                "user_id_salt_enc",
                "UPDATE argos.apps SET user_id_salt_enc = ? WHERE id = CAST(? AS INTEGER) AND"
                    + " user_id_salt_enc = ?");
      };
    }

    Object[] bind(String key, byte[] value, byte @Nullable [] old) {
      if (field == SecretField.WEBHOOK_SIGNING_KEY) {
        int slash = key.indexOf('/');
        return new Object[] {value, key.substring(0, slash), key.substring(slash + 1), old};
      }
      return new Object[] {value, key, old};
    }
  }
}
