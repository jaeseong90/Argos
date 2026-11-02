/* SPDX-License-Identifier: Apache-2.0 */
package example.transportpoc;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.lang.management.BufferPoolMXBean;
import java.lang.management.ClassLoadingMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryPoolMXBean;
import java.lang.management.RuntimeMXBean;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.TimeUnit;

/**
 * Host application of the P-3 transport comparison (WO-02): it does nothing for {@code duration-ms}
 * while the agent's sender works, then writes what the JVM reports about itself and exits. It
 * prints nothing, so any stdout/stderr output of a run comes from the agent or the JVM.
 *
 * <p>Reported (properties file {@code args[0]}): {@code jvm.start.ms}, {@code main.uptime.ms}
 * (premain included), {@code metaspace.used}/{@code .committed}, {@code ccs.used}, {@code
 * classes.loaded}/{@code .total}, {@code direct.used}/{@code .count}/{@code .max} (max sampled
 * every 100 ms), {@code threads.count} and {@code threads.names} (sorted, {@code |}-separated), and
 * the Netty property names of 10 §10.3 in original and relocated form (ADR-0003 C-23 evidence), and
 * the {@code java.util.logging} loggers named {@code io.argos.*} ({@code jul.agent.loggers}).
 *
 * <p>Usage: {@code HostApp <out.properties> <duration-ms> [<stop file>]}: it ends after {@code
 * duration-ms} or as soon as the stop file exists.
 */
public final class HostApp {
  private static final String RELOCATED = "io.argos.agent.shaded.";
  private static final String[] NETTY_PROPERTIES = {
    "io.netty.noUnsafe", "io.netty.allocator.type", "io.netty.maxDirectMemory"
  };

  private HostApp() {}

  public static void main(String[] args) throws Exception {
    RuntimeMXBean runtime = ManagementFactory.getRuntimeMXBean();
    long uptimeAtMain = runtime.getUptime();
    long jvmStart = runtime.getStartTime();
    File out = new File(args[0]);
    long duration = Long.parseLong(args[1]);
    File stop = args.length > 2 ? new File(args[2]) : null;

    BufferPoolMXBean direct = null;
    for (BufferPoolMXBean pool : ManagementFactory.getPlatformMXBeans(BufferPoolMXBean.class)) {
      if (pool.getName().equals("direct")) {
        direct = pool;
      }
    }
    long maxDirect = 0;
    long maxDirectCount = 0;
    long end = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(duration);
    while (System.nanoTime() - end < 0 && (stop == null || !stop.exists())) {
      if (direct != null) {
        maxDirect = Math.max(maxDirect, direct.getMemoryUsed());
        maxDirectCount = Math.max(maxDirectCount, direct.getCount());
      }
      Thread.sleep(100);
    }

    Properties result = new Properties();
    result.setProperty("jvm.start.ms", Long.toString(jvmStart));
    result.setProperty("main.uptime.ms", Long.toString(uptimeAtMain));
    result.setProperty("java.version", System.getProperty("java.version"));
    for (MemoryPoolMXBean pool : ManagementFactory.getMemoryPoolMXBeans()) {
      if (pool.getName().equals("Metaspace")) {
        result.setProperty("metaspace.used", Long.toString(pool.getUsage().getUsed()));
        result.setProperty("metaspace.committed", Long.toString(pool.getUsage().getCommitted()));
      } else if (pool.getName().equals("Compressed Class Space")) {
        result.setProperty("ccs.used", Long.toString(pool.getUsage().getUsed()));
      }
    }
    ClassLoadingMXBean classes = ManagementFactory.getClassLoadingMXBean();
    result.setProperty("classes.loaded", Integer.toString(classes.getLoadedClassCount()));
    result.setProperty("classes.total", Long.toString(classes.getTotalLoadedClassCount()));
    if (direct != null) {
      result.setProperty("direct.used", Long.toString(direct.getMemoryUsed()));
      result.setProperty("direct.count", Long.toString(direct.getCount()));
      result.setProperty("direct.max", Long.toString(maxDirect));
      result.setProperty("direct.count.max", Long.toString(maxDirectCount));
    }
    List<String> names = new ArrayList<String>();
    for (Thread thread : Thread.getAllStackTraces().keySet()) {
      names.add(thread.getName() + (thread.isDaemon() ? "(d)" : ""));
    }
    Collections.sort(names);
    result.setProperty("threads.count", Integer.toString(names.size()));
    StringBuilder joined = new StringBuilder();
    for (String name : names) {
      joined.append(joined.length() == 0 ? "" : "|").append(name);
    }
    result.setProperty("threads.names", joined.toString());
    // java.util.logging loggers that agent code created (shaded libraries log through JUL with
    // their relocated class names). Asked at the very end, after the agent has done its work.
    List<String> julLoggers = new ArrayList<String>();
    for (java.util.Enumeration<String> e =
            java.util.logging.LogManager.getLogManager().getLoggerNames();
        e.hasMoreElements(); ) {
      String logger = e.nextElement();
      if (logger.startsWith("io.argos.")) {
        julLoggers.add(logger);
      }
    }
    Collections.sort(julLoggers);
    result.setProperty("jul.agent.loggers", Integer.toString(julLoggers.size()));
    StringBuilder loggerNames = new StringBuilder();
    for (String logger : julLoggers) {
      loggerNames.append(loggerNames.length() == 0 ? "" : "|").append(logger);
    }
    result.setProperty("jul.agent.logger.names", loggerNames.toString());
    for (String name : NETTY_PROPERTIES) {
      result.setProperty("prop." + name, String.valueOf(System.getProperty(name)));
      result.setProperty(
          "prop." + RELOCATED + name, String.valueOf(System.getProperty(RELOCATED + name)));
    }
    File tmp = new File(out.getPath() + ".tmp");
    try (OutputStream stream = new FileOutputStream(tmp)) {
      result.store(stream, "P-3 host application");
    }
    if (!tmp.renameTo(out)) {
      throw new IllegalStateException("cannot write " + out);
    }
    System.exit(0);
  }
}
