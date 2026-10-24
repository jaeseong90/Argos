/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.sample.smoke;

import java.lang.management.ManagementFactory;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Machine conditions recorded next to every startup measurement (the machine is shared: a startup
 * time without the load at that moment is not comparable).
 */
final class MachineLoad {

  private MachineLoad() {}

  static Map<String, Object> sample() throws InterruptedException {
    Map<String, Object> machine = new LinkedHashMap<>();
    machine.put("os", System.getProperty("os.name") + " " + System.getProperty("os.version"));
    machine.put("availableProcessors", Runtime.getRuntime().availableProcessors());
    if (ManagementFactory.getOperatingSystemMXBean()
        instanceof com.sun.management.OperatingSystemMXBean os) {
      os.getCpuLoad(); // the first reading primes the measurement
      Thread.sleep(500);
      double load = os.getCpuLoad();
      machine.put("systemCpuLoadPercent", load < 0 ? null : Math.round(load * 1000) / 10.0);
      machine.put("totalMemoryMb", os.getTotalMemorySize() / (1024 * 1024));
      machine.put("freeMemoryMb", os.getFreeMemorySize() / (1024 * 1024));
    }
    return machine;
  }
}
