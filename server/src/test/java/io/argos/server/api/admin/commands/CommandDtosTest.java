/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.api.admin.commands;

import static org.assertj.core.api.Assertions.assertThat;

import io.argos.server.storage.postgres.command.CommandStore.CommandRow;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** {@link CommandDtos.CommandView}: the additive {@code requested_by_name} of WO-37. */
class CommandDtosTest {

  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final UUID ID = UUID.fromString("0b6f3c1e-8f4a-4d2b-9c3e-1a2b3c4d5e6f");
  private static final UUID USER = UUID.fromString("5d0c2a9b-7e1f-4a3c-8b2d-6e5f4a3b2c1d");
  private static final Instant AT = Instant.parse("2026-11-24T01:02:03Z");

  private static CommandRow row(String name) {
    return new CommandRow(
        ID,
        42,
        "thread_dump",
        Map.of(),
        USER,
        AT,
        AT.plusSeconds(60),
        "DONE",
        AT.plusMillis(10),
        AT.plusMillis(250),
        240,
        null,
        1234,
        false,
        "application/json",
        name);
  }

  @Test
  @DisplayName(
      "T-SV-041i the command view carries requested_by_name next to requested_by and keeps every"
          + " existing field")
  void requesterName() {
    CommandDtos.CommandView view = CommandDtos.CommandView.of(row("alice"));
    assertThat(view.requestedByName()).isEqualTo("alice");
    assertThat(view.requestedBy()).isEqualTo(USER);
    assertThat(view.status()).isEqualTo("done");
    assertThat(view.resultSize()).isEqualTo(1234);
    assertThat(view.durationMs()).isEqualTo(240);

    JsonNode json = JSON.valueToTree(view);
    assertThat(json.path("requested_by_name").asString()).isEqualTo("alice");
    assertThat(json.path("requested_by").asString()).isEqualTo(USER.toString());
    assertThat(json.path("command_id").asString()).isEqualTo(ID.toString());
    assertThat(json.path("instance_id").asInt()).isEqualTo(42);
    assertThat(json.has("error")).isTrue();
  }

  @Test
  @DisplayName(
      "T-SV-041i a deleted requester shows its anonymised name; a store without names gives null")
  void deletedAndUnknown() {
    String anonymised = "deleted-" + USER;
    assertThat(CommandDtos.CommandView.of(row(anonymised)).requestedByName()).isEqualTo(anonymised);

    CommandRow withoutName =
        new CommandRow(
            ID,
            42,
            "self_diagnosis",
            Map.of(),
            USER,
            AT,
            AT.plusSeconds(60),
            "QUEUED",
            null,
            null,
            null,
            null,
            null,
            null,
            null);
    CommandDtos.CommandView view = CommandDtos.CommandView.of(withoutName);
    assertThat(view.requestedByName()).isNull();
    assertThat(JSON.valueToTree(view).path("requested_by_name").isNull()).isTrue();
  }
}
