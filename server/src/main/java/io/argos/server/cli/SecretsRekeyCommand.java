/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.cli;

import io.argos.server.audit.Actor;
import io.argos.server.audit.AuditEvent;
import io.argos.server.audit.AuditResult;
import io.argos.server.audit.AuditSinks;
import io.argos.server.audit.DefaultAuditService;
import io.argos.server.audit.Target;
import io.argos.server.config.ArgosProperties;
import io.argos.server.secrets.MasterKey;
import io.argos.server.secrets.MasterKeyLoader;
import io.argos.server.secrets.MasterKeyRing;
import io.argos.server.storage.postgres.ops.AdminCommandDatabase;
import io.argos.server.storage.postgres.secrets.SecretsRekeyService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.PrintStream;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.springframework.dao.DataAccessException;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * {@code argos-admin secrets rekey [--new-key <path>] [--batch <n>] [--json]} and its alias {@code
 * argos-admin master-key rotate [--new-key-file <path>] [--batch <n>] [--json]} (16 §8.2, 12 §8.2,
 * 17 §4.4; ADR-0010 C-226 (2); the two names of the design are one command, WO-28 PM question 2):
 * re-encrypts every stored secret ({@link io.argos.server.secrets.SecretField}) under one key with
 * {@link SecretsRekeyService}.
 *
 * <ul>
 *   <li>Without {@code --new-key}: the target is the configured current key ({@code
 *       argos.master-key} or {@code argos.master-key-file}); rows of the previous key ({@code
 *       argos.security.master-key.previous[-file]}) are re-encrypted. This is the documented
 *       rotation: new key as {@code master-key-file}, old key as {@code previous-file}, restart,
 *       rekey, remove the previous key setting, restart.
 *   <li>With {@code --new-key <path>}: the target is the key in that file (the same permission rule
 *       as the key file); the configured keys decrypt. The server must then be restarted with the
 *       new key as {@code master-key-file} before it can read the re-encrypted secrets.
 * </ul>
 *
 * Progress goes to standard error, the result (a table, or one JSON object with {@code --json}) to
 * standard output; a {@code MASTER_KEY_ROTATED} audit row names the key ids and row counts only.
 * Exit codes: 0 every row names the target key, 1 failure (configuration, keys, database) or rows
 * that could not be decrypted (left unchanged, listed), 2 invalid arguments.
 */
final class SecretsRekeyCommand {

  static final String USAGE =
      """
      Usage: argos-admin secrets rekey [--new-key <path>] [--batch <n>] [--json]
             argos-admin master-key rotate [--new-key-file <path>] [--batch <n>] [--json]
        Re-encrypts every stored secret (alert channel settings, webhook signing keys, command
        results, application user id salts) under one master key. Without --new-key the target
        is the configured key (argos.master-key-file) and the previous key
        (argos.security.master-key.previous-file) decrypts; with --new-key the target is the key
        in that file (restart the server with it as argos.master-key-file afterwards).
        --batch rows per transaction, 1 to 10000 (default 100).
        Exit codes: 0 done, 1 failure or rows that could not be decrypted, 2 invalid arguments.
      """;

  /** Audit actor. */
  static final String ACTOR = "argos-admin";

  private static final JsonMapper JSON = JsonMapper.builder().build();

  /**
   * The parsed options.
   *
   * @param command {@code secrets rekey} or {@code master-key rotate}
   * @param newKey the target key file, or null for the configured key
   * @param batch rows per batch
   * @param json JSON output
   */
  record Options(String command, @Nullable Path newKey, int batch, boolean json) {}

  private SecretsRekeyCommand() {}

  /**
   * Parses the arguments after {@code secrets rekey} ({@code alias} false) or {@code master-key
   * rotate} ({@code alias} true).
   *
   * @throws CliUsageException for a missing, repeated, unknown or invalid option
   */
  static Options parse(boolean alias, List<String> args) throws CliUsageException {
    String keyOption = alias ? "--new-key-file" : "--new-key";
    Path newKey = null;
    Integer batch = null;
    boolean json = false;
    CliArgs a = new CliArgs(args);
    while (a.next()) {
      String name = a.name();
      if (name.equals(keyOption)) {
        if (newKey != null) {
          throw new CliUsageException(keyOption + " given twice");
        }
        String value = a.value();
        try {
          newKey = Path.of(value);
        } catch (InvalidPathException e) {
          throw new CliUsageException(keyOption + " is not a valid file name");
        }
      } else if (name.equals("--batch")) {
        if (batch != null) {
          throw new CliUsageException("--batch given twice");
        }
        batch = batch(a.value());
      } else if (name.equals("--json")) {
        a.noValue();
        json = true;
      } else {
        throw new CliUsageException("Unknown option: " + CliArgs.shorten(name));
      }
    }
    return new Options(
        alias ? "master-key rotate" : "secrets rekey",
        newKey,
        batch == null ? SecretsRekeyService.DEFAULT_BATCH : batch,
        json);
  }

  private static int batch(String value) throws CliUsageException {
    if (!value.isEmpty()
        && value.length() <= 5
        && value.charAt(0) != '0'
        && value.chars().allMatch(c -> c >= '0' && c <= '9')) {
      int n = Integer.parseInt(value);
      if (n <= SecretsRekeyService.MAX_BATCH) {
        return n;
      }
    }
    throw new CliUsageException(
        "--batch must be a whole number from 1 to "
            + SecretsRekeyService.MAX_BATCH
            + ", got: "
            + CliArgs.shorten(value));
  }

  /**
   * Re-encrypts and prints the result.
   *
   * @return the exit code
   */
  static int execute(
      Options options, ArgosProperties properties, PrintStream out, PrintStream err) {
    MasterKeyLoader.Loaded keys;
    try {
      keys = MasterKeyLoader.load(properties, MasterKeyLoader.FILE_SYSTEM);
    } catch (MasterKeyLoader.MasterKeyException e) {
      return fail(options, e.getMessage(), "master_key", out, err);
    }
    List<MasterKey> decryptOnly = new ArrayList<>();
    MasterKey target = keys.current();
    List<String> warnings = new ArrayList<>(keys.warnings());
    List<String> notes = new ArrayList<>(keys.notes());
    if (keys.previous() != null) {
      decryptOnly.add(keys.previous());
    }
    if (options.newKey() != null) {
      String option =
          options.command().equals("master-key rotate") ? "--new-key-file" : "--new-key";
      try {
        target =
            MasterKeyLoader.loadFile(
                option,
                options.newKey(),
                properties.security().masterKey().permissive(),
                MasterKeyLoader.FILE_SYSTEM,
                warnings,
                notes);
      } catch (MasterKeyLoader.MasterKeyException e) {
        close(keys.current(), decryptOnly);
        return fail(options, e.getMessage(), "master_key", out, err);
      }
      decryptOnly.add(0, keys.current());
    }
    for (String note : notes) {
      err.println("argos-admin " + options.command() + ": " + note);
    }
    for (String warning : warnings) {
      err.println("argos-admin " + options.command() + ": WARNING: " + warning);
    }
    MasterKeyRing ring = MasterKeyRing.of(target, decryptOnly);
    AdminCommandDatabase db = null;
    try {
      db = AdminCommandDatabase.open(properties.postgres());
      SecretsRekeyService service = db.secretsRekey(ring);
      List<String> fromKids = ring.decryptOnlyKids();
      err.printf(
          Locale.ROOT,
          "argos-admin %s: re-encrypting under key id %s (decrypting also with %s)%n",
          options.command(),
          service.targetKid(),
          fromKids.isEmpty() ? "no other key" : String.join(", ", fromKids));
      err.flush();
      SecretsRekeyService.Result result =
          service.rekey(
              options.batch(),
              (field, done, failed) -> {
                err.printf(
                    Locale.ROOT,
                    "argos-admin %s: %s: %d re-encrypted, %d failed%n",
                    options.command(),
                    field.qualifiedName(),
                    done,
                    failed);
                err.flush();
              });
      audit(properties, db, result, fromKids);
      print(options, result, fromKids, out);
      if (result.failed() > 0) {
        err.println(
            "argos-admin "
                + options.command()
                + ": "
                + result.failed()
                + " row(s) could not be decrypted with the loaded keys and were left unchanged;"
                + " load their key as argos.security.master-key.previous-file and run again");
        return AdminCli.EXIT_FAILURE;
      }
      return AdminCli.EXIT_OK;
    } catch (AdminCommandDatabase.AdminCommandDatabaseException e) {
      return fail(options, e.getMessage(), "database", out, err);
    } catch (DataAccessException e) {
      return fail(options, "PostgreSQL: " + e.getClass().getSimpleName(), "database", out, err);
    } finally {
      if (db != null) {
        db.close();
      }
      ring.destroy();
    }
  }

  private static void audit(
      ArgosProperties properties,
      AdminCommandDatabase db,
      SecretsRekeyService.Result result,
      List<String> fromKids) {
    SimpleMeterRegistry registry = new SimpleMeterRegistry();
    DefaultAuditService audit;
    try {
      audit =
          new DefaultAuditService(
              AuditSinks.configure(properties.audit(), List.of(db.auditSink()), registry),
              registry);
    } catch (IllegalArgumentException e) {
      audit = new DefaultAuditService(List.of(db.auditSink()), registry);
    }
    try {
      Map<String, Object> rows = new LinkedHashMap<>();
      Map<String, Object> failedRows = new LinkedHashMap<>();
      for (SecretsRekeyService.FieldResult f : result.fields()) {
        rows.put(f.field().qualifiedName(), f.reencrypted());
        failedRows.put(f.field().qualifiedName(), f.failed());
      }
      Map<String, Object> before = new LinkedHashMap<>();
      before.put("decrypt_kids", fromKids);
      Map<String, Object> after = new LinkedHashMap<>();
      after.put("target_kid", result.targetKid());
      after.put("reencrypted", rows);
      after.put("failed", failedRows);
      after.put("changed_during_run", result.changed());
      audit.record(
          AuditEvent.MASTER_KEY_ROTATED,
          new Actor(Actor.Type.SYSTEM, ACTOR, ACTOR, null, null),
          new Target("master_key", result.targetKid(), null),
          result.failed() == 0 ? AuditResult.success() : AuditResult.failure("undecryptable_rows"),
          before,
          after);
    } finally {
      audit.destroy();
    }
  }

  private static void print(
      Options options, SecretsRekeyService.Result result, List<String> fromKids, PrintStream out) {
    if (options.json()) {
      ObjectNode node = JSON.createObjectNode();
      node.put("command", options.command());
      node.put("target_kid", result.targetKid());
      ArrayNode decrypt = node.putArray("decrypt_kids");
      fromKids.forEach(decrypt::add);
      ArrayNode columns = node.putArray("columns");
      for (SecretsRekeyService.FieldResult f : result.fields()) {
        ObjectNode c = columns.addObject();
        c.put("column", f.field().qualifiedName());
        c.put("reencrypted", f.reencrypted());
        c.put("changed", f.changed());
        c.put("failed", f.failed());
        ObjectNode kids = c.putObject("from_kids");
        f.fromKids().entrySet().stream()
            .sorted(Map.Entry.comparingByKey())
            .forEach(e -> kids.put(e.getKey(), e.getValue()));
        ArrayNode ids = c.putArray("failed_rows");
        f.failedIds().forEach(ids::add);
      }
      node.put("reencrypted", result.reencrypted());
      node.put("changed", result.changed());
      node.put("failed", result.failed());
      out.println(JSON.writeValueAsString(node));
    } else {
      out.printf(Locale.ROOT, "Master key rotation: target key id %s%n", result.targetKid());
      out.printf(Locale.ROOT, "%-36s %12s %8s %7s%n", "column", "reencrypted", "changed", "failed");
      for (SecretsRekeyService.FieldResult f : result.fields()) {
        out.printf(
            Locale.ROOT,
            "%-36s %12d %8d %7d%n",
            f.field().qualifiedName(),
            f.reencrypted(),
            f.changed(),
            f.failed());
        for (String id : f.failedIds()) {
          out.printf(Locale.ROOT, "  not decrypted: %s%n", id);
        }
      }
      out.printf(
          Locale.ROOT,
          "Total: %d re-encrypted, %d changed during the run (run again), %d failed%n",
          result.reencrypted(),
          result.changed(),
          result.failed());
    }
    out.flush();
  }

  private static int fail(
      Options options, String message, String code, PrintStream out, PrintStream err) {
    if (options.json()) {
      ObjectNode node = JSON.createObjectNode();
      node.put("command", options.command());
      node.put("error", message);
      node.put("code", code);
      out.println(JSON.writeValueAsString(node));
      out.flush();
    }
    err.println("argos-admin " + options.command() + ": " + message);
    err.flush();
    return AdminCli.EXIT_FAILURE;
  }

  private static void close(MasterKey current, List<MasterKey> others) {
    current.close();
    others.forEach(MasterKey::close);
  }
}
