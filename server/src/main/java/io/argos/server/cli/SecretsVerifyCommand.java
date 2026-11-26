/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.cli;

import io.argos.server.audit.AuditResult;
import io.argos.server.config.ArgosProperties;
import io.argos.server.secrets.EnvelopeCipher;
import io.argos.server.secrets.MasterKey;
import io.argos.server.secrets.MasterKeyLoader;
import io.argos.server.secrets.MasterKeyRing;
import io.argos.server.secrets.SecretField;
import io.argos.server.secrets.SecretUnavailableException;
import io.argos.server.storage.postgres.ops.AdminCommandDatabase;
import io.argos.server.storage.postgres.secrets.StoredSecretScan;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * {@code argos-admin secrets verify [--purpose backup|restore|manual] [--json]} (WO-43; 16 §8.2, 17
 * §4.4): tries to decrypt every stored secret ({@link SecretField}: alert channel settings, webhook
 * signing keys, command results, application user id salts) with the configured master key and its
 * previous key, and reports per column the rows, how many decrypt, how many do not and the key ids
 * they name. Read only: nothing is written but the audit row, and neither a plaintext, a key nor an
 * envelope is printed (row ids and key ids only).
 *
 * <p>The backup and restore scripts of the Compose installation use it: a backup records the key id
 * ({@code kid}, {@code SHA-256(key)[0:8]} as 16 hex characters, 16 §8.2) in its manifest, and a
 * restore runs it against the staged database with the candidate key ({@code
 * --argos.postgres.url=jdbc:postgresql://.../argos_restore --argos.master-key-file=<key>}) before
 * it replaces the live database, so a wrong key refuses the restore without a partial result.
 *
 * <p>A column missing from the database (a backup of an older schema version) is reported with
 * {@code present: false} and counts as empty. Output: a table, or one JSON object {@code {command,
 * kid, previous_kid, verifiable, rows, ok, failed, columns: [{table, column, present, rows, ok,
 * failed, kids, failed_rows}]}}; {@code verifiable} is false when no secret is stored (nothing
 * proves the key then). Audit: {@code ADMIN_COMMAND_EXECUTED} (target {@code secrets verify}) with
 * the purpose, the key ids and the counts (no new event, the catalog is closed, ADR-0010 C-238
 * (5)). Exit codes: 0 every stored secret decrypts or none is stored, 1 a secret does not decrypt,
 * the key is missing, malformed or too open, or the database cannot be read, 2 invalid arguments.
 */
final class SecretsVerifyCommand {

  static final String COMMAND = "secrets verify";

  static final String USAGE =
      """
      Usage: argos-admin secrets verify [--purpose backup|restore|manual] [--json]
        Decrypts every stored secret (alert channel settings, webhook signing keys, command
        results, application user id salts) with the configured master key
        (argos.master-key-file) and its previous key (argos.security.master-key.previous-file)
        and reports per column the rows, how many decrypt and the key ids they name. Writes
        nothing but the audit row and prints no secret. To check a restored database and a key
        file before using them, add --argos.postgres.url=<jdbc url> --argos.master-key-file=<path>.
        Exit codes: 0 every stored secret decrypts (or none is stored: verifiable false),
        1 a secret does not decrypt, no usable key, or a database failure, 2 invalid arguments.
      """;

  /** Rows read per statement. */
  static final int BATCH = 100;

  /** Failed row ids kept per column for the report. */
  static final int MAX_FAILED_IDS = 20;

  /** Why the command runs (the audit row's {@code purpose}). */
  enum Purpose {
    BACKUP,
    RESTORE,
    MANUAL;

    String code() {
      return name().toLowerCase(Locale.ROOT);
    }
  }

  /**
   * The parsed options.
   *
   * @param purpose the audit row's purpose
   * @param json JSON output
   */
  record Options(Purpose purpose, boolean json) {}

  /**
   * The outcome of one column.
   *
   * @param field the column
   * @param present whether the database has the column
   * @param rows stored values (not null, not empty)
   * @param ok values that decrypt
   * @param failed values that do not
   * @param kids rows per key id the values name ({@code none} for a value that is not an envelope)
   * @param failedIds the first failed row ids, each with the reason code
   */
  record ColumnResult(
      SecretField field,
      boolean present,
      long rows,
      long ok,
      long failed,
      Map<String, Long> kids,
      List<String> failedIds) {

    /** Copies the collections. */
    ColumnResult {
      kids = Map.copyOf(kids);
      failedIds = List.copyOf(failedIds);
    }
  }

  /**
   * The outcome of a run.
   *
   * @param kid the current key's id
   * @param previousKid the previous key's id, or null
   * @param columns per column, in {@link SecretField} order
   */
  record Result(String kid, @Nullable String previousKid, List<ColumnResult> columns) {

    /** Copies the list. */
    Result {
      columns = List.copyOf(columns);
    }

    long rows() {
      return columns.stream().mapToLong(ColumnResult::rows).sum();
    }

    long ok() {
      return columns.stream().mapToLong(ColumnResult::ok).sum();
    }

    long failed() {
      return columns.stream().mapToLong(ColumnResult::failed).sum();
    }

    /** Whether at least one stored secret was there to prove the key. */
    boolean verifiable() {
      return rows() > 0;
    }
  }

  private SecretsVerifyCommand() {}

  /**
   * Parses the arguments after {@code secrets verify}.
   *
   * @throws CliUsageException for an unknown, repeated or invalid option
   */
  static Options parse(List<String> args) throws CliUsageException {
    Purpose purpose = null;
    boolean json = false;
    CliArgs a = new CliArgs(args);
    while (a.next()) {
      switch (a.name()) {
        case "--purpose" -> {
          if (purpose != null) {
            throw new CliUsageException("--purpose given twice");
          }
          String v = a.value();
          purpose =
              switch (v) {
                case "backup" -> Purpose.BACKUP;
                case "restore" -> Purpose.RESTORE;
                case "manual" -> Purpose.MANUAL;
                default ->
                    throw new CliUsageException(
                        "--purpose must be backup, restore or manual, got: " + CliArgs.shorten(v));
              };
        }
        case "--json" -> {
          a.noValue();
          json = true;
        }
        default -> throw new CliUsageException("Unknown option: " + CliArgs.shorten(a.name()));
      }
    }
    return new Options(purpose == null ? Purpose.MANUAL : purpose, json);
  }

  /**
   * Verifies and prints the result.
   *
   * @return the exit code
   */
  static int execute(
      Options options, ArgosProperties properties, PrintStream out, PrintStream err) {
    MasterKeyLoader.Loaded keys;
    try {
      keys = MasterKeyLoader.load(properties, MasterKeyLoader.FILE_SYSTEM);
    } catch (MasterKeyLoader.MasterKeyException e) {
      audit(properties, options, null, AuditResult.failure("master_key"));
      return CliOutput.fail(
          options.json(), COMMAND, e.getMessage(), "master_key", out, err, AdminCli.EXIT_FAILURE);
    }
    for (String note : keys.notes()) {
      err.println("argos-admin " + COMMAND + ": " + note);
    }
    for (String warning : keys.warnings()) {
      err.println("argos-admin " + COMMAND + ": WARNING: " + warning);
    }
    MasterKey previous = keys.previous();
    MasterKeyRing ring =
        MasterKeyRing.of(keys.current(), previous == null ? List.of() : List.of(previous));
    String kid = keys.current().kid();
    String previousKid = previous == null ? null : previous.kid();
    Result result;
    try (AdminCommandDatabase db = AdminCommandDatabase.openReadOnly(properties.postgres())) {
      result = verify(db.storedSecrets(), ring, kid, previousKid);
    } catch (AdminCommandDatabase.AdminCommandDatabaseException
        | StoredSecretScan.ScanException e) {
      audit(properties, options, null, AuditResult.failure("database"));
      return CliOutput.fail(
          options.json(), COMMAND, e.getMessage(), "database", out, err, AdminCli.EXIT_FAILURE);
    } finally {
      ring.destroy();
    }
    audit(
        properties,
        options,
        result,
        result.failed() == 0 ? AuditResult.success() : AuditResult.failure("undecryptable_rows"));
    print(options, result, out);
    if (result.failed() > 0) {
      err.println(
          "argos-admin "
              + COMMAND
              + ": "
              + result.failed()
              + " stored secret(s) do not decrypt with key id "
              + kid
              + (previousKid == null ? "" : " or " + previousKid)
              + "; this key does not belong to this database");
      err.flush();
      return AdminCli.EXIT_FAILURE;
    }
    if (!result.verifiable()) {
      err.println(
          "argos-admin "
              + COMMAND
              + ": no stored secret to check; the key cannot be confirmed against this database");
      err.flush();
    }
    return AdminCli.EXIT_OK;
  }

  /**
   * Reads and decrypts every stored value over {@code scan} (the runtime account); nothing is
   * written.
   *
   * @throws StoredSecretScan.ScanException when a statement fails
   */
  static Result verify(
      StoredSecretScan scan, MasterKeyRing ring, String kid, @Nullable String previousKid)
      throws StoredSecretScan.ScanException {
    EnvelopeCipher cipher = new EnvelopeCipher(ring);
    List<ColumnResult> columns = new ArrayList<>();
    for (SecretField field : SecretField.values()) {
      columns.add(verify(scan, cipher, field));
    }
    return new Result(kid, previousKid, columns);
  }

  private static ColumnResult verify(
      StoredSecretScan scan, EnvelopeCipher cipher, SecretField field)
      throws StoredSecretScan.ScanException {
    if (!scan.present(field)) {
      return new ColumnResult(field, false, 0, 0, 0, Map.of(), List.of());
    }
    long[] counts = new long[3]; // rows, ok, failed
    Map<String, Long> kids = new TreeMap<>();
    List<String> failedIds = new ArrayList<>();
    scan.forEach(
        field,
        BATCH,
        (rowId, stored) -> {
          String envelope = SecretField.envelope(stored);
          String envelopeKid = SecretField.kidOf(envelope);
          kids.merge(envelopeKid == null ? "none" : envelopeKid, 1L, Long::sum);
          counts[0]++;
          byte[] plain = null;
          try {
            plain = field.decrypt(cipher, envelope, rowId);
            counts[1]++;
          } catch (SecretUnavailableException e) {
            counts[2]++;
            if (failedIds.size() < MAX_FAILED_IDS) {
              failedIds.add(rowId + " (" + e.reason() + ")");
            }
          } finally {
            if (plain != null) {
              Arrays.fill(plain, (byte) 0);
            }
          }
        });
    return new ColumnResult(field, true, counts[0], counts[1], counts[2], kids, failedIds);
  }

  /**
   * The stored values of {@code field} after a row id, in row id order ({@link
   * StoredSecretScan#select}).
   */
  static String select(SecretField field) {
    return StoredSecretScan.select(field);
  }

  private static void audit(
      ArgosProperties properties, Options options, @Nullable Result result, AuditResult outcome) {
    Map<String, Object> after = new LinkedHashMap<>();
    after.put("purpose", options.purpose().code());
    if (result != null) {
      after.put("kid", result.kid());
      after.put("previous_kid", result.previousKid() == null ? "none" : result.previousKid());
      after.put("verifiable", result.verifiable());
      after.put("rows", result.rows());
      after.put("ok", result.ok());
      after.put("failed", result.failed());
      Map<String, Object> failedByColumn = new LinkedHashMap<>();
      for (ColumnResult c : result.columns()) {
        failedByColumn.put(c.field().qualifiedName(), c.failed());
      }
      after.put("failed_by_column", failedByColumn);
    }
    CliAudit.executed(properties, COMMAND, outcome, after);
  }

  private static void print(Options options, Result result, PrintStream out) {
    if (options.json()) {
      ObjectNode node = CliOutput.result(COMMAND);
      node.put("kid", result.kid());
      if (result.previousKid() == null) {
        node.putNull("previous_kid");
      } else {
        node.put("previous_kid", result.previousKid());
      }
      node.put("verifiable", result.verifiable());
      node.put("rows", result.rows());
      node.put("ok", result.ok());
      node.put("failed", result.failed());
      ArrayNode columns = node.putArray("columns");
      for (ColumnResult c : result.columns()) {
        ObjectNode col = columns.addObject();
        col.put("table", c.field().table());
        col.put("column", c.field().column());
        col.put("present", c.present());
        col.put("rows", c.rows());
        col.put("ok", c.ok());
        col.put("failed", c.failed());
        ArrayNode kids = col.putArray("kids");
        c.kids().keySet().stream().sorted().forEach(kids::add);
        ArrayNode failedRows = col.putArray("failed_rows");
        c.failedIds().forEach(failedRows::add);
      }
      CliOutput.print(node, out);
      return;
    }
    out.printf(
        Locale.ROOT,
        "Master key id %s (previous key id %s)%n",
        result.kid(),
        result.previousKid() == null ? "none" : result.previousKid());
    out.printf(Locale.ROOT, "%-36s %8s %8s %7s  %s%n", "column", "rows", "ok", "failed", "key ids");
    for (ColumnResult c : result.columns()) {
      out.printf(
          Locale.ROOT,
          "%-36s %8s %8d %7d  %s%n",
          c.field().qualifiedName(),
          c.present() ? Long.toString(c.rows()) : "absent",
          c.ok(),
          c.failed(),
          c.kids().isEmpty() ? "-" : String.join(", ", new TreeMap<>(c.kids()).keySet()));
      for (String id : c.failedIds()) {
        out.printf(Locale.ROOT, "  does not decrypt: %s%n", id);
      }
    }
    out.printf(
        Locale.ROOT,
        "Total: %d stored, %d decrypt, %d do not%s%n",
        result.rows(),
        result.ok(),
        result.failed(),
        result.verifiable() ? "" : " (nothing to verify)");
    out.flush();
  }
}
