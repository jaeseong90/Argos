/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.testing.metric;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/**
 * The cgroup mode an agent on this host reports in {@code AgentHello.cgroup} (10 §11): {@code v2}
 * with the unified hierarchy ({@code /sys/fs/cgroup/cgroup.controllers}), {@code v1} with
 * controller directories, {@code none} otherwise and on every OS but Linux. The tests' own reading
 * of the host, independent of the agent's {@code CgroupReader}.
 */
public final class HostCgroup {
  private HostCgroup() {}

  /** {@code v1}, {@code v2} or {@code none}. */
  public static String mode() {
    if (!System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("linux")) {
      return "none";
    }
    Path mount = Path.of("/sys/fs/cgroup");
    if (Files.isRegularFile(mount.resolve("cgroup.controllers"))) {
      return "v2";
    }
    for (String dir : new String[] {"cpu,cpuacct", "cpu", "cpuacct", "cpuacct,cpu", "memory"}) {
      if (Files.isDirectory(mount.resolve(dir))) {
        return "v1";
      }
    }
    return "none";
  }
}
