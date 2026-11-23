/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.core.metric;

import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * The system rows of the 10 §11 metric table (F-A11):
 *
 * <ul>
 *   <li>{@code system_cpu_usage_ratio}, one series: on Linux with a cgroup CPU limit the group's
 *       CPU time over wall time and the limit (in cores) since the previous collection; otherwise
 *       the JVM's system CPU load ({@link OsBean}, also on Windows and macOS);
 *   <li>Linux only: {@code system_memory_used_bytes} and {@code system_memory_total_bytes} (the
 *       group's working set (usage less inactive page cache, C-229 (8)) and limit when it has a
 *       memory limit, else {@code /proc/meminfo} {@code MemTotal - MemAvailable} and {@code
 *       MemTotal}), {@code system_disk_used_bytes{mount}} and {@code
 *       system_disk_total_bytes{mount}} (local file systems of {@code /proc/self/mounts}, home
 *       directories as {@code ~} ({@link #mountLabel}), at most {@value #MAX_MOUNTS}; network and
 *       pseudo file systems are skipped so the metrics thread never waits on a remote server),
 *       {@code system_network_receive_bytes_total{iface}} and {@code
 *       system_network_transmit_bytes_total{iface}} ({@code /proc/net/dev} without {@code lo}, at
 *       most {@value #MAX_IFACES});
 *   <li>containers ({@link CgroupReader}): {@code container_cpu_limit_cores} and {@code
 *       container_memory_limit_bytes} when there is a limit, {@code
 *       container_cpu_throttled_seconds_total} when the group reports it.
 * </ul>
 *
 * The root directory stands for {@code /} (fixtures in tests). Metrics thread only; never throws.
 */
final class SystemMetrics {
  static final int MAX_MOUNTS = 16;
  static final int MAX_IFACES = 16;

  /** Local file systems whose size is reported. */
  static final Set<String> DISK_TYPES =
      Collections.unmodifiableSet(
          new HashSet<String>(
              Arrays.asList(
                  "ext2",
                  "ext3",
                  "ext4",
                  "xfs",
                  "btrfs",
                  "zfs",
                  "overlay",
                  "vfat",
                  "f2fs",
                  "jfs",
                  "reiserfs")));

  private final Path root;
  private final boolean linux;
  private final CgroupReader cgroup;
  private final OsBean os;
  private long lastCgroupCpuNanos = -1;
  private long lastWallNanos;

  SystemMetrics(Path root, boolean linux, CgroupReader cgroup, OsBean os) {
    this.root = root;
    this.linux = linux;
    this.cgroup = cgroup;
    this.os = os;
  }

  void collect(MetricRegistry r, long nowNanos) {
    try {
      cpu(r, nowNanos);
    } catch (Throwable ignored) {
      // Missing this time.
    }
    try {
      container(r);
    } catch (Throwable ignored) {
      // Missing this time.
    }
    if (!linux) {
      return;
    }
    try {
      memory(r);
    } catch (Throwable ignored) {
      // Missing this time.
    }
    try {
      disks(r);
    } catch (Throwable ignored) {
      // Missing this time.
    }
    try {
      network(r);
    } catch (Throwable ignored) {
      // Missing this time.
    }
  }

  private void cpu(MetricRegistry r, long nowNanos) {
    double limit = linux ? cgroup.cpuLimitCores() : Double.NaN;
    long used = Double.isNaN(limit) ? -1 : cgroup.cpuUsageNanos();
    if (used >= 0) {
      if (lastCgroupCpuNanos >= 0 && nowNanos > lastWallNanos) {
        double ratio = (used - lastCgroupCpuNanos) / ((double) (nowNanos - lastWallNanos) * limit);
        r.gauge("system_cpu_usage_ratio", JvmMetrics.clamp(ratio));
      }
      lastCgroupCpuNanos = used;
      lastWallNanos = nowNanos;
      return;
    }
    lastCgroupCpuNanos = -1;
    double load = os.systemCpuLoad();
    if (load >= 0) {
      r.gauge("system_cpu_usage_ratio", JvmMetrics.clamp(load));
    }
  }

  private void container(MetricRegistry r) {
    double cores = cgroup.cpuLimitCores();
    if (!Double.isNaN(cores)) {
      r.gauge("container_cpu_limit_cores", cores);
    }
    long memory = cgroup.memoryLimitBytes();
    if (memory > 0) {
      r.gauge("container_memory_limit_bytes", memory);
    }
    long throttled = cgroup.throttledNanos();
    if (throttled >= 0) {
      r.counter("container_cpu_throttled_seconds_total", throttled / 1e9);
    }
  }

  private void memory(MetricRegistry r) {
    long limit = cgroup.memoryLimitBytes();
    // The working set, not the usage: page cache counts in the usage and would show a container
    // near its limit while the kernel can still reclaim it (C-229 (8)).
    long usage = limit > 0 ? cgroup.memoryWorkingSetBytes() : -1;
    if (limit > 0 && usage >= 0) {
      r.gauge("system_memory_total_bytes", limit);
      r.gauge("system_memory_used_bytes", usage);
      return;
    }
    String meminfo = CgroupReader.read(root.resolve("proc/meminfo"));
    if (meminfo == null) {
      return;
    }
    long total = meminfoKb(meminfo, "MemTotal");
    long available = meminfoKb(meminfo, "MemAvailable");
    if (available < 0) {
      long free = meminfoKb(meminfo, "MemFree");
      long buffers = meminfoKb(meminfo, "Buffers");
      long cached = meminfoKb(meminfo, "Cached");
      available = free < 0 ? -1 : free + Math.max(0, buffers) + Math.max(0, cached);
    }
    if (total > 0) {
      r.gauge("system_memory_total_bytes", total * 1024.0);
      if (available >= 0 && available <= total) {
        r.gauge("system_memory_used_bytes", (total - available) * 1024.0);
      }
    }
  }

  /** {@code key: <n> kB} of {@code /proc/meminfo} in kB; -1. */
  static long meminfoKb(String meminfo, String key) {
    for (String line : meminfo.split("\n", -1)) {
      if (line.startsWith(key + ":")) {
        String[] parts = line.substring(key.length() + 1).trim().split("\\s+", -1);
        return parts.length == 0 ? -1 : CgroupReader.parse(parts[0]);
      }
    }
    return -1;
  }

  private void disks(MetricRegistry r) {
    Set<String> labels = new HashSet<String>();
    for (String mount : mounts(CgroupReader.read(root.resolve("proc/self/mounts")))) {
      String label = mountLabel(mount);
      if (!labels.add(label)) {
        // Two home directories with one label: the first one only (a series name is unique).
        continue;
      }
      File dir = root.resolve(mount.substring(1)).toFile();
      if (!dir.isDirectory()) {
        // A bind-mounted file (a container's /etc/hosts) is not a file system of its own.
        continue;
      }
      long total = dir.getTotalSpace();
      if (total <= 0) {
        continue;
      }
      long free = dir.getFreeSpace();
      r.gauge(MetricRegistry.name("system_disk_total_bytes", "mount", label), total);
      if (free >= 0 && free <= total) {
        r.gauge(MetricRegistry.name("system_disk_used_bytes", "mount", label), total - free);
      }
    }
  }

  /**
   * The {@code mount} label of a mount point: a home directory names a user, so the segments {@code
   * home/<name>} and {@code Users/<name>} (any case: macOS, Windows) become {@code ~} (ADR-0010
   * C-229 (9), 02 data classification): {@code /home/alice/data} is {@code ~/data}, {@code
   * C:\Users\alice} is {@code ~}, {@code /var/home/alice} is {@code /var/~}. Other paths are kept.
   */
  static String mountLabel(String mount) {
    char sep = mount.indexOf('/') < 0 && mount.indexOf('\\') >= 0 ? '\\' : '/';
    List<String> parts = new ArrayList<String>();
    int from = 0;
    for (int i = 0; i <= mount.length(); i++) {
      if (i == mount.length() || mount.charAt(i) == sep) {
        parts.add(mount.substring(from, i));
        from = i + 1;
      }
    }
    List<String> kept = new ArrayList<String>(parts.size());
    boolean changed = false;
    for (int i = 0; i < parts.size(); i++) {
      String p = parts.get(i);
      if ((p.equals("home") || p.equalsIgnoreCase("users"))
          && i + 1 < parts.size()
          && !parts.get(i + 1).isEmpty()) {
        kept.add("~");
        changed = true;
        i++;
      } else {
        kept.add(p);
      }
    }
    if (!changed) {
      return mount;
    }
    // A home directory at the root (or under a drive letter) is "~" itself, not "/~" or "C:\~".
    if (kept.size() >= 2
        && kept.get(1).equals("~")
        && (kept.get(0).isEmpty() || isDrive(kept.get(0)))) {
      kept.remove(0);
    }
    StringBuilder out = new StringBuilder(mount.length());
    for (int i = 0; i < kept.size(); i++) {
      if (i > 0) {
        out.append(sep);
      }
      out.append(kept.get(i));
    }
    return out.toString();
  }

  private static boolean isDrive(String part) {
    return part.length() == 2 && part.charAt(1) == ':' && Character.isLetter(part.charAt(0));
  }

  /**
   * Mount points of local file systems ({@link #DISK_TYPES}), unique, at most {@value #MAX_MOUNTS}.
   */
  static List<String> mounts(String text) {
    List<String> out = new ArrayList<String>();
    if (text == null) {
      return out;
    }
    for (String line : text.split("\n", -1)) {
      String[] parts = line.trim().split("\\s+", -1);
      if (parts.length < 3 || !DISK_TYPES.contains(parts[2])) {
        continue;
      }
      String mount = unescapeMount(parts[1]);
      if (mount.startsWith("/") && !out.contains(mount)) {
        out.add(mount);
        if (out.size() >= MAX_MOUNTS) {
          break;
        }
      }
    }
    return out;
  }

  /** {@code /proc/mounts} escapes space, tab, newline and backslash as octal ({@code \040}). */
  static String unescapeMount(String text) {
    if (text.indexOf('\\') < 0) {
      return text;
    }
    StringBuilder out = new StringBuilder(text.length());
    for (int i = 0; i < text.length(); i++) {
      char c = text.charAt(i);
      if (c == '\\' && i + 3 < text.length()) {
        String octal = text.substring(i + 1, i + 4);
        if (octal.matches("[0-7]{3}")) {
          out.append((char) Integer.parseInt(octal, 8));
          i += 3;
          continue;
        }
      }
      out.append(c);
    }
    return out.toString();
  }

  private void network(MetricRegistry r) {
    String text = CgroupReader.read(root.resolve("proc/net/dev"));
    if (text == null) {
      return;
    }
    int ifaces = 0;
    for (String line : text.split("\n", -1)) {
      int colon = line.indexOf(':');
      if (colon < 0) {
        continue;
      }
      String iface = line.substring(0, colon).trim();
      if (iface.isEmpty() || iface.equals("lo") || iface.contains("|")) {
        continue;
      }
      String[] fields = line.substring(colon + 1).trim().split("\\s+", -1);
      if (fields.length < 9) {
        continue;
      }
      long received = CgroupReader.parse(fields[0]);
      long transmitted = CgroupReader.parse(fields[8]);
      if (received >= 0) {
        r.counter(
            MetricRegistry.name("system_network_receive_bytes_total", "iface", iface), received);
      }
      if (transmitted >= 0) {
        r.counter(
            MetricRegistry.name("system_network_transmit_bytes_total", "iface", iface),
            transmitted);
      }
      if (++ifaces >= MAX_IFACES) {
        break;
      }
    }
  }
}
