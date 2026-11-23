/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.core.metric;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Locale;

/**
 * The control group limits and usage of this process (10 §11 "시스템(Linux)", F-A11): the instance
 * metadata {@code cgroup=v1|v2|none} ({@code AgentHello.cgroup}) and the values behind {@code
 * container_*}, the limit-based {@code system_cpu_usage_ratio} and {@code system_memory_*_bytes}.
 *
 * <ul>
 *   <li><b>v2</b> (unified hierarchy, {@code <root>/sys/fs/cgroup/cgroup.controllers}): {@code
 *       cpu.max} ({@code <quota> <period>} or {@code max <period>}), {@code cpu.stat} ({@code
 *       usage_usec}, {@code throttled_usec}), {@code memory.max} (bytes or {@code max}), {@code
 *       memory.current}, {@code memory.stat} ({@code inactive_file}).
 *   <li><b>v1</b> (controller directories): {@code cpu/cpu.cfs_quota_us} (-1 unlimited), {@code
 *       cpu.cfs_period_us}, {@code cpu.stat} ({@code throttled_time} ns), {@code
 *       cpuacct/cpuacct.usage} (ns), {@code memory/memory.limit_in_bytes} (a value of {@value
 *       #V1_UNLIMITED_FLOOR} or more is unlimited), {@code memory.usage_in_bytes}, {@code
 *       memory.stat} ({@code total_inactive_file}). The joined directory names ({@code
 *       cpu,cpuacct}, {@code cpuacct,cpu}) are found too.
 *   <li><b>none</b>: neither (not Linux, or no cgroup file system).
 * </ul>
 *
 * The directory of the process is its path from {@code <root>/proc/self/cgroup} under the mount
 * when it exists (a host or a container without its own namespace), else the mount itself (a
 * container's namespace root). A limit set higher in the hierarchy counts too: the smallest limit
 * from the process directory up to the mount is used (at most {@value #MAX_DEPTH} levels).
 *
 * <p>The root directory is a constructor argument, so fixtures stand for {@code /} in tests. Files
 * are read whole with a size bound of {@value #MAX_FILE_BYTES} bytes; a missing or unreadable file
 * is a missing value (-1, NaN). Never throws. Not thread-safe (metrics thread; the mode alone is
 * read elsewhere).
 */
public final class CgroupReader {
  public static final String V1 = "v1";
  public static final String V2 = "v2";
  public static final String NONE = "none";

  /** v1 memory limits at or above this are "no limit" (the kernel's page-rounded LONG_MAX). */
  static final long V1_UNLIMITED_FLOOR = 1L << 60;

  static final int MAX_DEPTH = 32;
  static final int MAX_FILE_BYTES = 64 * 1024;

  private static volatile String systemMode;

  private final String mode;
  private final Path cpuDir;
  private final Path cpuMount;
  private final Path cpuacctDir;
  private final Path memoryDir;
  private final Path memoryMount;

  /** The reader of {@code root} (the file system root {@code /} outside tests). */
  public CgroupReader(Path root) {
    Path mount = root.resolve("sys/fs/cgroup");
    String procCgroup = read(root.resolve("proc/self/cgroup"));
    if (Files.isRegularFile(mount.resolve("cgroup.controllers"))) {
      Path dir = processDir(mount, v2Path(procCgroup));
      this.mode = V2;
      this.cpuDir = dir;
      this.cpuMount = mount;
      this.cpuacctDir = dir;
      this.memoryDir = dir;
      this.memoryMount = mount;
      return;
    }
    Path cpu = controller(mount, "cpu,cpuacct", "cpu", "cpuacct,cpu");
    Path cpuacct = controller(mount, "cpu,cpuacct", "cpuacct", "cpuacct,cpu");
    Path memory = controller(mount, "memory");
    if (cpu == null && cpuacct == null && memory == null) {
      this.mode = NONE;
      this.cpuDir = null;
      this.cpuMount = null;
      this.cpuacctDir = null;
      this.memoryDir = null;
      this.memoryMount = null;
      return;
    }
    this.mode = V1;
    this.cpuMount = cpu;
    this.cpuDir = cpu == null ? null : processDir(cpu, v1Path(procCgroup, "cpu"));
    this.cpuacctDir = cpuacct == null ? null : processDir(cpuacct, v1Path(procCgroup, "cpuacct"));
    this.memoryMount = memory;
    this.memoryDir = memory == null ? null : processDir(memory, v1Path(procCgroup, "memory"));
  }

  /** The reader of this machine: {@code /} on Linux, a reader of mode {@code none} elsewhere. */
  public static CgroupReader system() {
    if (!linux()) {
      return new CgroupReader();
    }
    try {
      return new CgroupReader(Paths.get("/"));
    } catch (Throwable t) {
      return new CgroupReader();
    }
  }

  /** The mode of this machine ({@code AgentHello.cgroup}), detected once. Never throws. */
  public static String systemMode() {
    String mode = systemMode;
    if (mode == null) {
      mode = system().mode();
      systemMode = mode;
    }
    return mode;
  }

  /** True on Linux ({@code os.name}). */
  static boolean linux() {
    try {
      return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("linux");
    } catch (Throwable t) {
      return false;
    }
  }

  private CgroupReader() {
    this.mode = NONE;
    this.cpuDir = null;
    this.cpuMount = null;
    this.cpuacctDir = null;
    this.memoryDir = null;
    this.memoryMount = null;
  }

  /** {@code v1}, {@code v2} or {@code none}. */
  public String mode() {
    return mode;
  }

  /** The CPU limit in cores (quota over period); NaN without a limit. */
  public double cpuLimitCores() {
    if (cpuDir == null) {
      return Double.NaN;
    }
    double limit = Double.NaN;
    for (Path dir = cpuDir; dir != null; dir = parentWithin(dir, cpuMount)) {
      double cores = mode.equals(V2) ? v2Cores(dir) : v1Cores(dir);
      if (!Double.isNaN(cores) && (Double.isNaN(limit) || cores < limit)) {
        limit = cores;
      }
    }
    return limit;
  }

  private static double v2Cores(Path dir) {
    String text = read(dir.resolve("cpu.max"));
    if (text == null) {
      return Double.NaN;
    }
    String[] parts = text.trim().split("\\s+", -1);
    if (parts.length < 1 || parts[0].equals("max")) {
      return Double.NaN;
    }
    long quota = parse(parts[0]);
    long period = parts.length > 1 ? parse(parts[1]) : 100_000L;
    return quota > 0 && period > 0 ? (double) quota / period : Double.NaN;
  }

  private static double v1Cores(Path dir) {
    long quota = number(dir.resolve("cpu.cfs_quota_us"));
    long period = number(dir.resolve("cpu.cfs_period_us"));
    return quota > 0 && period > 0 ? (double) quota / period : Double.NaN;
  }

  /** The memory limit in bytes; -1 without a limit. */
  public long memoryLimitBytes() {
    if (memoryDir == null) {
      return -1;
    }
    long limit = -1;
    for (Path dir = memoryDir; dir != null; dir = parentWithin(dir, memoryMount)) {
      long bytes =
          mode.equals(V2)
              ? number(dir.resolve("memory.max"))
              : number(dir.resolve("memory.limit_in_bytes"));
      if (bytes >= V1_UNLIMITED_FLOOR) {
        bytes = -1;
      }
      if (bytes > 0 && (limit < 0 || bytes < limit)) {
        limit = bytes;
      }
    }
    return limit;
  }

  /**
   * Memory charged to the group ({@code memory.current}, {@code memory.usage_in_bytes}), page cache
   * included; -1. The series report {@link #memoryWorkingSetBytes()}.
   */
  public long memoryUsageBytes() {
    if (memoryDir == null) {
      return -1;
    }
    return mode.equals(V2)
        ? number(memoryDir.resolve("memory.current"))
        : number(memoryDir.resolve("memory.usage_in_bytes"));
  }

  /**
   * The group's working set, the memory it cannot give back without paging out (ADR-0010 C-229
   * (8)): the usage ({@link #memoryUsageBytes()}) less the inactive page cache, {@code
   * inactive_file} of {@code memory.stat} (v2) or {@code total_inactive_file} (v1, the hierarchy's
   * total). Without that line the usage itself; 0 when the cache exceeds the usage (as cAdvisor and
   * the kubelet compute it). -1 when the usage is unknown.
   */
  public long memoryWorkingSetBytes() {
    long usage = memoryUsageBytes();
    if (usage < 0) {
      return -1;
    }
    long inactive =
        statValue(
            memoryDir.resolve("memory.stat"),
            mode.equals(V2) ? "inactive_file" : "total_inactive_file");
    if (inactive < 0) {
      return usage;
    }
    return inactive > usage ? 0 : usage - inactive;
  }

  /** CPU time used by the group in nanoseconds; -1 when unknown. */
  public long cpuUsageNanos() {
    if (cpuacctDir == null) {
      return -1;
    }
    if (mode.equals(V2)) {
      long usec = statValue(cpuacctDir.resolve("cpu.stat"), "usage_usec");
      return usec < 0 ? -1 : usec * 1000L;
    }
    return number(cpuacctDir.resolve("cpuacct.usage"));
  }

  /** Time the group was throttled by its CPU limit, in nanoseconds; -1 when unknown. */
  public long throttledNanos() {
    if (cpuDir == null) {
      return -1;
    }
    if (mode.equals(V2)) {
      long usec = statValue(cpuDir.resolve("cpu.stat"), "throttled_usec");
      return usec < 0 ? -1 : usec * 1000L;
    }
    return statValue(cpuDir.resolve("cpu.stat"), "throttled_time");
  }

  // --- paths ----------------------------------------------------------------------------------

  private static Path controller(Path mount, String... names) {
    for (String name : names) {
      Path dir = mount.resolve(name);
      if (Files.isDirectory(dir)) {
        return dir;
      }
    }
    return null;
  }

  /** {@code 0::<path>} of {@code /proc/self/cgroup}, or null. */
  static String v2Path(String procCgroup) {
    if (procCgroup == null) {
      return null;
    }
    for (String line : procCgroup.split("\n", -1)) {
      if (line.startsWith("0::")) {
        return line.substring(3).trim();
      }
    }
    return null;
  }

  /** The path of the v1 hierarchy that holds {@code controller}, or null. */
  static String v1Path(String procCgroup, String controller) {
    if (procCgroup == null) {
      return null;
    }
    for (String line : procCgroup.split("\n", -1)) {
      String[] parts = line.split(":", 3);
      if (parts.length == 3) {
        for (String c : parts[1].split(",", -1)) {
          if (c.equals(controller)) {
            return parts[2].trim();
          }
        }
      }
    }
    return null;
  }

  /** {@code mount/<path>} when that directory exists, else {@code mount}. */
  private static Path processDir(Path mount, String path) {
    if (path == null || path.isEmpty() || path.equals("/")) {
      return mount;
    }
    String relative = path.startsWith("/") ? path.substring(1) : path;
    try {
      Path dir = mount.resolve(relative).normalize();
      if (dir.startsWith(mount) && Files.isDirectory(dir)) {
        return dir;
      }
    } catch (RuntimeException e) {
      // An odd path: the mount.
    }
    return mount;
  }

  /** The parent of {@code dir} while it stays within {@code mount}, else null. */
  private static Path parentWithin(Path dir, Path mount) {
    if (mount == null || dir.equals(mount)) {
      return null;
    }
    Path parent = dir.getParent();
    int depth = 0;
    for (Path p = parent; p != null && !p.equals(mount); p = p.getParent()) {
      if (++depth > MAX_DEPTH) {
        return null;
      }
    }
    return parent != null && parent.startsWith(mount) ? parent : null;
  }

  // --- files ----------------------------------------------------------------------------------

  /** The value {@code key <n>} of a flat-keyed file such as {@code cpu.stat}; -1. */
  static long statValue(Path file, String key) {
    String text = read(file);
    if (text == null) {
      return -1;
    }
    for (String line : text.split("\n", -1)) {
      String[] parts = line.trim().split("\\s+", -1);
      if (parts.length == 2 && parts[0].equals(key)) {
        return parse(parts[1]);
      }
    }
    return -1;
  }

  /** The single number of {@code file}; -1 for a missing file, {@code max} or garbage. */
  static long number(Path file) {
    String text = read(file);
    return text == null ? -1 : parse(text.trim());
  }

  static long parse(String text) {
    try {
      return Long.parseLong(text.trim());
    } catch (NumberFormatException e) {
      return -1;
    }
  }

  /** The text of {@code file} (at most {@value #MAX_FILE_BYTES} bytes), or null. */
  static String read(Path file) {
    if (file == null) {
      return null;
    }
    try (InputStream in = Files.newInputStream(file);
        BufferedReader reader =
            new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
      StringBuilder out = new StringBuilder();
      char[] buffer = new char[4096];
      int n;
      while ((n = reader.read(buffer)) > 0) {
        out.append(buffer, 0, n);
        if (out.length() > MAX_FILE_BYTES) {
          out.setLength(MAX_FILE_BYTES);
          break;
        }
      }
      return out.toString();
    } catch (IOException | RuntimeException e) {
      return null;
    }
  }
}
