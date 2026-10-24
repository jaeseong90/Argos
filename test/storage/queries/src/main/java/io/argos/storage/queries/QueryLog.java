/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.storage.queries;

import io.argos.storage.clickhouse.ClickHouseException;
import io.argos.storage.clickhouse.ClickHouseHttp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Reads the server-side record of the runner's queries from {@code system.query_log} (argos_reader
 * has a SELECT grant on it, users.d/argos.xml), by the query ids the runner assigned. The log is
 * flushed every 7.5 s and the table only exists after the first flush, so collection polls until
 * every id is present or the wait ends; ids that never appear are left out.
 */
public final class QueryLog {

  private static final int CHUNK = 200;

  private QueryLog() {}

  /**
   * Final records (QueryFinish or an exception type) of the given query ids.
   *
   * @param reader argos_reader client
   * @param queryIds ids assigned by the runner ([A-Za-z0-9_-])
   * @param maxWait how long to wait for the log flush
   */
  public static Map<String, Entry> collect(
      ClickHouseHttp reader, Collection<String> queryIds, Duration maxWait) {
    Map<String, Entry> found = new LinkedHashMap<>();
    if (queryIds.isEmpty()) {
      return found;
    }
    List<String> ids = new ArrayList<>(queryIds);
    Instant deadline = Instant.now().plus(maxWait);
    while (true) {
      List<String> missing = ids.stream().filter(id -> !found.containsKey(id)).toList();
      for (int i = 0; i < missing.size(); i += CHUNK) {
        List<String> chunk = missing.subList(i, Math.min(missing.size(), i + CHUNK));
        try {
          for (List<String> row : query(reader, chunk)) {
            Entry e = Entry.parse(row);
            found.put(e.queryId(), e);
          }
        } catch (ClickHouseException e) {
          if (!"UNKNOWN_TABLE".equals(e.errorName())) {
            throw e;
          }
        }
      }
      if (found.size() == ids.size() || Instant.now().isAfter(deadline)) {
        return found;
      }
      try {
        Thread.sleep(1000);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        return found;
      }
    }
  }

  private static List<List<String>> query(ClickHouseHttp reader, List<String> ids) {
    StringBuilder array = new StringBuilder("[");
    for (String id : ids) {
      if (!id.matches("[A-Za-z0-9_-]+")) {
        throw new IllegalArgumentException("Unexpected query id " + id);
      }
      array.append(array.length() > 1 ? "," : "").append('\'').append(id).append('\'');
    }
    array.append(']');
    String sql =
        "SELECT query_id, user, toString(type), query_duration_ms, read_rows, read_bytes,"
            + " result_rows, memory_usage, ProfileEvents['SelectedParts'],"
            + " ProfileEvents['SelectedMarks'], ProfileEvents['SelectedRows'],"
            + " ProfileEvents['OSReadBytes'], ProfileEvents['UserTimeMicroseconds'],"
            + " ProfileEvents['SystemTimeMicroseconds'], exception_code,"
            + " Settings['max_execution_time'], written_rows,"
            + " ProfileEvents['ReadCompressedBytes'], ProfileEvents['SelectedMarksTotal'],"
            + " ProfileEvents['FilteringMarksWithSecondaryKeysMicroseconds']"
            + " FROM system.query_log"
            + " WHERE event_date >= yesterday() AND type != 'QueryStart'"
            + " AND query_id IN {ids:Array(String)}";
    return Tsv.rows(reader.execute(sql, Map.of("param_ids", array.toString())).body());
  }

  /**
   * One query_log record.
   *
   * @param queryId query id
   * @param user account that ran it
   * @param type QueryFinish, ExceptionBeforeStart or ExceptionWhileProcessing
   * @param durationMs server duration
   * @param readRows rows read
   * @param readBytes bytes read (uncompressed)
   * @param resultRows result rows
   * @param memoryUsage peak memory of the query
   * @param selectedParts parts selected after the partition and primary key analysis
   * @param selectedMarks marks (granules) selected after primary key and skip indexes
   * @param selectedRows rows of the selected marks
   * @param osReadBytes bytes read from the disk (not from the page cache)
   * @param userTimeUs user CPU time
   * @param systemTimeUs system CPU time
   * @param exceptionCode 0, or the ClickHouse error code
   * @param maxExecutionTime the max_execution_time the query ran with ("" when default)
   * @param writtenRows rows written (inserts)
   * @param readCompressedBytes compressed bytes read from the parts (columns, marks and skip index
   *     files; page cache or disk), the cost a bloom-only lookup pays for its index
   * @param selectedMarksTotal marks of the selected parts before the primary key and skip index
   *     analysis (with {@link #selectedMarks}: what the indexes skipped)
   * @param skipIndexUs time spent filtering marks with the skip indexes (microseconds)
   */
  public record Entry(
      String queryId,
      String user,
      String type,
      long durationMs,
      long readRows,
      long readBytes,
      long resultRows,
      long memoryUsage,
      long selectedParts,
      long selectedMarks,
      long selectedRows,
      long osReadBytes,
      long userTimeUs,
      long systemTimeUs,
      int exceptionCode,
      String maxExecutionTime,
      long writtenRows,
      long readCompressedBytes,
      long selectedMarksTotal,
      long skipIndexUs) {

    static Entry parse(List<String> r) {
      return new Entry(
          r.get(0),
          r.get(1),
          r.get(2),
          Long.parseLong(r.get(3)),
          Long.parseLong(r.get(4)),
          Long.parseLong(r.get(5)),
          Long.parseLong(r.get(6)),
          Long.parseLong(r.get(7)),
          Long.parseLong(r.get(8)),
          Long.parseLong(r.get(9)),
          Long.parseLong(r.get(10)),
          Long.parseLong(r.get(11)),
          Long.parseLong(r.get(12)),
          Long.parseLong(r.get(13)),
          Integer.parseInt(r.get(14)),
          r.get(15),
          Long.parseLong(r.get(16)),
          Long.parseLong(r.get(17)),
          Long.parseLong(r.get(18)),
          Long.parseLong(r.get(19)));
    }

    /** CPU time (user + system) in ms. */
    public double cpuMs() {
      return (userTimeUs + systemTimeUs) / 1000.0;
    }
  }
}
