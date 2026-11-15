/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.testing.querydata;

import io.argos.server.testing.ServerTestEnvironment;
import io.argos.server.testing.fakeagent.TxIds;
import io.argos.storage.clickhouse.Account;
import io.argos.storage.clickhouse.ClickHouseHttp;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Rows written by a test itself, next to the generated data: raw table rows inserted as the batch
 * writer ({@code argos_writer}, INSERT on the five raw tables only; the rollup views fill the
 * rollups as for real data). Times come from {@link Anchors} (relative to now).
 *
 * <pre>{@code
 * HandMadeRows rows = HandMadeRows.of(env);
 * rows.xlog(List.of(
 *     XlogRow.at(900, 9001, Anchors.minutesAgo(3)).service(17).elapsedMs(250),
 *     XlogRow.at(900, 9001, Anchors.minutesAgo(1)).error(true).httpStatus(500)));
 * rows.steps(List.of(StepRow.of(tx, 1, "SQL").elapsedMs(40).nameId(sqlId)));
 * }</pre>
 *
 * Rows go in as {@code TabSeparated} with explicit columns (the other columns take their defaults,
 * including the V002 columns) and a unique {@code insert_deduplication_token}, so two identical
 * hand-made inserts both count.
 */
public final class HandMadeRows {

  private static final DateTimeFormatter TIME =
      DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS", Locale.ROOT).withZone(ZoneOffset.UTC);

  private final ClickHouseHttp writer;
  private final String database;
  private final String prefix;

  private HandMadeRows(ClickHouseHttp writer, String database, String prefix) {
    this.writer = writer;
    this.database = database;
    this.prefix = prefix;
  }

  /** Rows for the database {@code argos} of a test environment, as {@code argos_writer}. */
  public static HandMadeRows of(ServerTestEnvironment env) {
    return new HandMadeRows(env.clickHouse(Account.WRITER), ServerTestEnvironment.DATABASE, "");
  }

  /** Rows for any installation (the dev command): a writer client, database and prefix. */
  public static HandMadeRows of(ClickHouseHttp writer, String database, String prefix) {
    return new HandMadeRows(writer, database, prefix);
  }

  /** Inserts xlog rows. */
  public void xlog(List<XlogRow> rows) {
    insert("xlog", rows.stream().map(XlogRow::columns).toList());
  }

  /** Inserts profile_step rows. */
  public void steps(List<StepRow> rows) {
    insert("profile_step", rows.stream().map(StepRow::columns).toList());
  }

  /**
   * Inserts rows of a raw table ({@code xlog}, {@code profile_step}, {@code metric}, {@code
   * sql_stat_raw}, {@code ext_stat_raw}), column name to value; rows with different column sets go
   * in separate inserts. Values: {@code String}, {@code Number}, {@code Boolean} (0/1), {@code
   * Instant} (DateTime64(3)), {@code byte[]} (FixedString), {@code List} (Array), {@code Map}
   * (Map).
   */
  public void insert(String table, List<Map<String, Object>> rows) {
    Map<List<String>, List<Map<String, Object>>> byColumns = new LinkedHashMap<>();
    for (Map<String, Object> row : rows) {
      byColumns.computeIfAbsent(List.copyOf(row.keySet()), k -> new ArrayList<>()).add(row);
    }
    for (Map.Entry<List<String>, List<Map<String, Object>>> group : byColumns.entrySet()) {
      StringBuilder data = new StringBuilder();
      for (Map<String, Object> row : group.getValue()) {
        boolean first = true;
        for (String column : group.getKey()) {
          if (!first) {
            data.append('\t');
          }
          first = false;
          tsv(data, row.get(column));
        }
        data.append('\n');
      }
      String statement =
          "INSERT INTO "
              + database
              + "."
              + prefix
              + table
              + " ("
              + String.join(", ", group.getKey())
              + ") FORMAT TabSeparated";
      writer.insert(
          statement,
          data.toString().getBytes(StandardCharsets.UTF_8),
          Map.of("insert_deduplication_token", "hand-made-" + UUID.randomUUID()));
    }
  }

  private static void tsv(StringBuilder out, Object value) {
    switch (value) {
      case null -> out.append("\\N");
      case String s -> escaped(out, s);
      case Boolean b -> out.append(b ? '1' : '0');
      case Instant t -> out.append(TIME.format(t));
      case byte[] bytes -> {
        for (byte b : bytes) {
          out.append("\\x").append(HexFormat.of().toHexDigits(b));
        }
      }
      case List<?> list -> {
        out.append('[');
        for (int i = 0; i < list.size(); i++) {
          if (i > 0) {
            out.append(',');
          }
          literal(out, list.get(i));
        }
        out.append(']');
      }
      case Map<?, ?> map -> {
        out.append('{');
        boolean first = true;
        for (Map.Entry<?, ?> e : map.entrySet()) {
          if (!first) {
            out.append(',');
          }
          first = false;
          literal(out, String.valueOf(e.getKey()));
          out.append(':');
          literal(out, e.getValue());
        }
        out.append('}');
      }
      default -> out.append(value);
    }
  }

  /** An element of an array or map: numbers plain, strings quoted (inside the TSV field). */
  private static void literal(StringBuilder out, Object value) {
    if (value instanceof Number n) {
      out.append(n);
      return;
    }
    StringBuilder quoted = new StringBuilder("'");
    for (char c : String.valueOf(value).toCharArray()) {
      if (c == '\'' || c == '\\') {
        quoted.append('\\');
      }
      quoted.append(c);
    }
    quoted.append('\'');
    escaped(out, quoted.toString());
  }

  /** The TabSeparated escaping of a field. */
  private static void escaped(StringBuilder out, String s) {
    for (int i = 0; i < s.length(); i++) {
      char c = s.charAt(i);
      switch (c) {
        case '\\' -> out.append("\\\\");
        case '\t' -> out.append("\\t");
        case '\n' -> out.append("\\n");
        case '\r' -> out.append("\\r");
        case '\0' -> out.append("\\0");
        case '\b' -> out.append("\\b");
        case '\f' -> out.append("\\f");
        default -> out.append(c);
      }
    }
  }

  /** An xlog row with neutral defaults; set what the test checks. */
  public static final class XlogRow {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final AtomicInteger SEQ = new AtomicInteger();

    private final Map<String, Object> c = new LinkedHashMap<>();

    private XlogRow(int appId, int instanceId, Instant ts) {
      Objects.requireNonNull(ts, "ts");
      byte[] trace = new byte[16];
      RANDOM.nextBytes(trace);
      c.put("app_id", appId);
      c.put("env", "prod");
      c.put("instance_id", instanceId);
      c.put("ts", ts);
      c.put(
          "tx_id",
          Long.toUnsignedString(TxIds.of(ts.toEpochMilli(), 0xABC, SEQ.incrementAndGet())));
      c.put("kind", "HTTP");
      c.put("trace_id", trace);
      c.put("span_id", Long.toUnsignedString(RANDOM.nextLong()));
      c.put("parent_span_id", 0);
      c.put("service_id", 1);
      c.put("http_method", "GET");
      c.put("http_status", 200);
      c.put("elapsed_ms", 100);
      c.put("error", false);
      c.put("apdex_t_ms", 500);
      c.put("remote_ip", "::");
      c.put("ip_mode", "off");
      c.put("user_id_mode", "off");
      c.put("cpu_time_ms", -1);
      c.put("alloc_bytes", -1);
    }

    /**
     * A row of {@code appId}/{@code instanceId} starting at {@code ts}; {@code tx_id} is derived
     * from {@code ts} like the agent's ({@code tx_id >> 23} = ms since 2026-01-01).
     */
    public static XlogRow at(int appId, int instanceId, Instant ts) {
      return new XlogRow(appId, instanceId, ts);
    }

    /** Any column. */
    public XlogRow set(String column, Object value) {
      c.put(column, value);
      return this;
    }

    /** {@code tx_id} (unsigned 64 bits). */
    public XlogRow txId(long bits) {
      return set("tx_id", Long.toUnsignedString(bits));
    }

    /** The {@code tx_id} of this row. */
    public long txId() {
      return Long.parseUnsignedLong(String.valueOf(c.get("tx_id")));
    }

    /** {@code trace_id} as 32 hex digits. */
    public XlogRow traceIdHex(String hex) {
      return set("trace_id", HexFormat.of().parseHex(hex));
    }

    /** {@code service_id}. */
    public XlogRow service(long id) {
      return set("service_id", Long.toUnsignedString(id));
    }

    /** {@code elapsed_ms}. */
    public XlogRow elapsedMs(int ms) {
      return set("elapsed_ms", ms);
    }

    /** {@code error}. */
    public XlogRow error(boolean value) {
      return set("error", value);
    }

    /** {@code http_status}. */
    public XlogRow httpStatus(int status) {
      return set("http_status", status);
    }

    /** {@code kind} ({@code HTTP}, {@code BATCH}, {@code MESSAGE}). */
    public XlogRow kind(String value) {
      return set("kind", value);
    }

    /** {@code user_id} with its mode ({@code plain} or {@code hashed}). */
    public XlogRow userId(String value, String mode) {
      set("user_id", value);
      return set("user_id_mode", mode);
    }

    /** {@code request_id}. */
    public XlogRow requestId(String value) {
      return set("request_id", value);
    }

    /** {@code profile_captured}. */
    public XlogRow profileCaptured(boolean value) {
      return set("profile_captured", value);
    }

    /** The columns of this row. */
    public Map<String, Object> columns() {
      return new LinkedHashMap<>(c);
    }
  }

  /** A profile_step row of a transaction. */
  public static final class StepRow {

    private final Map<String, Object> c = new LinkedHashMap<>();

    private StepRow(XlogRow tx, int seq, String type) {
      Map<String, Object> x = tx.columns();
      c.put("app_id", x.get("app_id"));
      c.put("env", x.get("env"));
      c.put("instance_id", x.get("instance_id"));
      c.put("tx_id", x.get("tx_id"));
      c.put("ts", x.get("ts"));
      c.put("kind", x.get("kind"));
      c.put("seq", seq);
      c.put("parent_seq", 0);
      c.put("type", type);
      c.put("start_offset_ms", 0);
      c.put("elapsed_ms", 0);
      c.put("repeat_count", 1);
    }

    /** Step {@code seq} of type {@code type} ({@code SQL}, {@code DBC}, ..., C-1) of {@code tx}. */
    public static StepRow of(XlogRow tx, int seq, String type) {
      return new StepRow(tx, seq, type);
    }

    /** Any column. */
    public StepRow set(String column, Object value) {
      c.put(column, value);
      return this;
    }

    /** {@code elapsed_ms}. */
    public StepRow elapsedMs(int ms) {
      return set("elapsed_ms", ms);
    }

    /** {@code name_id}. */
    public StepRow nameId(long id) {
      return set("name_id", Long.toUnsignedString(id));
    }

    /** The columns of this row. */
    public Map<String, Object> columns() {
      return new LinkedHashMap<>(c);
    }
  }
}
