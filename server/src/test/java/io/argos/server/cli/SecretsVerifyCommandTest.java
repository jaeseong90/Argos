/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.cli;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.argos.server.secrets.SecretField;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Arguments, usage and the statements of {@code argos-admin secrets verify} (WO-43); no database is
 * opened here ({@code SecretsVerifyIT} runs the command on PostgreSQL).
 */
@Tag("req:DSN-17-14")
@Tag("req:SEC-52")
class SecretsVerifyCommandTest {

  private final ByteArrayOutputStream outBytes = new ByteArrayOutputStream();
  private final ByteArrayOutputStream errBytes = new ByteArrayOutputStream();
  private final PrintStream out = new PrintStream(outBytes, true, StandardCharsets.UTF_8);
  private final PrintStream err = new PrintStream(errBytes, true, StandardCharsets.UTF_8);

  private int run(String... args) {
    return AdminCli.run(args, Map.of(), out, err);
  }

  @Test
  @DisplayName(
      "T-SEC-52e (unit) secrets verify options: --purpose backup|restore|manual (default manual),"
          + " --json; anything else exit 2 before any configuration is loaded")
  void options() throws Exception {
    assertThat(SecretsVerifyCommand.parse(List.of()))
        .isEqualTo(new SecretsVerifyCommand.Options(SecretsVerifyCommand.Purpose.MANUAL, false));
    assertThat(SecretsVerifyCommand.parse(List.of("--purpose", "restore", "--json")))
        .isEqualTo(new SecretsVerifyCommand.Options(SecretsVerifyCommand.Purpose.RESTORE, true));
    assertThat(SecretsVerifyCommand.parse(List.of("--purpose", "backup")).purpose().code())
        .isEqualTo("backup");
    assertThatThrownBy(() -> SecretsVerifyCommand.parse(List.of("--purpose", "nightly")))
        .isInstanceOf(CliUsageException.class);
    assertThatThrownBy(
            () -> SecretsVerifyCommand.parse(List.of("--purpose", "backup", "--purpose", "manual")))
        .hasMessage("--purpose given twice");
    assertThatThrownBy(() -> SecretsVerifyCommand.parse(List.of("--new-key", "x")))
        .isInstanceOf(CliUsageException.class);
    assertThat(run("secrets", "verify", "--purpose", "x")).isEqualTo(AdminCli.EXIT_USAGE);
    assertThat(errBytes.toString(StandardCharsets.UTF_8))
        .contains("Usage: argos-admin secrets verify");
  }

  @Test
  @DisplayName(
      "T-SEC-52e (unit) secrets verify has usage text, --help exit 0 and is listed in the overall"
          + " usage")
  void usage() {
    assertThat(run("secrets", "verify", "--help")).isZero();
    assertThat(outBytes.toString(StandardCharsets.UTF_8))
        .contains("Usage: argos-admin secrets verify")
        .contains("--argos.postgres.url");
    assertThat(AdminCli.USAGE).contains("  secrets verify [--purpose backup|restore|manual]");
  }

  @Test
  @DisplayName(
      "T-SEC-52e (unit) one read statement per secret column, keyed by the row id of its"
          + " associated data (the same as the writers and secrets rekey), keyset order in the C"
          + " collation, no write")
  void statements() {
    assertThat(SecretsVerifyCommand.select(SecretField.CHANNEL_CONFIG))
        .startsWith("SELECT k, v FROM (SELECT id::text AS k, config_enc AS v")
        .contains("FROM argos.notification_channels");
    assertThat(SecretsVerifyCommand.select(SecretField.WEBHOOK_SIGNING_KEY))
        .contains("(channel_id::text || '/' || key_id) AS k, key_enc AS v");
    assertThat(SecretsVerifyCommand.select(SecretField.COMMAND_RESULT))
        .contains("command_id::text AS k, body_enc AS v");
    assertThat(SecretsVerifyCommand.select(SecretField.APP_USER_ID_SALT))
        .contains("id::text AS k, user_id_salt_enc AS v FROM argos.apps");
    for (SecretField f : SecretField.values()) {
      String sql = SecretsVerifyCommand.select(f);
      assertThat(sql)
          .contains("octet_length(" + f.column() + ") > 0")
          .endsWith("WHERE k COLLATE \"C\" > ? ORDER BY k COLLATE \"C\" LIMIT ?")
          .doesNotContainIgnoringCase("update")
          .doesNotContainIgnoringCase("delete");
    }
  }
}
