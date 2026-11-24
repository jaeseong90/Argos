/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.cli;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Argument parsing and the failures before any database of {@code argos-admin secrets rekey} and
 * its alias {@code master-key rotate} (16 §8.2, 17 §4.4, ADR-0010 C-226 (2)).
 */
@Tag("req:SEC-52")
@Tag("req:DSN-17-14")
class SecretsRekeyCommandTest {

  private final ByteArrayOutputStream outBytes = new ByteArrayOutputStream();
  private final ByteArrayOutputStream errBytes = new ByteArrayOutputStream();
  private final PrintStream out = new PrintStream(outBytes, true, StandardCharsets.UTF_8);
  private final PrintStream err = new PrintStream(errBytes, true, StandardCharsets.UTF_8);

  @TempDir Path dir;

  @Test
  @DisplayName(
      "T-SV-053a secrets rekey options: none (configured key, batch 100), --new-key, --batch,"
          + " --json")
  void parse() throws Exception {
    assertThat(SecretsRekeyCommand.parse(false, List.of()))
        .isEqualTo(new SecretsRekeyCommand.Options("secrets rekey", null, 100, false));
    assertThat(
            SecretsRekeyCommand.parse(
                false, List.of("--new-key", "/run/new.key", "--batch=5", "--json")))
        .isEqualTo(
            new SecretsRekeyCommand.Options("secrets rekey", Path.of("/run/new.key"), 5, true));
  }

  @Test
  @DisplayName("T-SV-053b master-key rotate takes --new-key-file (and not --new-key)")
  void alias() throws Exception {
    assertThat(SecretsRekeyCommand.parse(true, List.of("--new-key-file", "k", "--batch", "10000")))
        .isEqualTo(
            new SecretsRekeyCommand.Options("master-key rotate", Path.of("k"), 10000, false));
    assertThatThrownBy(() -> SecretsRekeyCommand.parse(true, List.of("--new-key", "k")))
        .hasMessage("Unknown option: --new-key");
  }

  @Test
  @DisplayName("T-SV-053a refusals: repeated key, batch out of range, flag with a value")
  void refusals() {
    assertThatThrownBy(
            () -> SecretsRekeyCommand.parse(false, List.of("--new-key", "a", "--new-key=b")))
        .isInstanceOf(CliUsageException.class)
        .hasMessage("--new-key given twice");
    for (String bad : List.of("0", "10001", "-1", "01", "x")) {
      assertThatThrownBy(() -> SecretsRekeyCommand.parse(false, List.of("--batch", bad)))
          .as(bad)
          .hasMessageStartingWith("--batch must be a whole number from 1 to 10000");
    }
    assertThatThrownBy(() -> SecretsRekeyCommand.parse(false, List.of("--json=yes")))
        .hasMessage("--json takes no value");
  }

  @Test
  @DisplayName(
      "T-SV-053c exit 2 for invalid arguments, usage listed; exit 1 with a JSON error and no"
          + " database when the configuration has no master key")
  void exitCodes() {
    assertThat(AdminCli.run(new String[] {"secrets", "rekey", "--batch", "0"}, Map.of(), out, err))
        .isEqualTo(AdminCli.EXIT_USAGE);
    assertThat(errBytes.toString(StandardCharsets.UTF_8)).contains("Usage: argos-admin secrets");
    assertThat(AdminCli.run(new String[] {"--help"}, Map.of(), out, err)).isZero();
    assertThat(outBytes.toString(StandardCharsets.UTF_8))
        .contains("secrets rekey")
        .contains("master-key rotate");
    outBytes.reset();
    int code =
        AdminCli.run(
            new String[] {"master-key", "rotate", "--json"},
            Map.of(
                "argos.web.public-url",
                "https://argos.example.com",
                "argos.master-key-file",
                dir.resolve("absent.key").toString()),
            out,
            err);
    assertThat(code).isEqualTo(AdminCli.EXIT_FAILURE);
    assertThat(outBytes.toString(StandardCharsets.UTF_8))
        .contains("\"command\":\"master-key rotate\"")
        .contains("\"code\":\"master_key\"")
        .contains("No master key");
  }
}
