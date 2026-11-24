/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.ops.storage;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * The used ratio of a ClickHouse disk as the storage health job and the emergency retention see it
 * (F-S23). The server uses {@link #systemDisks()}: {@code (total_space - free_space) / total_space}
 * of {@code system.disks}. Development builds can replace it with {@link #file} through {@code
 * argos.testing.disk-usage-file} (18 §15), so tests drive the thresholds without filling a disk.
 */
@FunctionalInterface
public interface DiskUsageProbe {

  /**
   * The used ratio of one ClickHouse disk.
   *
   * @param disk the disk name of {@code system.disks}
   * @param freeBytes {@code free_space}
   * @param totalBytes {@code total_space}
   * @return the ratio in {@code [0, 1]}, or NaN when unknown
   */
  double usedRatio(String disk, long freeBytes, long totalBytes);

  /** The ratio from {@code system.disks}. */
  static DiskUsageProbe systemDisks() {
    return (disk, free, total) -> {
      if (total <= 0) {
        return Double.NaN;
      }
      double used = (double) (total - Math.max(0, Math.min(free, total))) / total;
      return Math.max(0, Math.min(1, used));
    };
  }

  /**
   * The ratio written in {@code file} (one decimal number, e.g. {@code 0.91}) for every disk while
   * the file exists and parses to a value in {@code [0, 1]}; otherwise {@code fallback}.
   */
  static DiskUsageProbe file(Path file, DiskUsageProbe fallback) {
    return (disk, free, total) -> {
      try {
        String text = Files.readString(file, StandardCharsets.UTF_8).strip();
        double v = Double.parseDouble(text);
        if (v >= 0 && v <= 1) {
          return v;
        }
      } catch (IOException | NumberFormatException e) {
        // no override now
      }
      return fallback.usedRatio(disk, free, total);
    };
  }
}
