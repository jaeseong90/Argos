/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.storage.clickhouse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

class ClickHouseSupportTest {

  @Test
  @Tag("req:N-18")
  @DisplayName("memory budget: 13 §7.1 proportions, 0.9 of the container limit for the server")
  void memoryBudget() {
    MemoryBudget s = MemoryBudget.sTopology();
    assertThat(s.environment())
        .containsEntry("ARGOS_CH_MAX_SERVER_MEMORY_USAGE", "8053063680")
        .containsEntry("ARGOS_CH_READER_MAX_MEMORY_USAGE", "3221225472")
        .containsEntry("ARGOS_CH_WRITER_MAX_MEMORY_USAGE", "2147483648");

    MemoryBudget five = MemoryBudget.forContainerLimit(5 * MemoryBudget.GIB);
    assertThat(five.serverBytes()).isEqualTo(4_831_838_208L);
    assertThat((double) five.readerQueryBytes() / five.serverBytes()).isCloseTo(0.4, within());
    assertThat((double) five.writerQueryBytes() / five.serverBytes()).isCloseTo(4.0 / 15, within());

    assertThatThrownBy(() -> MemoryBudget.forContainerLimit(512L << 20))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  @Tag("req:N-21")
  @DisplayName("the bundled ClickHouse is an exact LTS version pinned by digest (DSN-13-18)")
  void pinnedImage() {
    ClickHouseImage bundled = ClickHouseImage.bundled();
    assertThat(bundled.version()).isEqualTo("26.8.34.13");
    assertThat(bundled.image())
        .isEqualTo(
            "clickhouse/clickhouse-server:26.8.34.13@sha256:"
                + "8b28eb09479c36c4533eec7a6268917d04ba3a6fd90e3f4205f70e761b6d7779");
    assertThat(ClickHouseImage.previousLts().version()).startsWith("26.3.");
    assertThatThrownBy(() -> new ClickHouseImage("26.8", "clickhouse/clickhouse-server:26.8"))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  @DisplayName("TabSeparated parsing unescapes ClickHouse escapes")
  void tabSeparated() {
    assertThat(ClickHouseHttp.parseTabSeparated("a\\tb\tc\\\\d\n1\tx\\ny\n"))
        .isEqualTo(List.of(List.of("a\tb", "c\\d"), List.of("1", "x\ny")));
    assertThat(ClickHouseHttp.parseTabSeparated("")).isEmpty();
  }

  @Test
  @DisplayName("error code and name are parsed from the HTTP error body")
  void errorParsing() {
    ClickHouseException e =
        new ClickHouseException(
            500,
            null,
            "Code: 497. DB::Exception: argos_writer: Not enough privileges. To execute this query,"
                + " it's necessary to have the grant SELECT(app_id) ON argos.xlog. (ACCESS_DENIED)"
                + " (version 26.8.34.13 (official build))");
    assertThat(e.code()).isEqualTo(497);
    assertThat(e.errorName()).isEqualTo("ACCESS_DENIED");
    assertThat(new ClickHouseException(500, "164", "Cannot modify (READONLY)").code())
        .isEqualTo(164);
  }

  @Test
  @Tag("req:SEC-74")
  @DisplayName(
      "generated passwords: 32 random bytes base64url, SHA-256 hex for password_sha256_hex")
  void passwords() {
    String a = ArgosClickHouse.newPassword();
    String b = ArgosClickHouse.newPassword();
    assertThat(a).hasSize(43).matches("[A-Za-z0-9_-]+").isNotEqualTo(b);
    assertThat(ArgosClickHouse.sha256Hex("abc"))
        .isEqualTo("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
  }

  private static org.assertj.core.data.Offset<Double> within() {
    return org.assertj.core.data.Offset.offset(0.001);
  }
}
