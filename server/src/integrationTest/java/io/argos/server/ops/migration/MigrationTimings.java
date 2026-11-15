/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.ops.migration;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The timing table of the migration tests: {@code server/build/reports/migration/timings.md}
 * (system property {@code argos.migration.reportDir}). Each test class replaces its own section
 * ({@code <!-- section:<name> -->} ... {@code <!-- end:<name> -->}), so the file holds the latest
 * run of every path. Durations measured on a shared developer machine are reference values only.
 */
final class MigrationTimings {

  /** Limit of one script or step on 1/100 data (13 §13.2, T-SV-002). */
  static final long LIMIT_MS = 10 * 60 * 1000L;

  private final String section;
  private final String title;
  private final List<String> rows = new ArrayList<>();
  private final List<String> notes = new ArrayList<>();

  MigrationTimings(String section, String title) {
    this.section = section;
    this.title = title;
  }

  /** One PostgreSQL row per Flyway migration of {@code result}. */
  void flyway(String path, PostgresMigrationPhase.Result result) {
    for (PostgresMigrationPhase.Applied m : result.applied()) {
      row(path, "PostgreSQL", "V" + m.version() + " " + m.description(), m.executionTimeMs(), "");
    }
  }

  /** One ClickHouse row per applied script, step and backfill partition of {@code result}. */
  void clickHouse(String path, ClickHouseMigrationRunner.Result result) {
    for (ClickHouseMigrationRunner.Applied a : result.applied()) {
      row(
          path,
          "ClickHouse",
          a.version() + " " + a.description(),
          a.durationMs(),
          a.statements() + " statements");
    }
  }

  /** Any other measured row. */
  void row(String path, String engine, String what, long millis, String note) {
    rows.add(
        String.format(
            Locale.ROOT,
            "| %s | %s | %s | %,d | %s |",
            path,
            engine,
            what.replace('|', '/'),
            millis,
            note));
  }

  /** A free-text line under the table. */
  void note(String text) {
    notes.add(text);
  }

  /** Replaces this section in timings.md. */
  void write() {
    Path dir = Path.of(System.getProperty("argos.migration.reportDir", "build/reports/migration"));
    Path file = dir.resolve("timings.md");
    StringBuilder sb = new StringBuilder();
    sb.append("<!-- section:").append(section).append(" -->\n");
    sb.append("## ").append(title).append("\n\n");
    sb.append("Run ")
        .append(Instant.now().truncatedTo(ChronoUnit.SECONDS))
        .append(", shared developer machine: reference values only (INVALID as a benchmark).\n\n");
    sb.append("| Path | Engine | Script / step | ms | Note |\n|---|---|---|---:|---|\n");
    rows.forEach(r -> sb.append(r).append('\n'));
    if (!notes.isEmpty()) {
      sb.append('\n');
      notes.forEach(n -> sb.append("- ").append(n).append('\n'));
    }
    sb.append("<!-- end:").append(section).append(" -->\n");
    try {
      Files.createDirectories(dir);
      String existing =
          Files.exists(file)
              ? Files.readString(file, StandardCharsets.UTF_8)
              : "# Migration timings (T-ST-052, T-SV-002)\n\n";
      String start = "<!-- section:" + section + " -->";
      String end = "<!-- end:" + section + " -->\n";
      int from = existing.indexOf(start);
      int to = existing.indexOf(end);
      String updated =
          from >= 0 && to > from
              ? existing.substring(0, from) + sb + existing.substring(to + end.length())
              : existing + sb + "\n";
      Files.writeString(file, updated, StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
