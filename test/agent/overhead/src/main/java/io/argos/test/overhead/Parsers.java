/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.test.overhead;

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
  private static final Pattern CGROUP_USAGE = Pattern.compile("(?m)^usage_usec (\\d+)");
  private static final Pattern BANNER = Pattern.compile("Argos agent .* started in ([0-9.]+) ms");
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
    Matcher m = CGROUP_USAGE.matcher(cpuStat);
    return m.find() ? Long.parseLong(m.group(1)) : -1;
  }

  /** The agent's premain time from its start banner ({@code started in N ms}, N-30). */
  static OptionalDouble premainMillis(String agentLog) {
    Matcher m = BANNER.matcher(agentLog);
    return m.find() ? OptionalDouble.of(Double.parseDouble(m.group(1))) : OptionalDouble.empty();
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
