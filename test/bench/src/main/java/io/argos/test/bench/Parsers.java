/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.test.bench;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalDouble;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parsers of tool output: {@code jcmd} (Thread.print, GC.class_histogram, VM.native_memory
 * summary), cgroup {@code cpu.stat}, the agent's start banner and Spring Boot's start line.
 */
final class Parsers {
  private Parsers() {}

  /** Thread name prefix of every agent thread (10 §14 thread budget, ArgosThread). */
  static final String AGENT_THREAD_PREFIX = "argos-";

  /**
   * Class name prefix whose instances count as agent heap (01 §7.3 item 7: "argos." objects). The
   * agent's own and shaded classes are all below io.argos.agent; the sample apps use
   * io.argos.sample, which must not count.
   */
  static final String AGENT_CLASS_PREFIX = "io.argos.agent.";

  private static final Pattern THREAD_HEADER = Pattern.compile("^\"(.*)\" .*$");
  private static final Pattern THREAD_CPU = Pattern.compile(" cpu=([0-9.]+)ms ");
  private static final Pattern HISTOGRAM_ROW =
      Pattern.compile("^\\s*\\d+:\\s+(\\d+)\\s+(\\d+)\\s+(\\S+).*$");
  private static final Pattern NMT_TOTAL =
      Pattern.compile("^Total: reserved=(\\d+)KB, committed=(\\d+)KB");
  private static final Pattern NMT_CATEGORY =
      Pattern.compile("^-\\s+(.+?) \\(reserved=(\\d+)KB, committed=(\\d+)KB\\)");
  private static final Pattern NMT_THREADS = Pattern.compile("\\(threads? #(\\d+)\\)");
  private static final Pattern NMT_CLASSES = Pattern.compile("\\(classes #(\\d+)\\)");
  private static final Pattern NMT_COMMITTED =
      Pattern.compile("\\(\\s*reserved=(\\d+)KB, committed=(\\d+)KB\\)");
  private static final Pattern NMT_USED = Pattern.compile("\\(\\s*used=(\\d+)KB\\)");
  private static final Pattern BANNER = Pattern.compile("Argos agent .* started in ([0-9.]+) ms");

  /** {@code premain_ms=N} of a self-diagnostic line (10 §19, 18 §7.4), once the agent writes it. */
  private static final Pattern PREMAIN_MS = Pattern.compile("\\bpremain_ms[=:]\\s*([0-9.]+)");

  private static final Pattern METASPACE_BOTH =
      Pattern.compile(
          "^\\s*Both:\\s+\\d+ chunks,\\s+([0-9.]+) (bytes|KB|MB|GB) capacity,\\s+([0-9.]+) (bytes|KB|MB|GB)"
              + " \\([^)]*\\) committed,\\s+([0-9.]+) (bytes|KB|MB|GB) \\([^)]*\\) used");
  private static final Pattern METASPACE_TOTAL =
      Pattern.compile("^Total Usage - (\\d+) loaders, (\\d+) classes");
  private static final Pattern SPRING_STARTED =
      Pattern.compile("Started \\S+ in ([0-9.]+) seconds(?: \\(process running for ([0-9.]+)\\))?");
  private static final Pattern LINES = Pattern.compile("\\R");

  /** Threads of one {@code jcmd Thread.print}: platform threads with their CPU time. */
  record ThreadDump(int javaThreads, Map<String, Double> agentThreadCpuMs) {
    double agentCpuMs() {
      return agentThreadCpuMs.values().stream().mapToDouble(Double::doubleValue).sum();
    }
  }

  /**
   * Counts the Java threads ({@code "name" #N ...} headers) and reads {@code cpu=...ms} of every
   * thread whose name starts with {@code argos-}. JVM-internal threads (no {@code #N}) are not
   * counted as Java threads.
   */
  static ThreadDump threadDump(String text) {
    int javaThreads = 0;
    Map<String, Double> agent = new LinkedHashMap<>();
    for (String line : LINES.split(text, -1)) {
      Matcher header = THREAD_HEADER.matcher(line);
      if (!header.matches()) {
        continue;
      }
      if (line.contains("\" #")) {
        javaThreads++;
      }
      String name = header.group(1);
      if (name.startsWith(AGENT_THREAD_PREFIX)) {
        Matcher cpu = THREAD_CPU.matcher(line);
        agent.put(name, cpu.find() ? Double.parseDouble(cpu.group(1)) : Double.NaN);
      }
    }
    return new ThreadDump(javaThreads, agent);
  }

  /** Bytes and instances of {@code io.argos.*} classes in a {@code GC.class_histogram}. */
  record AgentHeap(long instances, long bytes, long totalBytes, List<String> topClasses) {}

  static AgentHeap classHistogram(String text) {
    long instances = 0;
    long bytes = 0;
    long total = 0;
    List<String> top = new ArrayList<>();
    for (String line : LINES.split(text, -1)) {
      Matcher row = HISTOGRAM_ROW.matcher(line);
      if (!row.matches()) {
        continue;
      }
      long n = Long.parseLong(row.group(1));
      long b = Long.parseLong(row.group(2));
      String name = row.group(3);
      total += b;
      if (name.startsWith(AGENT_CLASS_PREFIX) || name.startsWith("[L" + AGENT_CLASS_PREFIX)) {
        instances += n;
        bytes += b;
        if (top.size() < 10) {
          top.add(name + " " + n + " instances " + b + " bytes");
        }
      }
    }
    return new AgentHeap(instances, bytes, total, top);
  }

  /** {@code VM.native_memory summary}: committed KB per category, threads, classes, metaspace. */
  record NativeMemory(
      long totalCommittedKb,
      Map<String, Long> committedKb,
      long threads,
      long classes,
      long metaspaceUsedKb,
      long metaspaceCommittedKb) {
    long committed(String category) {
      return committedKb.getOrDefault(category, 0L);
    }
  }

  static NativeMemory nativeMemory(String text) {
    long total = -1;
    long threads = -1;
    long classes = -1;
    long used = 0;
    long metaCommitted = 0;
    boolean usedSeen = false;
    Map<String, Long> categories = new LinkedHashMap<>();
    String category = "";
    for (String line : LINES.split(text, -1)) {
      Matcher m = NMT_TOTAL.matcher(line);
      if (m.find()) {
        total = Long.parseLong(m.group(2));
        continue;
      }
      m = NMT_CATEGORY.matcher(line);
      if (m.find()) {
        category = m.group(1).trim();
        categories.put(category, Long.parseLong(m.group(3)));
        continue;
      }
      if (category.equals("Thread")) {
        m = NMT_THREADS.matcher(line);
        if (m.find()) {
          threads = Long.parseLong(m.group(1));
        }
      }
      if (category.equals("Class")) {
        m = NMT_CLASSES.matcher(line);
        if (m.find()) {
          classes = Long.parseLong(m.group(1));
        }
        // "(  Metadata:   )" and "(  Class space:)" each have reserved/committed and used lines.
        m = NMT_COMMITTED.matcher(line);
        if (m.find()) {
          metaCommitted += Long.parseLong(m.group(2));
        }
        m = NMT_USED.matcher(line);
        if (m.find()) {
          used += Long.parseLong(m.group(1));
          usedSeen = true;
        }
      }
    }
    return new NativeMemory(
        total, categories, threads, classes, usedSeen ? used : -1, usedSeen ? metaCommitted : -1);
  }

  /** {@code usage_usec} of a cgroup v2 {@code cpu.stat}, -1 when absent. */
  static long cgroupUsageMicros(String cpuStat) {
    return ProcFs.cgroupUsageMicros(cpuStat);
  }

  /**
   * The agent's premain time (N-30, 18 §7.4): {@code premain_ms=N} of its self-diagnostics when
   * present, else its start banner ({@code started in N ms}).
   */
  static OptionalDouble premainMillis(String agentLog) {
    Matcher m = PREMAIN_MS.matcher(agentLog);
    if (m.find()) {
      return OptionalDouble.of(Double.parseDouble(m.group(1)));
    }
    m = BANNER.matcher(agentLog);
    return m.find() ? OptionalDouble.of(Double.parseDouble(m.group(1))) : OptionalDouble.empty();
  }

  /** {@code jcmd VM.metaspace} totals: used and committed bytes of "Both", classes, loaders. */
  record Metaspace(long usedBytes, long committedBytes, long classes, long loaders) {}

  /**
   * Parses the "Total Usage" block of {@code jcmd VM.metaspace} (JDK 16+ format, 18 §7.4: the
   * metaspace difference on - off); null when the block is missing.
   */
  static Metaspace vmMetaspace(String text) {
    long classes = -1;
    long loaders = -1;
    boolean total = false;
    for (String line : LINES.split(text, -1)) {
      Matcher t = METASPACE_TOTAL.matcher(line.trim());
      if (t.find()) {
        loaders = Long.parseLong(t.group(1));
        classes = Long.parseLong(t.group(2));
        total = true;
        continue;
      }
      Matcher m = METASPACE_BOTH.matcher(line);
      if (total && m.find()) {
        return new Metaspace(
            bytes(m.group(5), m.group(6)), bytes(m.group(3), m.group(4)), classes, loaders);
      }
    }
    return null;
  }

  private static long bytes(String value, String unit) {
    double v = Double.parseDouble(value);
    double factor =
        switch (unit) {
          case "KB" -> 1024;
          case "MB" -> 1024 * 1024;
          case "GB" -> 1024.0 * 1024 * 1024;
          default -> 1;
        };
    return Math.round(v * factor);
  }

  /** Spring Boot's {@code Started X in S seconds (process running for P)}: [S, P or NaN]. */
  static double[] springStarted(String log) {
    Matcher m = SPRING_STARTED.matcher(log);
    if (!m.find()) {
      return new double[0];
    }
    double running = m.group(2) == null ? Double.NaN : Double.parseDouble(m.group(2));
    return new double[] {Double.parseDouble(m.group(1)), running};
  }
}
