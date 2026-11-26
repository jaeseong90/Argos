/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.test.soak;

import io.argos.test.soak.SeriesMath.Point;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.ToLongFunction;

/**
 * The series of one soak run (18 §11.2), kept in memory (at most a few hundred points each: 72
 * hourly and 432 ten-minute points in 72 hours) and appended to {@code samples.tsv} as they come,
 * so that an aborted run keeps what it measured. TSV columns: {@code ts} (UTC), {@code elapsed_s}
 * (seconds since the measured window started), {@code series}, {@code name}, {@code value}. A value
 * that could not be read is {@code -1} in memory and not written.
 */
public final class SoakSamples {

  /** Header line of {@code samples.tsv}. */
  public static final String TSV_HEADER = "ts\telapsed_s\tseries\tname\tvalue";

  /**
   * One hourly agent sample of shop-boot3 (-1: not read).
   *
   * @param seconds since the measured window started
   * @param heapBytes {@code io.argos.} classes of the class histogram after {@code GC.run} twice
   * @param nmtInternalKb NMT committed {@code Internal}
   * @param nmtOtherKb NMT committed {@code Other}
   * @param nmtThreadKb NMT committed {@code Thread}
   * @param jvmThreads NMT thread count
   * @param agentThreads threads named {@code argos-*} in {@code Thread.print}
   * @param liveHeapBytes every class of the same histogram (the app's live heap, a reference:
   *     arrays the agent owns count under {@code [B}, {@code [J}, not under {@code io.argos.})
   * @param bufferBytes the agent's own estimate of its buffers ({@code
   *     argos_agent_heap_bytes_estimated}: queues, dictionary texts and service names, 10 §11,
   *     §14), the newest value the agent sent before the sample (ADR-0010 C-259 (5): the bytes of
   *     arrays that the {@code io.argos.} classes of the histogram do not count)
   */
  public record AgentSample(
      double seconds,
      long heapBytes,
      long nmtInternalKb,
      long nmtOtherKb,
      long nmtThreadKb,
      long jvmThreads,
      long agentThreads,
      long liveHeapBytes,
      long bufferBytes) {

    /** A sample without the agent's buffer estimate. */
    public AgentSample(
        double seconds,
        long heapBytes,
        long nmtInternalKb,
        long nmtOtherKb,
        long nmtThreadKb,
        long jvmThreads,
        long agentThreads,
        long liveHeapBytes) {
      this(
          seconds,
          heapBytes,
          nmtInternalKb,
          nmtOtherKb,
          nmtThreadKb,
          jvmThreads,
          agentThreads,
          liveHeapBytes,
          -1);
    }

    /** A sample without the live heap reference. */
    public AgentSample(
        double seconds,
        long heapBytes,
        long nmtInternalKb,
        long nmtOtherKb,
        long nmtThreadKb,
        long jvmThreads,
        long agentThreads) {
      this(
          seconds,
          heapBytes,
          nmtInternalKb,
          nmtOtherKb,
          nmtThreadKb,
          jvmThreads,
          agentThreads,
          -1,
          -1);
    }

    /**
     * The agent's footprint of ADR-0010 C-259 (5): the {@code io.argos.} classes plus the agent's
     * buffer estimate, -1 when either was not read.
     */
    public long agentBytes() {
      return heapBytes < 0 || bufferBytes < 0 ? -1 : heapBytes + bufferBytes;
    }

    /** {@code Internal + Other + Thread} committed KB (18 §11.2), -1 when not read. */
    public long nmtKb() {
      return nmtInternalKb < 0 || nmtOtherKb < 0 || nmtThreadKb < 0
          ? -1
          : nmtInternalKb + nmtOtherKb + nmtThreadKb;
    }
  }

  /**
   * One 10-minute sample of the stack (-1: not read).
   *
   * @param seconds since the measured window started
   * @param appFds open file descriptors of shop-boot3 (Linux) or its handle count (Windows,
   *     reference)
   * @param serverRssBytes server resident set
   * @param serverHeapUsed server {@code jvm_memory_used_bytes{area="heap"}}
   * @param serverNonHeapUsed server {@code jvm_memory_used_bytes{area="nonheap"}}
   * @param serverDirectBytes server direct buffers
   * @param walBytes {@code argos_wal_bytes} (else the WAL directory size)
   * @param walBacklogBytes the unloaded backlog of the WAL: bytes from the oldest data record that
   *     is not yet in ClickHouse to the write position ({@link WalBacklog}, ADR-0010 C-259 (4))
   * @param maxPartsPerPartition largest active part count of one partition
   * @param busiestPartition table and partition of that count
   * @param merges running merges ({@code system.merges})
   * @param diskBytes {@code bytes_on_disk} of the active {@code argos} parts
   * @param rowsByTable active rows per table
   * @param bytesByTable active bytes on disk per table
   */
  public record StackSample(
      double seconds,
      long appFds,
      long serverRssBytes,
      long serverHeapUsed,
      long serverNonHeapUsed,
      long serverDirectBytes,
      long walBytes,
      long maxPartsPerPartition,
      String busiestPartition,
      long merges,
      long diskBytes,
      Map<String, Long> rowsByTable,
      Map<String, Long> bytesByTable,
      long walBacklogBytes) {

    /** Copies the maps. */
    public StackSample {
      rowsByTable = Map.copyOf(rowsByTable);
      bytesByTable = Map.copyOf(bytesByTable);
    }

    /** A sample without the WAL backlog. */
    public StackSample(
        double seconds,
        long appFds,
        long serverRssBytes,
        long serverHeapUsed,
        long serverNonHeapUsed,
        long serverDirectBytes,
        long walBytes,
        long maxPartsPerPartition,
        String busiestPartition,
        long merges,
        long diskBytes,
        Map<String, Long> rowsByTable,
        Map<String, Long> bytesByTable) {
      this(
          seconds,
          appFds,
          serverRssBytes,
          serverHeapUsed,
          serverNonHeapUsed,
          serverDirectBytes,
          walBytes,
          maxPartsPerPartition,
          busiestPartition,
          merges,
          diskBytes,
          rowsByTable,
          bytesByTable,
          -1);
    }
  }

  /**
   * One load segment (an "hourly" p99 point): a {@code load.jar} run of one interval.
   *
   * @param index 0-based; the warm-up segment is not one
   * @param seconds start since the measured window started
   * @param p99Millis latency p99 of the segment
   * @param sent requests sent
   * @param unexpectedRate share without the expected outcome
   * @param http5xxRate share of 5xx answers and failures (the mix has 5% intended 500s)
   * @param exitCode the generator's exit code
   */
  public record Segment(
      int index,
      double seconds,
      double p99Millis,
      long sent,
      double unexpectedRate,
      double http5xxRate,
      int exitCode) {}

  private final List<AgentSample> agent = new ArrayList<>();
  private final List<StackSample> stack = new ArrayList<>();
  private final List<Segment> segments = new ArrayList<>();
  private final Path tsv;
  private Instant windowStart;

  /** Samples written to {@code tsv} (null: memory only). */
  public SoakSamples(Path tsv) {
    this.tsv = tsv;
    if (tsv != null) {
      try {
        Files.writeString(tsv, TSV_HEADER + "\n", StandardCharsets.UTF_8);
      } catch (IOException e) {
        throw new UncheckedIOException(e);
      }
    }
  }

  /** Marks the start of the measured window (elapsed seconds count from here). */
  public synchronized void windowStart(Instant start) {
    this.windowStart = start;
  }

  /** Seconds since the measured window started (negative before). */
  public synchronized double elapsed(Instant now) {
    return windowStart == null ? 0 : (now.toEpochMilli() - windowStart.toEpochMilli()) / 1000.0;
  }

  /** Adds an hourly agent sample. */
  public synchronized void add(AgentSample s) {
    agent.add(s);
    Map<String, Long> v = new LinkedHashMap<>();
    v.put("agent_heap_bytes", s.heapBytes());
    v.put("nmt_internal_kb", s.nmtInternalKb());
    v.put("nmt_other_kb", s.nmtOtherKb());
    v.put("nmt_thread_kb", s.nmtThreadKb());
    v.put("nmt_internal_other_thread_kb", s.nmtKb());
    v.put("jvm_threads", s.jvmThreads());
    v.put("agent_threads", s.agentThreads());
    v.put("app_live_heap_bytes", s.liveHeapBytes());
    v.put("agent_buffer_bytes", s.bufferBytes());
    write("agent", s.seconds(), v);
  }

  /** Adds a 10-minute stack sample. */
  public synchronized void add(StackSample s) {
    stack.add(s);
    Map<String, Long> v = new LinkedHashMap<>();
    v.put("app_fds", s.appFds());
    v.put("server_rss_bytes", s.serverRssBytes());
    v.put("server_heap_used_bytes", s.serverHeapUsed());
    v.put("server_nonheap_used_bytes", s.serverNonHeapUsed());
    v.put("server_direct_bytes", s.serverDirectBytes());
    v.put("wal_bytes", s.walBytes());
    v.put("wal_backlog_bytes", s.walBacklogBytes());
    v.put("ch_max_parts_per_partition", s.maxPartsPerPartition());
    v.put("ch_merges", s.merges());
    v.put("ch_disk_bytes", s.diskBytes());
    write("stack", s.seconds(), v);
  }

  /** Adds a load segment. */
  public synchronized void add(Segment s) {
    segments.add(s);
    Map<String, Long> v = new LinkedHashMap<>();
    v.put("p99_us", Double.isNaN(s.p99Millis()) ? -1 : Math.round(s.p99Millis() * 1000));
    v.put("sent", s.sent());
    v.put("exit_code", (long) s.exitCode());
    write("load", s.seconds(), v);
  }

  public synchronized List<AgentSample> agent() {
    return List.copyOf(agent);
  }

  public synchronized List<StackSample> stack() {
    return List.copyOf(stack);
  }

  public synchronized List<Segment> segments() {
    return List.copyOf(segments);
  }

  /** The points of an agent field, without the samples that could not read it. */
  public synchronized List<Point> agentSeries(ToLongFunction<AgentSample> field) {
    List<Point> p = new ArrayList<>();
    for (AgentSample s : agent) {
      long v = field.applyAsLong(s);
      if (v >= 0) {
        p.add(new Point(s.seconds(), v));
      }
    }
    return p;
  }

  /** The points of a stack field, without the samples that could not read it. */
  public synchronized List<Point> stackSeries(ToLongFunction<StackSample> field) {
    List<Point> p = new ArrayList<>();
    for (StackSample s : stack) {
      long v = field.applyAsLong(s);
      if (v >= 0) {
        p.add(new Point(s.seconds(), v));
      }
    }
    return p;
  }

  /** TSV lines of one sample (values below 0 are not written). */
  static List<String> tsvLines(Instant ts, double seconds, String series, Map<String, Long> v) {
    List<String> lines = new ArrayList<>();
    String t = ts.truncatedTo(ChronoUnit.SECONDS).toString();
    for (Map.Entry<String, Long> e : v.entrySet()) {
      if (e.getValue() >= 0) {
        lines.add(
            String.format(
                Locale.ROOT, "%s\t%.1f\t%s\t%s\t%d", t, seconds, series, e.getKey(), e.getValue()));
      }
    }
    return lines;
  }

  private void write(String series, double seconds, Map<String, Long> values) {
    if (tsv == null) {
      return;
    }
    try (BufferedWriter w =
        Files.newBufferedWriter(tsv, StandardCharsets.UTF_8, StandardOpenOption.APPEND)) {
      for (String line : tsvLines(Instant.now(), seconds, series, values)) {
        w.write(line);
        w.write('\n');
      }
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
