/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.sample.load;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.OptionalLong;

/**
 * Machine-load recorder for runs driven by another tool (run-bench.sh around k6): {@code java -cp
 * load.jar io.argos.sample.load.MachineMonitor --duration 1m30s [--interval 1s] [--pid <app pid>]
 * [--stop-file F] --out machine.json}. Samples the system CPU load for the given time (or until the
 * stop file appears) and, with {@code --pid}, the CPU time the app process used meanwhile; writes
 * JSON. {@code --duration 0} takes one sample over one interval (the load just before a run).
 */
public final class MachineMonitor {

  private static final long POLL_MILLIS = 200;
  private static final int TIMELINE_LIMIT = 7200;

  private MachineMonitor() {}

  public static void main(String[] args) throws InterruptedException, IOException {
    Duration duration = null;
    Duration interval = Duration.ofSeconds(1);
    OptionalLong pid = OptionalLong.empty();
    Path out = null;
    Path stopFile = null;
    String label = "";
    for (int i = 0; i < args.length; i += 2) {
      String value = i + 1 < args.length ? args[i + 1] : null;
      if (value == null) {
        usage(args[i] + " needs a value");
        return;
      }
      switch (args[i]) {
        case "--duration" -> duration = Durations.parse(value);
        case "--interval" -> interval = Durations.parse(value);
        case "--pid" -> pid = OptionalLong.of(Long.parseLong(value));
        case "--out" -> out = Path.of(value);
        case "--stop-file" -> stopFile = Path.of(value);
        case "--label" -> label = value;
        default -> {
          usage("unknown option " + args[i]);
          return;
        }
      }
    }
    if (duration == null || out == null || interval.isZero() || interval.isNegative()) {
      usage("--duration and --out are required, --interval must be positive");
      return;
    }
    String startedAt = Instant.now().toString();
    Map<String, Object> machine;
    // A per-interval timeline (up to 2 h at 1 s) shows load spikes during the run.
    try (MachineSampler sampler = MachineSampler.start(interval, pid, TIMELINE_LIMIT)) {
      long waitMillis = Math.max(duration.toMillis(), interval.toMillis() + 100);
      long deadline = System.nanoTime() + waitMillis * 1_000_000L;
      while (System.nanoTime() < deadline && (stopFile == null || !Files.exists(stopFile))) {
        Thread.sleep(
            Math.min(POLL_MILLIS, Math.max(1, (deadline - System.nanoTime()) / 1_000_000)));
      }
      machine = sampler.result();
    }
    Map<String, Object> root = new LinkedHashMap<>();
    root.put("label", label);
    root.put("startedAt", startedAt);
    root.put("machine", machine);
    Path parent = out.toAbsolutePath().getParent();
    if (parent != null) {
      Files.createDirectories(parent);
    }
    Files.writeString(out, Json.write(root), StandardCharsets.UTF_8);
  }

  private static void usage(String problem) {
    System.err.println(
        problem
            + "\nusage: MachineMonitor --duration D --out FILE [--interval 1s] [--pid PID]"
            + " [--stop-file FILE] [--label TEXT]");
    System.exit(2);
  }
}
