/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.cli;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.argos.server.ops.retention.RetentionApplier;
import io.argos.server.ops.retention.RetentionApplier.Drop;
import io.argos.server.ops.retention.RetentionApplier.TableState;
import io.argos.server.ops.retention.RetentionPlanner.TtlChange;
import io.argos.server.ops.retention.RetentionSettings;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Options and output of {@code argos-admin retention show|apply} (17 §4.4, §9.1; WO-28). */
@Tag("req:N-08")
class RetentionCommandTest {

  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final RetentionSettings S = new RetentionSettings(30, 7, 21, 14, 90, 365);
  private static final Map<String, String> SOURCES =
      Map.of(
          "xlog_days", "settings",
          "profile_days", "config",
          "stat_raw_days", "config",
          "metric_raw_days", "config",
          "rollup_1m_days", "config",
          "rollup_1h_days", "config");

  @Test
  @DisplayName("T-SV-053f retention show|apply take --json only; the command line lists them")
  void parse() throws Exception {
    assertThat(RetentionCommand.parse(false, List.of()).json()).isFalse();
    assertThat(RetentionCommand.parse(true, List.of("--json")).command())
        .isEqualTo("retention apply");
    assertThatThrownBy(() -> RetentionCommand.parse(false, List.of("--json", "--json")))
        .isInstanceOf(CliUsageException.class);
    assertThatThrownBy(() -> RetentionCommand.parse(false, List.of("--json=yes")))
        .isInstanceOf(CliUsageException.class);
    assertThatThrownBy(() -> RetentionCommand.parse(true, List.of("--table", "xlog")))
        .isInstanceOf(CliUsageException.class);
    assertThat(AdminCli.USAGE).contains("retention show [--json]", "retention apply [--json]");
  }

  @Test
  @DisplayName("T-SV-053d/e the --json documents name values, sources, tables, changes and drops")
  void json() {
    JsonNode show =
        JSON.readTree(
            RetentionCommand.showJson(
                S, SOURCES, List.of(new TableState("xlog", "xlog_days", 30, 30, null, 0, 0, 0))));
    assertThat(show.path("values").path("stat_raw_days").asInt()).isEqualTo(21);
    assertThat(show.path("sources").path("xlog_days").asString()).isEqualTo("settings");
    assertThat(show.path("tables").get(0).path("oldest_partition").isNull()).isTrue();
    JsonNode apply =
        JSON.readTree(
            RetentionCommand.applyJson(
                S,
                SOURCES,
                new RetentionApplier.Result(
                    List.of(new TtlChange("xlog", "xlog_days", "toDateTime(ts)", 45, 30)),
                    List.of(),
                    List.of()),
                List.of(new Drop("xlog", "20261001", 10, 1000))));
    assertThat(apply.path("command").asString()).isEqualTo("retention apply");
    assertThat(apply.path("changes").get(0).path("to_days").asInt()).isEqualTo(30);
    assertThat(apply.path("dropped").get(0).path("partition").asString()).isEqualTo("20261001");
  }
}
