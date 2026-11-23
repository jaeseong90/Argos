/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.cli;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.argos.server.ops.rollup.RebuildResult;
import io.argos.server.ops.rollup.RebuildResult.Level;
import io.argos.server.ops.rollup.RebuildResult.TableResult;
import io.argos.server.storage.clickhouse.RollupPartitions.Digest;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Argument parsing, exit codes and output of {@code argos-admin rollup rebuild} (17 §4.4). */
@Tag("req:F-S22")
@Tag("req:DSN-17-14")
class AdminCliTest {

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
  @DisplayName("T-SV-019 options: --date (both forms), repeated --table, --json")
  void parse() throws Exception {
    RollupRebuildCommand.Options o =
        RollupRebuildCommand.parse(
            List.of("--date", "2026-10-31", "--table", "sql_stat_1m", "--table=ext_stat_1m"));
    assertThat(o.day()).isEqualTo(LocalDate.of(2026, 10, 31));
    assertThat(o.tables()).containsExactlyInAnyOrder("sql_stat_1m", "ext_stat_1m");
    assertThat(o.json()).isFalse();
    assertThat(RollupRebuildCommand.parse(List.of("--date=2026-11-01", "--json")))
        .isEqualTo(new RollupRebuildCommand.Options(LocalDate.of(2026, 11, 1), Set.of(), true));
  }

  @Test
  @DisplayName(
      "T-SV-019 invalid arguments are refused: missing or repeated --date, impossible date, table"
          + " name, unknown option, --app (C-59 (c))")
  void parseRefusals() {
    assertThatThrownBy(() -> RollupRebuildCommand.parse(List.of()))
        .isInstanceOf(CliUsageException.class)
        .hasMessage("--date is required");
    assertThatThrownBy(
            () -> RollupRebuildCommand.parse(List.of("--date", "2026-11-01", "--date=2026-11-02")))
        .hasMessage("--date given twice");
    assertThatThrownBy(() -> RollupRebuildCommand.parse(List.of("--date", "2026-02-30")))
        .hasMessageContaining("yyyy-MM-dd");
    assertThatThrownBy(() -> RollupRebuildCommand.parse(List.of("--date")))
        .hasMessage("--date needs a value");
    assertThatThrownBy(
            () ->
                RollupRebuildCommand.parse(
                    List.of("--date", "2026-11-01", "--table", "x; DROP TABLE xlog")))
        .hasMessageContaining("--table must be a rollup table name");
    assertThatThrownBy(
            () -> RollupRebuildCommand.parse(List.of("--date", "2026-11-01", "--app", "3")))
        .hasMessage("Unknown option: --app");
  }

  @Test
  @DisplayName(
      "T-SV-019 exit code 2 and usage on standard error for invalid arguments, nothing on standard"
          + " output; help exits 0")
  void exitCodes() {
    assertThat(run("rollup", "rebuild", "--table", "app_stat_1m")).isEqualTo(AdminCli.EXIT_USAGE);
    assertThat(err()).contains("--date is required").contains("Usage: argos-admin rollup rebuild");
    assertThat(out()).isEmpty();

    assertThat(run("rollup", "explode")).isEqualTo(AdminCli.EXIT_USAGE);
    assertThat(err()).contains("unknown command: rollup explode");
    assertThat(run()).isEqualTo(AdminCli.EXIT_USAGE);

    assertThat(run("help")).isEqualTo(AdminCli.EXIT_OK);
    assertThat(out()).contains("rollup rebuild --date <yyyy-MM-dd>");
    assertThat(run("rollup", "rebuild", "--help")).isEqualTo(AdminCli.EXIT_OK);
    assertThat(AdminCli.selected(new String[] {"admin", "rollup"})).isTrue();
    assertThat(AdminCli.selected(new String[] {"--server.port=0"})).isFalse();
    assertThat(AdminCli.commandArguments(new String[] {"admin", "rollup", "rebuild"}))
        .containsExactly("rollup", "rebuild");
  }

  private static RebuildResult sample() {
    Digest a = new Digest(1440, "1234567890", List.of());
    Digest b = new Digest(1440, "99", List.of());
    return new RebuildResult(
        LocalDate.of(2026, 10, 31),
        List.of(
            new TableResult("app_stat_1m", Level.MINUTE, "20261031", a, a, 2880, false, 12),
            new TableResult("metric_1h", Level.HOUR, "202610", a, b, 24, false, 30),
            new TableResult(
                "error_stat_1m",
                Level.MINUTE,
                "20261031",
                new Digest(3, "5", List.of()),
                new Digest(0, "0", List.of()),
                0,
                true,
                4)),
        51);
  }

  @Test
  @DisplayName("T-SV-019 --json: one object with the documented fields per table")
  void json() {
    JsonNode root = JsonMapper.builder().build().readTree(RollupRebuildCommand.json(sample()));
    assertThat(root.get("command").asString()).isEqualTo("rollup rebuild");
    assertThat(root.get("day").asString()).isEqualTo("2026-10-31");
    assertThat(root.get("identical").asBoolean()).isFalse();
    assertThat(root.get("duration_ms").asLong()).isEqualTo(51);
    JsonNode first = root.get("tables").get(0);
    assertThat(first.propertyNames())
        .containsExactly(
            "table",
            "level",
            "partition",
            "staging_rows",
            "dropped",
            "identical",
            "before",
            "after",
            "excluded_columns",
            "duration_ms");
    assertThat(first.get("level").asString()).isEqualTo("1m");
    assertThat(first.get("before").get("keys").asLong()).isEqualTo(1440);
    assertThat(first.get("before").get("hash").asString()).isEqualTo("1234567890");
    assertThat(root.get("tables").get(1).get("identical").asBoolean()).isFalse();
    assertThat(root.get("tables").get(2).get("dropped").asBoolean()).isTrue();
  }

  @Test
  @DisplayName("T-SV-019 table output: summary line, one row per table with its result")
  void table() {
    String text = RollupRebuildCommand.table(sample());
    List<String> lines = text.lines().toList();
    assertThat(lines.get(0))
        .isEqualTo("Rollup rebuild of 2026-10-31 (UTC): 3 tables, 1 identical, 2 changed, 51 ms");
    assertThat(lines.get(1)).startsWith("TABLE").contains("KEYS BEFORE").contains("RESULT");
    assertThat(lines.get(2)).startsWith("app_stat_1m").contains("identical");
    assertThat(lines.get(3)).startsWith("metric_1h").contains("changed");
    assertThat(lines.get(4)).startsWith("error_stat_1m").contains("dropped");
  }
}
