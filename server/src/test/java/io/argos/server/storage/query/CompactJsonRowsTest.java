/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.storage.query;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Reading {@code JSONCompactEachRowWithNamesAndTypes} as ClickHouse 26.8 writes it (sample output
 * recorded from the pinned image: unquoted UInt64 above 2^63, NaN as null, DateTime strings).
 */
class CompactJsonRowsTest {

  private static List<QueryRow> read(String body) throws Exception {
    List<QueryRow> rows = new ArrayList<>();
    CompactJsonRows.read(
        new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8)), rows::add);
    return rows;
  }

  @Test
  @DisplayName("values are converted by the column type; null is never 0")
  void conversions() throws Exception {
    String body =
        "[\"u\", \"t\", \"t2\", \"n\", \"a\", \"m\", \"q\", \"i\", \"s\", \"e\", \"x\"]\n"
            + "[\"UInt64\", \"DateTime64(3, 'UTC')\", \"DateTime('UTC')\", \"Float64\","
            + " \"Array(UInt64)\", \"Map(String, String)\", \"Array(Float64)\", \"Int64\","
            + " \"String\", \"Enum8('HTTP' = 1, 'BATCH' = 2)\", \"Nullable(UInt32)\"]\n"
            + "[18446744073709551615, \"2026-10-23 01:02:03.456\", \"2026-10-23 01:02:03\", null,"
            + " [1,18446744073709551614], {\"k\":\"v\"}, [4.01,7.9,null], -5, \"a\\tb\\\"c\","
            + " \"HTTP\", null]\n";
    List<QueryRow> rows = read(body);
    assertThat(rows).hasSize(1);
    QueryRow r = rows.get(0);
    assertThat(r.columns()).containsExactly("u", "t", "t2", "n", "a", "m", "q", "i", "s", "e", "x");
    assertThat(r.getUInt64("u")).isEqualTo("18446744073709551615");
    assertThat(r.getLong("u")).isEqualTo(-1L);
    assertThat(r.getInstant("t")).isEqualTo(Instant.parse("2026-10-23T01:02:03.456Z"));
    assertThat(r.getInstant("t2")).isEqualTo(Instant.parse("2026-10-23T01:02:03Z"));
    assertThat(r.getDouble("n")).isNaN();
    assertThat(r.getDoubleOrNull("n")).isNull();
    assertThat(r.getLongArray("a")).containsExactly(1L, -2L);
    assertThat(r.getMap("m")).containsEntry("k", "v");
    assertThat(r.getDoubleArray("q")[0]).isEqualTo(4.01);
    assertThat(r.getDoubleArray("q")[2]).isNaN();
    assertThat(r.getLong("i")).isEqualTo(-5L);
    assertThat(r.getString("s")).isEqualTo("a\tb\"c");
    assertThat(r.getString("e")).isEqualTo("HTTP");
    assertThat(r.isNull("x")).isTrue();
    assertThat(r.getLongOrNull("x")).isNull();
    assertThatThrownBy(() -> r.getLong("x")).isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(() -> r.get("missing")).isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  @DisplayName("quoted 64-bit integers (older servers) are parsed by type")
  void quotedIntegers() throws Exception {
    List<QueryRow> rows =
        read("[\"u\",\"i\"]\n[\"UInt64\",\"Int64\"]\n[\"18446744073709551615\",\"-7\"]\n");
    assertThat(rows.get(0).getUInt64("u")).isEqualTo("18446744073709551615");
    assertThat(rows.get(0).getLong("i")).isEqualTo(-7L);
  }

  @Test
  @DisplayName("an error ClickHouse wrote into the body is raised with its code")
  void streamedError() {
    String body =
        "[\"c\"]\n[\"UInt64\"]\n[1]\nCode: 159. DB::Exception: Timeout exceeded: elapsed 1000 ms."
            + " (TIMEOUT_EXCEEDED) (version 26.8.34.13 (official build))\n";
    assertThatThrownBy(() -> read(body))
        .isInstanceOfSatisfying(
            ClickHouseErrors.StreamedError.class, e -> assertThat(e.code()).isEqualTo(159));
    assertThatThrownBy(() -> read("{\"exception\": \"Code: 241. DB::Exception: Memory\"}\n"))
        .isInstanceOfSatisfying(
            ClickHouseErrors.StreamedError.class, e -> assertThat(e.code()).isEqualTo(241));
  }

  @Test
  @DisplayName("an empty body has no columns and no rows")
  void empty() throws Exception {
    assertThat(read("")).isEmpty();
    assertThat(read("[\"c\"]\n[\"UInt8\"]\n")).isEmpty();
  }
}
