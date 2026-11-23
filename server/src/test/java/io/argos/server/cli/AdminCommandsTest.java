/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.cli;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Argument parsing and usage exit codes of {@code argos-admin consistency check} and {@code
 * argos-admin apdex reapply} (17 §4.4, 12 §9.1, D-17). No database is opened: every case here fails
 * or answers before the configuration is loaded.
 */
@Tag("req:F-S22")
@Tag("req:D-17")
@Tag("req:DSN-17-14")
class AdminCommandsTest {

  private final ByteArrayOutputStream outBytes = new ByteArrayOutputStream();
  private final ByteArrayOutputStream errBytes = new ByteArrayOutputStream();
  private final PrintStream out = new PrintStream(outBytes, true, StandardCharsets.UTF_8);
  private final PrintStream err = new PrintStream(errBytes, true, StandardCharsets.UTF_8);

  private int run(String... args) {
    return AdminCli.run(args, Map.of(), out, err);
  }

  private String out() {
    return outBytes.toString(StandardCharsets.UTF_8);
  }

  private String err() {
    return errBytes.toString(StandardCharsets.UTF_8);
  }

  @Test
  @DisplayName("T-SV-019 consistency check options: --date (both forms), --rebuild, --json")
  void consistencyOptions() throws Exception {
    assertThat(ConsistencyCheckCommand.parse(List.of("--date", "2026-11-22")))
        .isEqualTo(new ConsistencyCheckCommand.Options(LocalDate.of(2026, 11, 22), false, false));
    assertThat(ConsistencyCheckCommand.parse(List.of("--rebuild", "--date=2026-02-28", "--json")))
        .isEqualTo(new ConsistencyCheckCommand.Options(LocalDate.of(2026, 2, 28), true, true));
  }

  @Test
  @DisplayName(
      "T-SV-019 consistency check refuses a missing, repeated or impossible --date, flags with a"
          + " value, unknown options and --app")
  void consistencyRefusals() {
    assertThatThrownBy(() -> ConsistencyCheckCommand.parse(List.of()))
        .isInstanceOf(CliUsageException.class)
        .hasMessage("--date is required");
    assertThatThrownBy(
            () ->
                ConsistencyCheckCommand.parse(List.of("--date", "2026-11-01", "--date=2026-11-02")))
        .hasMessage("--date given twice");
    assertThatThrownBy(() -> ConsistencyCheckCommand.parse(List.of("--date", "2026-02-29")))
        .hasMessageContaining("yyyy-MM-dd");
    assertThatThrownBy(() -> ConsistencyCheckCommand.parse(List.of("--date", "--json")))
        .hasMessage("--date needs a value");
    assertThatThrownBy(
            () -> ConsistencyCheckCommand.parse(List.of("--date", "2026-11-01", "--rebuild=yes")))
        .hasMessage("--rebuild takes no value");
    assertThatThrownBy(
            () -> ConsistencyCheckCommand.parse(List.of("--date", "2026-11-01", "--app", "3")))
        .hasMessage("Unknown option: --app");
  }

  @Test
  @DisplayName(
      "T-ACC-17 apdex reapply options: --app, optional --service (unsigned 64-bit), --from, --to,"
          + " --json")
  void reapplyOptions() throws Exception {
    ApdexReapplyCommand.Options o =
        ApdexReapplyCommand.parse(
            List.of("--app", "7", "--from", "2026-11-20", "--to=2026-11-22", "--json"));
    assertThat(o)
        .isEqualTo(
            new ApdexReapplyCommand.Options(
                7, null, LocalDate.of(2026, 11, 20), LocalDate.of(2026, 11, 22), true));
    ApdexReapplyCommand.Options service =
        ApdexReapplyCommand.parse(
            List.of(
                "--service=18446744073709551615",
                "--app=2",
                "--from=2026-11-22",
                "--to",
                "2026-11-22"));
    assertThat(service.serviceId()).isEqualTo(-1L);
    assertThat(Long.toUnsignedString(service.serviceId())).isEqualTo("18446744073709551615");
    assertThat(service.json()).isFalse();
  }

  @Test
  @DisplayName(
      "T-ACC-17 apdex reapply refuses a missing --app or range, --from after --to, invalid ids"
          + " and dates, repeated options")
  void reapplyRefusals() {
    assertThatThrownBy(
            () -> ApdexReapplyCommand.parse(List.of("--from", "2026-11-20", "--to", "2026-11-21")))
        .hasMessage("--app is required");
    assertThatThrownBy(() -> ApdexReapplyCommand.parse(List.of("--app", "1", "--to", "2026-11-21")))
        .hasMessage("--from and --to are required");
    assertThatThrownBy(
            () ->
                ApdexReapplyCommand.parse(
                    List.of("--app", "1", "--from", "2026-11-22", "--to", "2026-11-21")))
        .hasMessage("--from 2026-11-22 is after --to 2026-11-21");
    assertThatThrownBy(
            () ->
                ApdexReapplyCommand.parse(
                    List.of("--app", "0", "--from", "2026-11-20", "--to", "2026-11-21")))
        .hasMessageContaining("--app must be a positive application id");
    assertThatThrownBy(
            () ->
                ApdexReapplyCommand.parse(
                    List.of("--app", "x1", "--from", "2026-11-20", "--to", "2026-11-21")))
        .hasMessageContaining("--app must be a positive application id");
    assertThatThrownBy(
            () ->
                ApdexReapplyCommand.parse(
                    List.of(
                        "--app",
                        "1",
                        "--service",
                        "-5",
                        "--from",
                        "2026-11-20",
                        "--to",
                        "2026-11-21")))
        .hasMessageContaining("--service must be an unsigned decimal id");
    assertThatThrownBy(
            () ->
                ApdexReapplyCommand.parse(
                    List.of(
                        "--app",
                        "1",
                        "--service=18446744073709551616",
                        "--from",
                        "2026-11-20",
                        "--to",
                        "2026-11-21")))
        .hasMessageContaining("--service must be an unsigned decimal id");
    assertThatThrownBy(
            () ->
                ApdexReapplyCommand.parse(
                    List.of(
                        "--app", "1", "--app", "2", "--from", "2026-11-20", "--to", "2026-11-21")))
        .hasMessage("--app given twice");
    assertThatThrownBy(
            () ->
                ApdexReapplyCommand.parse(
                    List.of("--app", "1", "--from", "2026-13-01", "--to", "2026-11-21")))
        .hasMessageContaining("--from must be a date yyyy-MM-dd");
  }

  @Test
  @DisplayName(
      "T-SV-019 invalid arguments of both commands exit 2 with the command's usage on standard"
          + " error, before any database is opened; --help prints the usage and exits 0")
  void usageExitCodes() {
    assertThat(run("consistency", "check", "--date", "yesterday")).isEqualTo(AdminCli.EXIT_USAGE);
    assertThat(err()).contains("argos-admin consistency check: --date must be a date");
    assertThat(err()).contains("Usage: argos-admin consistency check --date <yyyy-MM-dd>");
    assertThat(out()).isEmpty();

    assertThat(run("apdex", "reapply", "--app", "1")).isEqualTo(AdminCli.EXIT_USAGE);
    assertThat(err()).contains("argos-admin apdex reapply: --from and --to are required");
    assertThat(err())
        .contains(
            "Usage: argos-admin apdex reapply --app <id> [--service <id>] --from <yyyy-MM-dd>"
                + " --to <yyyy-MM-dd> [--json]");

    assertThat(run("apdex", "reapply", "--help")).isEqualTo(AdminCli.EXIT_OK);
    assertThat(out()).contains("Raw retention only.");
    assertThat(run("help")).isEqualTo(AdminCli.EXIT_OK);
    assertThat(out())
        .contains("consistency check --date <yyyy-MM-dd> [--rebuild] [--json]")
        .contains("apdex reapply --app <id>");
  }
}
