/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.storage.schema;

import static org.assertj.core.api.Assertions.assertThat;

import io.argos.storage.clickhouse.RepositoryLayout;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The integration tests apply V001 with the production retention and write their rows relative to
 * {@code Workload.anchorDay()} (today minus a few days). A fixed date in their sources is a row
 * time or a partition that ages out: ClickHouse drops rows older than a table's TTL at insert time
 * while the views still receive them, so the raw-versus-rollup assertions start failing on a
 * calendar date. The check rejects these forms:
 *
 * <ul>
 *   <li>a {@code yyyy-MM-dd} string,
 *   <li>a java.time factory with a literal year ({@code LocalDate.of(20xx, ...)}, {@code
 *       LocalDateTime}, {@code YearMonth}, {@code ZonedDateTime}, {@code OffsetDateTime}),
 *   <li>a literal epoch ({@code Instant.ofEpochMilli(<digits>)}, {@code Instant.ofEpochSecond}),
 *   <li>a {@code yyyyMM} integer (a {@code toYYYYMM} partition or condition).
 * </ul>
 *
 * A line that matches but is not a row time says why ({@code fixed-date-ok: <reason>}).
 */
class FixedDatesTest {

  private static final List<Pattern> FIXED_DATE_PATTERNS =
      List.of(
          Pattern.compile("(?<!\\d)20\\d\\d-\\d\\d-\\d\\d(?!\\d)"),
          Pattern.compile(
              "\\b(LocalDate|LocalDateTime|YearMonth|ZonedDateTime|OffsetDateTime)\\.of\\(\\s*20\\d\\d"),
          Pattern.compile("Instant\\.ofEpoch(Milli|Second)\\(\\s*\\d"),
          Pattern.compile("(?<![\\d_])20\\d\\d(0[1-9]|1[0-2])(?![\\d_])"));
  private static final String MARKER = "fixed-date-ok:";

  static boolean hasFixedDate(String line) {
    for (Pattern pattern : FIXED_DATE_PATTERNS) {
      if (pattern.matcher(line).find()) {
        return true;
      }
    }
    return false;
  }

  @Test
  @DisplayName("schema integration tests derive row dates from the anchor day (no fixed dates)")
  void integrationTestsHaveNoFixedDates() throws IOException {
    Path dir = RepositoryLayout.root().resolve("test/storage/schema-tests/src/integrationTest");
    List<Path> sources;
    try (Stream<Path> files = Files.walk(dir)) {
      sources = files.filter(f -> f.toString().endsWith(".java")).sorted().toList();
    }
    assertThat(sources.stream().map(f -> f.getFileName().toString()))
        .contains("Workload.java", "RollupViewsIT.java", "ConfirmationItemsIT.java");

    List<String> fixed = new ArrayList<>();
    List<String> marked = new ArrayList<>();
    for (Path file : sources) {
      List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
      for (int i = 0; i < lines.size(); i++) {
        String line = lines.get(i);
        if (hasFixedDate(line)) {
          String where = dir.relativize(file) + ":" + (i + 1) + ": " + line.strip();
          (line.contains(MARKER) ? marked : fixed).add(where);
        }
      }
    }
    assertThat(fixed).as("date literals without '" + MARKER + " <reason>'").isEmpty();
    // The one exception today: the tx_id epoch of Workload (a format constant).
    assertThat(marked).hasSize(1).allMatch(l -> l.contains("tx_id epoch"));
  }

  @ParameterizedTest
  @DisplayName("fixed date forms are detected")
  @ValueSource(
      strings = {
        "toDate(ts) = '2026-10-24'",
        "LocalDate.of(2026, 10, 24)",
        "LocalDateTime.of(2026, 10, 24, 0, 0)",
        "YearMonth.of(2026, 10)",
        "Instant.ofEpochMilli(1761264000000L)",
        "Instant.ofEpochSecond(0)",
        "static final int MONTH = 202610;",
        "\"toYYYYMM(hour) = 202610\""
      })
  void detectsFixedDates(String line) {
    assertThat(hasFixedDate(line)).isTrue();
  }

  @ParameterizedTest
  @DisplayName("derived dates and other numbers are not reported")
  @ValueSource(
      strings = {
        "Instant.ofEpochMilli(tsMs / 10_000 * 10_000)",
        "FROM numbers(300000)",
        "1_767_225_600_000L",
        "200_001",
        "LocalDate.now(ZoneOffset.UTC)",
        "RollupRebuild.monthPartition(DAY)",
        "202613",
        "2026100"
      })
  void ignoresDerivedDates(String line) {
    assertThat(hasFixedDate(line)).isFalse();
  }
}
