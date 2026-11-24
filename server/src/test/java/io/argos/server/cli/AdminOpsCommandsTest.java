/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.cli;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * Argument parsing, usage and the database-free commands of the WO-28 {@code argos-admin}
 * subcommands (17 §4.4): {@code migrate}, {@code config}, {@code cleanup}, {@code token
 * issue|revoke|rotate}, {@code setup-token regenerate}, {@code tls reload}, {@code bundle}, {@code
 * version}. No database is opened here; the integration test {@code AdminCliOpsIT} runs them.
 */
@Tag("req:DSN-17-14")
class AdminOpsCommandsTest {

  private final ByteArrayOutputStream outBytes = new ByteArrayOutputStream();
  private final ByteArrayOutputStream errBytes = new ByteArrayOutputStream();
  private final PrintStream out = new PrintStream(outBytes, true, StandardCharsets.UTF_8);
  private final PrintStream err = new PrintStream(errBytes, true, StandardCharsets.UTF_8);

  private int run(String... args) {
    return AdminCli.run(args, Map.of(), out, err);
  }

  @Test
  @DisplayName(
      "T-SV-053g version --json: version, commit, protocol of the jar and the runtime, without"
          + " configuration or database; table output; unknown option exit 2")
  void version() {
    assertThat(run("version", "--json")).isZero();
    JsonNode node = CliOutput.JSON.readTree(outBytes.toString(StandardCharsets.UTF_8));
    assertThat(node.path("command").asString()).isEqualTo("version");
    assertThat(node.has("version")).isTrue();
    assertThat(node.has("commit")).isTrue();
    assertThat(node.path("java_version").asString()).isEqualTo(System.getProperty("java.version"));
    outBytes.reset();
    assertThat(run("version")).isZero();
    assertThat(outBytes.toString(StandardCharsets.UTF_8)).startsWith("Argos server ");
    assertThat(run("version", "--verbose")).isEqualTo(AdminCli.EXIT_USAGE);
  }

  @Test
  @DisplayName(
      "T-SV-053g every new subcommand has usage text, --help exit 0, and is listed in the overall"
          + " usage")
  void usage() {
    for (String[] cmd :
        List.of(
            new String[] {"migrate"},
            new String[] {"config", "effective"},
            new String[] {"config", "check"},
            new String[] {"cleanup", "instances"},
            new String[] {"cleanup", "dictionary"},
            new String[] {"cleanup", "commands"},
            new String[] {"token", "issue"},
            new String[] {"token", "revoke"},
            new String[] {"token", "rotate"},
            new String[] {"setup-token", "regenerate"},
            new String[] {"tls", "reload"},
            new String[] {"bundle"},
            new String[] {"version"})) {
      String[] args = java.util.Arrays.copyOf(cmd, cmd.length + 1);
      args[cmd.length] = "--help";
      outBytes.reset();
      assertThat(run(args)).as(String.join(" ", cmd)).isZero();
      assertThat(outBytes.toString(StandardCharsets.UTF_8))
          .contains("Usage: argos-admin " + cmd[0]);
      assertThat(AdminCli.USAGE).contains("  " + cmd[0]);
    }
    assertThat(run("cleanup", "everything")).isEqualTo(AdminCli.EXIT_USAGE);
  }

  @Test
  @DisplayName(
      "T-SV-053g options of config, cleanup, tls reload and bundle; refusals exit 2 before any"
          + " configuration is loaded")
  void options() throws Exception {
    assertThat(ConfigCommand.parse(false, List.of("--changed", "--json")))
        .isEqualTo(new ConfigCommand.Options(false, true, true));
    assertThatThrownBy(() -> ConfigCommand.parse(true, List.of("--changed")))
        .isInstanceOf(CliUsageException.class);
    assertThat(CleanupCommand.parse("dictionary", List.of("--json")))
        .isEqualTo(new CleanupCommand.Options(CleanupCommand.Target.DICTIONARY, true));
    assertThat(TlsReloadCommand.parse(List.of()))
        .isEqualTo(new TlsReloadCommand.Options(TlsReloadCommand.DEFAULT_WAIT_SECONDS, false));
    assertThat(TlsReloadCommand.parse(List.of("--wait", "0", "--json")).waitSeconds()).isZero();
    assertThatThrownBy(() -> TlsReloadCommand.parse(List.of("--wait", "301")))
        .isInstanceOf(CliUsageException.class);
    BundleCommand.Options b = BundleCommand.parse(List.of("--since", "7d", "--out", "x", "--json"));
    assertThat(b.since()).isEqualTo(Duration.ofDays(7));
    assertThat(b.out()).isEqualTo(Path.of("x"));
    assertThat(BundleCommand.parse(List.of()).since()).isEqualTo(Duration.ofHours(24));
    assertThatThrownBy(() -> BundleCommand.since("8d")).isInstanceOf(CliUsageException.class);
    assertThatThrownBy(() -> BundleCommand.since("169h")).isInstanceOf(CliUsageException.class);
    assertThatThrownBy(() -> BundleCommand.since("0h")).isInstanceOf(CliUsageException.class);
    assertThat(BundleCommand.since("168h")).isEqualTo(Duration.ofDays(7));
    assertThat(run("bundle", "--since", "1y")).isEqualTo(AdminCli.EXIT_USAGE);
    assertThat(run("migrate", "--force")).isEqualTo(AdminCli.EXIT_USAGE);
    assertThat(run("setup-token", "regenerate", "--token", "x")).isEqualTo(AdminCli.EXIT_USAGE);
  }

  @Test
  @DisplayName(
      "T-SV-053g token revoke|rotate: exactly one of --id and --prefix, a prefix never a token,"
          + " --reason required for revoke only, --grace-days 1 to 30 for rotate only")
  void tokenOptions() throws Exception {
    UUID id = UUID.randomUUID();
    assertThat(TokenCommand.parse(false, List.of("--id", id.toString(), "--reason", "leaked")))
        .isEqualTo(new TokenCommand.Options(false, id, null, "leaked", 7, false));
    assertThat(TokenCommand.parse(true, List.of("--prefix", "argos_abcd1234", "--grace-days", "3")))
        .isEqualTo(new TokenCommand.Options(true, null, "argos_abcd1234", null, 3, false));
    String token = "argos_" + "a".repeat(43);
    assertThatThrownBy(() -> TokenCommand.parse(true, List.of("--prefix", token)))
        .isInstanceOf(CliUsageException.class)
        .hasMessageNotContaining(token);
    assertThatThrownBy(() -> TokenCommand.parse(false, List.of("--id", id.toString())))
        .hasMessage("--reason is required");
    assertThatThrownBy(
            () -> TokenCommand.parse(true, List.of("--reason", "x", "--id", id.toString())))
        .isInstanceOf(CliUsageException.class);
    assertThatThrownBy(
            () ->
                TokenCommand.parse(
                    true, List.of("--id", id.toString(), "--prefix", "argos_abcd1234")))
        .hasMessage("give exactly one of --id and --prefix");
    assertThatThrownBy(
            () -> TokenCommand.parse(true, List.of("--id", id.toString(), "--grace-days", "31")))
        .isInstanceOf(CliUsageException.class);
    assertThatThrownBy(() -> TokenCommand.parse(false, List.of("--id", "nope", "--reason", "r")))
        .isInstanceOf(CliUsageException.class);
  }
}
