/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.test.overhead;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 01 §7.3 item 1 and 18 §7.1: machine, OS, JDK, tool versions and input hashes. */
final class Environment {
  private Environment() {}

  /** Facts about this machine; {@code java} (may be null) is the app's JDK. */
  static Map<String, Object> host(Path java) throws InterruptedException {
    Map<String, Object> env = new LinkedHashMap<>();
    env.put(
        "os",
        System.getProperty("os.name")
            + " "
            + System.getProperty("os.version")
            + " "
            + System.getProperty("os.arch"));
    env.put("logicalCpus", Runtime.getRuntime().availableProcessors());
    env.put("memoryMb", MachineMonitor.totalMemoryMb());
    env.put("cpuModel", cpuModel());
    if (Platform.isWindows()) {
      env.put(
          "powerPlan",
          first(Cmd.run(List.of("powercfg", "/getactivescheme"), Duration.ofSeconds(20))));
    } else {
      env.put("cpuGovernor", read("/sys/devices/system/cpu/cpu0/cpufreq/scaling_governor"));
      env.put("transparentHugePages", read("/sys/kernel/mm/transparent_hugepage/enabled"));
      env.put("kernel", first(Cmd.run(List.of("uname", "-r"), Duration.ofSeconds(10))));
    }
    if (java != null) {
      env.put("java", java.toString());
      env.put(
          "javaVersion",
          Cmd.run(List.of(java.toString(), "-version"), Duration.ofMinutes(1)).output().trim());
    }
    env.put(
        "harnessJava",
        System.getProperty("java.vm.name") + " " + System.getProperty("java.runtime.version"));
    return env;
  }

  private static String cpuModel() throws InterruptedException {
    if (Platform.isWindows()) {
      Cmd.Result r =
          Cmd.run(
              List.of(
                  "powershell", "-NoProfile", "-Command", "(Get-CimInstance Win32_Processor).Name"),
              Duration.ofSeconds(30));
      return r.ok() ? r.output().trim() : System.getenv("PROCESSOR_IDENTIFIER");
    }
    for (String line : read("/proc/cpuinfo").lines().toList()) {
      if (line.startsWith("model name")) {
        return line.substring(line.indexOf(':') + 1).trim();
      }
    }
    return "unknown";
  }

  private static String first(Cmd.Result result) {
    String out = result.output().trim();
    int nl = out.indexOf('\n');
    return nl < 0 ? out : out.substring(0, nl).trim();
  }

  private static String read(String path) {
    try {
      Path file = Path.of(path);
      return Files.isReadable(file) ? Files.readString(file, StandardCharsets.UTF_8).trim() : "n/a";
    } catch (IOException e) {
      return "n/a";
    }
  }

  /**
   * The busiest processes of the machine right now (interference evidence on a shared machine):
   * Windows performance counters or {@code ps}. Raw data only; reports name no process.
   */
  static String topProcesses() throws InterruptedException {
    List<String> command =
        Platform.isWindows()
            ? List.of(
                "powershell",
                "-NoProfile",
                "-Command",
                "Get-CimInstance Win32_PerfFormattedData_PerfProc_Process"
                    + " | Where-Object { $_.Name -ne '_Total' -and $_.Name -ne 'Idle' }"
                    + " | Sort-Object PercentProcessorTime -Descending | Select-Object -First 15"
                    + " Name,IDProcess,PercentProcessorTime,"
                    + "@{n='WorkingSetMB';e={[math]::Round($_.WorkingSet/1MB)}}"
                    + " | Format-Table -AutoSize | Out-String -Width 200")
            : List.of("sh", "-c", "ps -eo pid,pcpu,rss,comm --sort=-pcpu | head -n 16");
    Cmd.Result r = Cmd.run(command, Duration.ofMinutes(1));
    return Instant.now() + "\n" + r.output();
  }

  /** {@code k6 version} of the native binary or the image. */
  static String k6Version(String k6, String image) throws InterruptedException {
    Cmd.Result r =
        k6.equals("docker")
            ? Cmd.run(
                List.of("docker", "run", "--rm", "-e", "K6_NO_USAGE_REPORT=true", image, "version"),
                Duration.ofMinutes(5))
            : Cmd.run(List.of(k6, "version"), Duration.ofMinutes(1));
    return first(r) + (k6.equals("docker") ? " (" + image + ")" : " (" + k6 + ")");
  }

  /**
   * Commit and work-tree state of the repository (read-only git: {@code --no-optional-locks} keeps
   * {@code git status} from refreshing the index).
   */
  static Map<String, Object> git(Path repo) throws InterruptedException {
    Map<String, Object> git = new LinkedHashMap<>();
    Cmd.Result head =
        Cmd.run(List.of("git", "-C", repo.toString(), "rev-parse", "HEAD"), Duration.ofSeconds(30));
    git.put("commit", head.ok() ? head.output().trim() : "n/a");
    Cmd.Result status =
        Cmd.run(
            List.of("git", "--no-optional-locks", "-C", repo.toString(), "status", "--porcelain"),
            Duration.ofMinutes(1));
    git.put("uncommittedEntries", status.ok() ? status.output().lines().count() : -1);
    return git;
  }
}
