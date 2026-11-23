/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.cli;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Argument parsing of {@code argos-admin token create} (ADR-0010 C-173 (4)): the input limits of
 * the Compose command it replaces (app 1 to 64 and env 1 to 32 characters of {@code A-Z a-z 0-9 . _
 * -}, expiry 1 to 3650 days without a leading zero), the audit actor name, and exit code 2 for
 * every refusal before any database access.
 */
@Tag("req:C-173")
class TokenCreateCommandTest {

  @Test
  @DisplayName(
      "T-SV-040j token create options: both forms, defaults (no expiry option, actor"
          + " argos-admin), --json")
  void parse() throws Exception {
    TokenCreateCommand.Options o =
        TokenCreateCommand.parse(List.of("--app", "order-api", "--env=prod", "--json"));
    assertThat(o)
        .isEqualTo(new TokenCreateCommand.Options("order-api", "prod", null, "argos-admin", true));
    assertThat(
            TokenCreateCommand.parse(
                List.of(
                    "--app=" + "a".repeat(64),
                    "--env",
                    "e".repeat(32),
                    "--expires-days",
                    "3650",
                    "--actor",
                    "argos-compose")))
        .isEqualTo(
            new TokenCreateCommand.Options(
                "a".repeat(64), "e".repeat(32), 3650, "argos-compose", false));
  }

  @Test
  @DisplayName(
      "T-SV-040j token create refuses out-of-range input: names, environments, expiry, actor,"
          + " repeated and unknown options")
  void refusals() {
    List<List<String>> refused =
        List.of(
            List.of("--env", "prod"),
            List.of("--app", "order-api"),
            List.of("--app", "a".repeat(65), "--env", "prod"),
            List.of("--app", "order api", "--env", "prod"),
            List.of("--app", "order'api", "--env", "prod"),
            List.of("--app", "", "--env", "prod"),
            List.of("--app", "order-api", "--env", "e".repeat(33)),
            List.of("--app", "order-api", "--env", "prod;x"),
            List.of("--app", "order-api", "--env", "pröd"),
            List.of("--app", "order-api", "--env", "prod", "--expires-days", "0"),
            List.of("--app", "order-api", "--env", "prod", "--expires-days", "3651"),
            List.of("--app", "order-api", "--env", "prod", "--expires-days", "07"),
            List.of("--app", "order-api", "--env", "prod", "--expires-days", "-1"),
            List.of("--app", "order-api", "--env", "prod", "--expires-days", "abc"),
            List.of("--app", "order-api", "--env", "prod", "--actor", "Root User"),
            List.of("--app", "a", "--app", "b", "--env", "prod"),
            List.of("--app", "order-api", "--env", "prod", "--json=yes"),
            List.of("--app", "order-api", "--env", "prod", "--frobnicate"));
    for (List<String> args : refused) {
      assertThatThrownBy(() -> TokenCreateCommand.parse(args))
          .as(String.join(" ", args))
          .isInstanceOf(CliUsageException.class);
    }
  }

  @Test
  @DisplayName(
      "T-SV-040j argos-admin token create with invalid options exits 2 without opening the"
          + " database; the usage names exit code 4")
  void exitCodes() {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    ByteArrayOutputStream err = new ByteArrayOutputStream();
    int code =
        AdminCli.run(
            new String[] {"token", "create", "--app", "bad name", "--env", "prod"},
            Map.of(),
            new PrintStream(out, true, StandardCharsets.UTF_8),
            new PrintStream(err, true, StandardCharsets.UTF_8));
    assertThat(code).isEqualTo(AdminCli.EXIT_USAGE);
    assertThat(err.toString(StandardCharsets.UTF_8))
        .contains("argos-admin token create: --app")
        .contains("4 the application has its");
    assertThat(AdminCli.USAGE).contains("token create --app <name> --env <env>");
  }
}
