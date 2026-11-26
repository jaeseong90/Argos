/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.test.soak;

import io.argos.agent.testing.jvm.ClassHistogram;
import io.argos.agent.testing.jvm.JcmdClient;
import io.argos.agent.testing.jvm.NmtSummary;
import io.argos.agent.testing.jvm.ThreadCounter;
import io.argos.server.testing.ServerTestEnvironment;
import io.argos.server.testing.load.ClickHouseProbe;
import io.argos.server.testing.load.LoadServerJvm;
import io.argos.server.testing.load.PrometheusText;
import io.argos.server.testing.load.ResidentSetProbe;
import io.argos.storage.clickhouse.Account;
import io.argos.storage.clickhouse.ClickHouseHttp;
import io.argos.test.soak.SoakSamples.AgentSample;
import io.argos.test.soak.SoakSamples.StackSample;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * The probes of the soak run (18 §11.2): the hourly agent sample of shop-boot3 through {@code jcmd}
 * of its own JDK and the 10-minute sample of the stack (app FDs, server metrics and resident set,
 * WAL, ClickHouse parts, merges and disk). A value that cannot be read is -1 with a note; a probe
 * never aborts the run.
 */
final class StackProbe {

  /** Prefix of the agent's classes (own and relocated) in the class histogram. */
  static final String AGENT_CLASSES = "io.argos.";

  /** Prefix of the agent's thread names ({@code ArgosThread}). */
  static final String AGENT_THREADS = "argos-";

  private final ServerTestEnvironment env;
  private final LoadServerJvm server;
  private final ResidentSetProbe rss;
  private final ClickHouseProbe clickHouse;
  private final ClickHouseHttp reader;
  private final FdProbe fds;
  private final SoakSamples samples;
  private final RunFacts facts;

  StackProbe(
      ServerTestEnvironment env,
      LoadServerJvm server,
      ResidentSetProbe rss,
      FdProbe fds,
      SoakSamples samples,
      RunFacts facts) {
    this.env = env;
    this.server = server;
    this.rss = rss;
    this.clickHouse = new ClickHouseProbe(env);
    // argos_reader has SELECT on system.parts and system.merges (deploy/clickhouse users.d).
    this.reader = env.clickHouse(Account.READER);
    this.fds = fds;
    this.samples = samples;
    this.facts = facts;
  }

  /**
   * Name of the agent's own estimate of its buffers in the {@code metric} table (queues, dictionary
   * texts, service names; agent-core {@code TraceRuntime.HEAP_BYTES_ESTIMATED}).
   */
  static final String AGENT_BUFFER_METRIC = "argos_agent_heap_bytes_estimated";

  /**
   * The hourly agent sample: {@code GC.run} twice, class histogram, NMT, thread dump, and the
   * agent's buffer estimate it sent last (ADR-0010 C-259 (5)).
   *
   * @param appId the app of the sampled JVM (its agent's metric rows)
   */
  void agent(JcmdClient jcmd, int appId) {
    double seconds = samples.elapsed(Instant.now());
    long buffers = -1;
    try {
      String v =
          reader
              .scalar(
                  "SELECT if(count() = 0, -1, toInt64(argMax(value, ts))) FROM "
                      + ServerTestEnvironment.DATABASE
                      + ".metric WHERE app_id = "
                      + appId
                      + " AND name = '"
                      + AGENT_BUFFER_METRIC
                      + "' AND ts > now() - INTERVAL 10 MINUTE")
              .strip();
      buffers = Long.parseLong(v);
    } catch (RuntimeException e) {
      facts.note("Agent buffer estimate sample failed: " + e.getMessage());
    }
    long heap = -1;
    long liveHeap = -1;
    long internal = -1;
    long other = -1;
    long thread = -1;
    long jvmThreads = -1;
    long agentThreads = -1;
    try {
      jcmd.gcRun();
      jcmd.gcRun();
      ClassHistogram histogram = jcmd.classHistogram();
      heap = histogram.bytesOf(AGENT_CLASSES);
      liveHeap = histogram.totalBytes();
    } catch (RuntimeException e) {
      facts.note("Agent heap sample failed: " + e.getMessage());
    }
    try {
      NmtSummary nmt = jcmd.nativeMemorySummary();
      internal = nmt.committedKb("Internal");
      other = nmt.committedKb("Other");
      thread = nmt.committedKb("Thread");
      jvmThreads = nmt.threads();
    } catch (RuntimeException e) {
      facts.note("NMT sample failed: " + e.getMessage());
    }
    try {
      List<String> names = ThreadCounter.namesStartingWith(jcmd.threadPrint(), AGENT_THREADS);
      agentThreads = names.size();
    } catch (RuntimeException e) {
      facts.note("Thread dump failed: " + e.getMessage());
    }
    samples.add(
        new AgentSample(
            seconds, heap, internal, other, thread, jvmThreads, agentThreads, liveHeap, buffers));
  }

  /** The 10-minute sample of the stack. */
  void stack() {
    double seconds = samples.elapsed(Instant.now());
    long appFds = fds.count();
    long rssBytes = rss.sampleNow().orElse(-1);
    long heap = -1;
    long nonHeap = -1;
    long direct = -1;
    long wal = -1;
    try {
      PrometheusText p = server.scraper().scrape();
      heap = (long) p.sum("jvm_memory_used_bytes", l -> "heap".equals(l.get("area")));
      nonHeap = (long) p.sum("jvm_memory_used_bytes", l -> "nonheap".equals(l.get("area")));
      direct = (long) p.sum("jvm_buffer_memory_used_bytes", l -> "direct".equals(l.get("id")));
      if (p.has("argos_wal_bytes")) {
        wal = (long) p.sum("argos_wal_bytes");
      }
    } catch (RuntimeException e) {
      facts.note("Server metrics scrape failed: " + e.getMessage());
    }
    if (wal < 0) {
      wal = directorySize(env.walDirectory());
    }
    long backlog = -1;
    try {
      backlog = WalBacklog.scan(env.walDirectory());
    } catch (IOException | RuntimeException e) {
      facts.note("WAL backlog scan failed: " + e.getMessage());
    }
    long maxParts = -1;
    String busiest = "";
    long merges = -1;
    long disk = -1;
    Map<String, Long> rows = new LinkedHashMap<>();
    Map<String, Long> bytes = new LinkedHashMap<>();
    try {
      ClickHouseProbe.Sample s = clickHouse.sample();
      maxParts = s.maxActivePartsPerPartition();
      busiest = s.busiestPartition();
    } catch (RuntimeException e) {
      facts.note("ClickHouse parts sample failed: " + e.getMessage());
    }
    try {
      merges =
          Long.parseLong(
              reader
                  .scalar(
                      "SELECT count() FROM system.merges WHERE database = '"
                          + ServerTestEnvironment.DATABASE
                          + "'")
                  .strip());
    } catch (RuntimeException e) {
      facts.note("ClickHouse merges sample failed: " + e.getMessage());
    }
    try {
      long sum = 0;
      for (List<String> r :
          reader.rows(
              "SELECT table, sum(rows), sum(bytes_on_disk) FROM system.parts WHERE active AND"
                  + " database = '"
                  + ServerTestEnvironment.DATABASE
                  + "' GROUP BY table ORDER BY table")) {
        rows.put(r.get(0), Long.parseLong(r.get(1).strip()));
        long b = Long.parseLong(r.get(2).strip());
        bytes.put(r.get(0), b);
        sum += b;
      }
      disk = sum;
    } catch (RuntimeException e) {
      rows.clear();
      bytes.clear();
      facts.note("ClickHouse disk sample failed: " + e.getMessage());
    }
    samples.add(
        new StackSample(
            seconds, appFds, rssBytes, heap, nonHeap, direct, wal, maxParts, busiest, merges, disk,
            rows, bytes, backlog));
  }

  /** The total size of the regular files below {@code dir}, -1 when it cannot be read. */
  static long directorySize(Path dir) {
    if (!Files.isDirectory(dir)) {
      return -1;
    }
    try (Stream<Path> files = Files.walk(dir)) {
      return files
          .filter(Files::isRegularFile)
          .mapToLong(
              f -> {
                try {
                  return Files.size(f);
                } catch (IOException e) {
                  return 0;
                }
              })
          .sum();
    } catch (IOException | RuntimeException e) {
      return -1;
    }
  }
}
