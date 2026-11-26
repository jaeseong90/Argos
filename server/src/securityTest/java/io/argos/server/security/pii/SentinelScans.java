/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.security.pii;

import io.argos.sample.common.seed.Seeds;
import io.argos.storage.clickhouse.ClickHouseHttp;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.zip.GZIPInputStream;

/**
 * Sentinel scans of the PII seed grep (T-NFR-10c, 18 §12 last paragraph, DSN-18-09): every place a
 * seed value could have been stored or written is searched for {@code ARGOS_SEED}, for the
 * access-key seed that has no underscore {@code ARGOSSEED} (ADR-0004 C-44), and for the real-shaped
 * phone, resident number and card sentinels that the exception-message seeds carry (ADR-0010 C-250
 * (1)). A scan returns counts per location (ClickHouse {@code database.table.column}, PostgreSQL
 * table, file) and never other matching values: a failure names where and how often.
 */
final class SentinelScans {

  /**
   * The leak sentinels of 18 §4.3 and C-250 (1), from the one list the seed sender uses ({@code
   * Seeds.LEAK_SENTINELS} of sample-apps/common): {@code ARGOS_SEED}, {@code ARGOSSEED}, then the
   * real-shaped phone, resident number and card values.
   */
  static final List<String> SENTINELS = List.copyOf(Seeds.LEAK_SENTINELS);

  /** Engines whose columns hold no data of their own (the target tables are scanned instead). */
  static final Set<String> VIEW_ENGINES =
      Set.of("View", "MaterializedView", "LiveView", "WindowView", "Dictionary");

  private SentinelScans() {}

  /** Counts per location: {@code counts[i]} belongs to {@code SENTINELS.get(i)}. */
  record Counts(Map<String, long[]> byLocation) {

    static Counts empty() {
      return new Counts(new TreeMap<>());
    }

    void add(String location, long[] counts) {
      long[] current = byLocation.computeIfAbsent(location, k -> new long[SENTINELS.size()]);
      for (int i = 0; i < counts.length; i++) {
        current[i] += counts[i];
      }
    }

    /** The locations with at least one hit, as {@code location ARGOS_SEED=n ARGOSSEED=m ...}. */
    List<String> hits() {
      List<String> out = new ArrayList<>();
      byLocation.forEach(
          (location, counts) -> {
            if (total(counts) > 0) {
              out.add(location + " " + format(counts));
            }
          });
      return out;
    }

    long total() {
      long sum = 0;
      for (long[] counts : byLocation.values()) {
        sum += total(counts);
      }
      return sum;
    }

    int locations() {
      return byLocation.size();
    }

    /** The counts of one location (zeros when it was not scanned). */
    long[] of(String location) {
      return byLocation.getOrDefault(location, new long[SENTINELS.size()]);
    }

    static long total(long[] counts) {
      long sum = 0;
      for (long c : counts) {
        sum += c;
      }
      return sum;
    }
  }

  /** {@code ARGOS_SEED=n ARGOSSEED=m 010-9999-0001=k ...}, one entry per sentinel. */
  static String format(long[] counts) {
    StringBuilder out = new StringBuilder();
    for (int i = 0; i < SENTINELS.size(); i++) {
      if (i > 0) {
        out.append(' ');
      }
      out.append(SENTINELS.get(i)).append('=').append(counts[i]);
    }
    return out.toString();
  }

  /** Occurrences of each sentinel in {@code text}. */
  static long[] occurrences(String text) {
    long[] counts = new long[SENTINELS.size()];
    for (int i = 0; i < SENTINELS.size(); i++) {
      String sentinel = SENTINELS.get(i);
      int from = 0;
      while (true) {
        int at = text.indexOf(sentinel, from);
        if (at < 0) {
          break;
        }
        counts[i]++;
        from = at + sentinel.length();
      }
    }
    return counts;
  }

  /** A ClickHouse identifier in backquotes (inner tables of materialized views carry dots). */
  static String quote(String identifier) {
    return "`" + identifier.replace("\\", "\\\\").replace("`", "\\`") + "`";
  }

  /** One string column of a ClickHouse table. */
  record Column(String database, String table, String name, String type) {
    String location() {
      return database + "." + table + "." + name;
    }
  }

  /**
   * Every column of the {@code argos*} databases whose type mentions {@code String} ({@code
   * String}, {@code FixedString}, {@code LowCardinality}, {@code Nullable}, {@code Array}, {@code
   * Map}, ...), in tables that hold data (not views, 18 §12 (a)).
   */
  static List<Column> stringColumns(ClickHouseHttp clickHouse) {
    List<Column> columns = new ArrayList<>();
    String sql =
        "SELECT c.database, c.table, c.name, c.type FROM system.columns AS c"
            + " INNER JOIN system.tables AS t ON c.database = t.database AND c.table = t.name"
            + " WHERE c.database LIKE 'argos%' AND c.type LIKE '%String%'"
            + " AND t.engine NOT IN ("
            + String.join(", ", VIEW_ENGINES.stream().sorted().map(e -> "'" + e + "'").toList())
            + ") ORDER BY c.database, c.table, c.position";
    for (List<String> row : clickHouse.rows(sql)) {
      if (row.get(3).startsWith("AggregateFunction(")) {
        // a binary aggregate state, never text (the schema has none over String)
        continue;
      }
      columns.add(new Column(row.get(0), row.get(1), row.get(2), row.get(3)));
    }
    return columns;
  }

  /**
   * {@code SELECT countIf(position(toString(col), sentinel) > 0) ...} for every column of {@code
   * columns}, one query per table: rows per column and sentinel.
   */
  static Counts clickHouse(ClickHouseHttp clickHouse, List<Column> columns) {
    Map<String, List<Column>> byTable = new LinkedHashMap<>();
    for (Column c : columns) {
      byTable
          .computeIfAbsent(quote(c.database()) + "." + quote(c.table()), k -> new ArrayList<>())
          .add(c);
    }
    Counts counts = Counts.empty();
    byTable.forEach(
        (table, cols) -> {
          List<String> expressions = new ArrayList<>();
          for (Column c : cols) {
            for (String sentinel : SENTINELS) {
              expressions.add(
                  "countIf(position(toString(" + quote(c.name()) + "), '" + sentinel + "') > 0)");
            }
          }
          List<String> row =
              clickHouse.rows("SELECT " + String.join(", ", expressions) + " FROM " + table).get(0);
          int i = 0;
          for (Column c : cols) {
            long[] values = new long[SENTINELS.size()];
            for (int s = 0; s < SENTINELS.size(); s++) {
              values[s] = Long.parseLong(row.get(i++).strip());
            }
            counts.add(c.location(), values);
          }
        });
    return counts;
  }

  private static final Pattern COPY_HEADER =
      Pattern.compile("^COPY (\\S+) \\((.*)\\) FROM stdin;$");

  /**
   * Rows of a plain {@code pg_dump --data-only} output that carry a sentinel, by {@code
   * table.column}: the rows of {@code COPY <table> (<columns>) FROM stdin;} up to {@code \.}, split
   * at the tab between columns (a tab inside a value is written {@code \t}). Every column of the
   * dump is a location, also with zero hits; text outside a COPY block counts as {@code (outside
   * COPY)}.
   */
  static Counts pgDump(String dump) {
    Counts counts = Counts.empty();
    counts.add("(outside COPY)", new long[SENTINELS.size()]);
    String table = null;
    List<String> columns = List.of();
    for (String line : dump.split("\n", -1)) {
      if (table == null) {
        Matcher header = COPY_HEADER.matcher(line);
        if (header.matches()) {
          table = header.group(1);
          columns = new ArrayList<>();
          for (String column : header.group(2).split(",", -1)) {
            columns.add(column.strip().replace("\"", ""));
          }
          for (String column : columns) {
            counts.add(table + "." + column, new long[SENTINELS.size()]);
          }
          continue;
        }
        counts.add("(outside COPY)", lineHits(line));
        continue;
      }
      if (line.equals("\\.")) {
        table = null;
        continue;
      }
      String[] values = line.split("\t", -1);
      for (int i = 0; i < values.length; i++) {
        String column = i < columns.size() ? columns.get(i) : "(column " + (i + 1) + ")";
        counts.add(table + "." + column, lineHits(values[i]));
      }
    }
    return counts;
  }

  /** The tables of {@code table.column} locations (the PostgreSQL dump, the ClickHouse scan). */
  static long tables(Counts counts) {
    return counts.byLocation().keySet().stream()
        .filter(l -> !l.startsWith("("))
        .map(l -> l.substring(0, l.lastIndexOf('.')))
        .distinct()
        .count();
  }

  /** 1 per sentinel the line contains. */
  static long[] lineHits(String line) {
    long[] found = new long[SENTINELS.size()];
    for (int i = 0; i < SENTINELS.size(); i++) {
      found[i] = line.contains(SENTINELS.get(i)) ? 1 : 0;
    }
    return found;
  }

  /**
   * Lines with a sentinel in every regular file below {@code root} (gzip-compressed rotations
   * decompressed), by path relative to {@code root} and prefixed with {@code label}. Bytes are read
   * as ISO-8859-1: the sentinels are ASCII, so no encoding can hide them.
   */
  static Counts files(String label, Path root) {
    Counts counts = Counts.empty();
    if (root == null || !Files.exists(root)) {
      return counts;
    }
    List<Path> files;
    try (Stream<Path> walk = Files.walk(root)) {
      files = walk.filter(Files::isRegularFile).sorted().toList();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    for (Path file : files) {
      String relative = root.relativize(file).toString().replace('\\', '/');
      counts.add(label + "/" + relative, fileHits(file));
    }
    return counts;
  }

  /** Lines with each sentinel in one file. */
  static long[] fileHits(Path file) {
    long[] total = new long[SENTINELS.size()];
    for (String line : lines(file)) {
      long[] found = lineHits(line);
      for (int i = 0; i < total.length; i++) {
        total[i] += found[i];
      }
    }
    return total;
  }

  /** The lines of a file, gzip-decompressed when its name ends in {@code .gz}. */
  static List<String> lines(Path file) {
    try {
      byte[] bytes;
      if (file.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".gz")) {
        try (InputStream in = new GZIPInputStream(Files.newInputStream(file))) {
          bytes = in.readAllBytes();
        }
      } else {
        bytes = Files.readAllBytes(file);
      }
      return List.of(new String(bytes, StandardCharsets.ISO_8859_1).split("\n", -1));
    } catch (IOException e) {
      throw new UncheckedIOException("Cannot read " + file, e);
    }
  }
}
