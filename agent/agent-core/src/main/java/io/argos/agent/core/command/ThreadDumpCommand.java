/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.core.command;

import io.argos.agent.bootstrap.TraceContext;
import io.argos.agent.core.config.AgentConfig;
import io.argos.agent.core.trace.ActiveRegistry;
import io.argos.agent.core.trace.TraceRuntime;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.lang.management.LockInfo;
import java.lang.management.ManagementFactory;
import java.lang.management.MonitorInfo;
import java.lang.management.ThreadInfo;
import java.lang.management.ThreadMXBean;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Pattern;

/**
 * {@code THREAD_DUMP} (10 §12.1, 11 §8.2): every thread as JSON ({@link ThreadDumpJson}, {@code
 * application/json}) on every JDK.
 *
 * <ul>
 *   <li>Platform threads: {@code ThreadMXBean.getThreadInfo(ids, lockedMonitors,
 *       lockedSynchronizers)} (each flag only when the JVM supports it), at most {@value
 *       ThreadDumpJson#MAX_FRAMES} frames; deadlock cycles from {@code findDeadlockedThreads}
 *       ({@code findMonitorDeadlockedThreads} without synchronizer support).
 *   <li>Virtual threads (JDK 21+, 10 U-2): {@code HotSpotDiagnosticMXBean.dumpThreads(path, JSON)},
 *       looked up by reflection once on {@code argos-command} and cached, written to {@code
 *       <log.dir>/td-<command_id>.json} (absolute path, owner-only permissions where the file
 *       system allows), read with {@link JdkThreadDumpReader} and deleted in {@code finally}. The
 *       threads of that dump that are not platform threads are added as {@code virtual=true}.
 *   <li>{@code tx_id}: the transaction each thread runs ({@code ActiveRegistry}).
 * </ul>
 *
 * <p>The result is cut at {@code commands.thread-dump.max-bytes} (default 1 MiB) by whole threads
 * ({@code truncated}). The {@code virtual_threads_source} member says where virtual threads came
 * from: {@code dumpThreads}, {@code unavailable} (JDK before 21) or {@code failed} (the JDK dump
 * could not be written or read; the platform threads are still reported). Thread names and frames
 * are never logged.
 */
final class ThreadDumpCommand implements CommandHandler {
  /** {@code commands.thread-dump.max-bytes} when no configuration is given (1 MiB). */
  static final int DEFAULT_MAX_BYTES = 1024 * 1024;

  static final String SOURCE_DUMP_THREADS = "dumpThreads";
  static final String SOURCE_UNAVAILABLE = "unavailable";
  static final String SOURCE_FAILED = "failed";

  private static final Pattern SAFE_ID = Pattern.compile("[0-9A-Za-z-]{1,64}");
  private static final AtomicLong LOCAL_IDS = new AtomicLong();

  private final CommandEnv env;

  /** The cached JDK dump access; null until the first run. */
  private volatile JdkDump jdkDump;

  ThreadDumpCommand(CommandEnv env) {
    this.env = env;
  }

  @Override
  public CommandOutput run(Map<String, String> args) throws Exception {
    ThreadMXBean bean = ManagementFactory.getThreadMXBean();
    Map<Long, Long> txByThread = txByThread(env.runtime());
    List<ThreadDumpJson.ThreadEntry> threads = new ArrayList<ThreadDumpJson.ThreadEntry>();
    Set<Long> platformIds = new HashSet<Long>();
    for (ThreadInfo info : platformInfos(bean)) {
      platformIds.add(info.getThreadId());
      threads.add(withTx(platformEntry(info), txByThread));
    }
    List<long[]> deadlocks = deadlocks(bean);

    String source;
    boolean truncated = false;
    JdkDump dump = jdkDump();
    if (!dump.available()) {
      source = SOURCE_UNAVAILABLE;
    } else {
      JdkThreadDumpReader.Result read = null;
      try {
        read = dump.dump(dumpFile(args));
      } catch (Exception e) {
        read = null;
      }
      if (read == null) {
        source = SOURCE_FAILED;
      } else {
        source = SOURCE_DUMP_THREADS;
        truncated = read.truncated;
        // Threads started after getAllThreadIds may be platform threads too.
        for (long id : bean.getAllThreadIds()) {
          platformIds.add(id);
        }
        for (JdkThreadDumpReader.DumpedThread t : read.threads) {
          if (isVirtual(t, platformIds)) {
            threads.add(withTx(virtualEntry(t), txByThread));
          }
        }
      }
    }
    ThreadDumpJson.Encoded encoded =
        ThreadDumpJson.encode(threads, deadlocks, source, truncated, maxBytes(env.config()));
    return CommandOutput.json(encoded.utf8, encoded.truncated);
  }

  /** {@code commands.thread-dump.max-bytes}, at most the result cap of 1 MiB. */
  static int maxBytes(AgentConfig config) {
    int max = DEFAULT_MAX_BYTES;
    if (config != null) {
      try {
        max = config.threadDumpMaxBytes();
      } catch (RuntimeException e) {
        max = DEFAULT_MAX_BYTES;
      }
    }
    return Math.max(4096, Math.min(max, DEFAULT_MAX_BYTES));
  }

  /** Thread id to the transaction it runs now. */
  static Map<Long, Long> txByThread(TraceRuntime runtime) {
    final Map<Long, Long> map = new HashMap<Long, Long>();
    if (runtime == null) {
      return map;
    }
    ActiveRegistry registry = runtime.activeRegistry();
    registry.forEach(
        (TraceContext context) -> {
          if (context.isEnded()) {
            return;
          }
          Thread thread = context.thread == null ? null : context.thread.get();
          if (thread != null) {
            map.put(thread.getId(), context.txId);
          }
        });
    return map;
  }

  private static ThreadDumpJson.ThreadEntry withTx(
      ThreadDumpJson.ThreadEntry entry, Map<Long, Long> txByThread) {
    Long tx = txByThread.get(entry.id);
    return tx == null ? entry : entry.withTx(tx);
  }

  /** Every platform thread with locks where supported (null infos of ended threads dropped). */
  static List<ThreadInfo> platformInfos(ThreadMXBean bean) {
    long[] ids = bean.getAllThreadIds();
    boolean monitors = bean.isObjectMonitorUsageSupported();
    boolean synchronizers = bean.isSynchronizerUsageSupported();
    ThreadInfo[] infos;
    if (monitors || synchronizers) {
      infos = bean.getThreadInfo(ids, monitors, synchronizers);
    } else {
      infos = bean.getThreadInfo(ids, ThreadDumpJson.MAX_FRAMES);
    }
    List<ThreadInfo> out = new ArrayList<ThreadInfo>(infos.length);
    for (ThreadInfo info : infos) {
      if (info != null) {
        out.add(info);
      }
    }
    return out;
  }

  /** A platform thread from its {@link ThreadInfo}. */
  static ThreadDumpJson.ThreadEntry platformEntry(ThreadInfo info) {
    StackTraceElement[] stack = info.getStackTrace();
    List<String> frames = frames(stack);
    List<String> locks = new ArrayList<String>();
    MonitorInfo[] monitors = info.getLockedMonitors();
    if (monitors != null) {
      for (MonitorInfo monitor : monitors) {
        if (locks.size() < ThreadDumpJson.MAX_LOCKS) {
          locks.add(lockName(monitor));
        }
      }
    }
    LockInfo[] synchronizers = info.getLockedSynchronizers();
    if (synchronizers != null) {
      for (LockInfo lock : synchronizers) {
        if (locks.size() < ThreadDumpJson.MAX_LOCKS) {
          locks.add(lockName(lock));
        }
      }
    }
    return new ThreadDumpJson.ThreadEntry(
        info.getThreadId(),
        info.getThreadName(),
        info.getThreadState() == null ? null : info.getThreadState().name(),
        false,
        locks,
        info.getLockName(),
        frames,
        false,
        0L);
  }

  /** A virtual thread from the JDK dump. */
  static ThreadDumpJson.ThreadEntry virtualEntry(JdkThreadDumpReader.DumpedThread t) {
    return new ThreadDumpJson.ThreadEntry(
        t.tid, t.name, t.state, true, t.locks, t.waitingOn, t.stack, false, 0L);
  }

  /**
   * True for a thread of the JDK dump that is virtual: marked {@code "virtual":true} (JDK 25), or
   * not a platform thread and either without frames (not yet mounted) or running {@code
   * java.lang.VirtualThread} code (JDK 21 marks nothing).
   */
  static boolean isVirtual(JdkThreadDumpReader.DumpedThread t, Set<Long> platformIds) {
    if (Boolean.TRUE.equals(t.virtual)) {
      return true;
    }
    if (platformIds.contains(t.tid)) {
      return false;
    }
    if (t.stack.isEmpty()) {
      return true;
    }
    for (String frame : t.stack) {
      if (frame.contains("java.lang.VirtualThread.")) {
        return true;
      }
    }
    return false;
  }

  /** At most {@value ThreadDumpJson#MAX_FRAMES} frames in {@code StackTraceElement} form. */
  static List<String> frames(StackTraceElement[] stack) {
    if (stack == null) {
      return new ArrayList<String>(0);
    }
    int n = Math.min(stack.length, ThreadDumpJson.MAX_FRAMES);
    List<String> frames = new ArrayList<String>(n);
    for (int i = 0; i < n; i++) {
      frames.add(stack[i].toString());
    }
    return frames;
  }

  private static String lockName(LockInfo lock) {
    return lock.getClassName() + "@" + Integer.toHexString(lock.getIdentityHashCode());
  }

  /** Deadlock cycles (thread ids), at most {@value ThreadDumpJson#MAX_DEADLOCKS}. */
  static List<long[]> deadlocks(ThreadMXBean bean) {
    long[] ids;
    try {
      ids =
          bean.isSynchronizerUsageSupported()
              ? bean.findDeadlockedThreads()
              : bean.findMonitorDeadlockedThreads();
    } catch (RuntimeException e) {
      ids = null;
    }
    List<long[]> cycles = new ArrayList<long[]>();
    if (ids == null || ids.length == 0) {
      return cycles;
    }
    Map<Long, Long> owner = new HashMap<Long, Long>();
    ThreadInfo[] infos = bean.getThreadInfo(ids, 0);
    for (ThreadInfo info : infos) {
      if (info != null) {
        owner.put(info.getThreadId(), info.getLockOwnerId());
      }
    }
    Set<Long> placed = new HashSet<Long>();
    for (long id : ids) {
      if (placed.contains(id) || cycles.size() >= ThreadDumpJson.MAX_DEADLOCKS) {
        continue;
      }
      List<Long> cycle = new ArrayList<Long>();
      long current = id;
      while (!placed.contains(current)
          && cycle.size() < ThreadDumpJson.MAX_DEADLOCKS
          && owner.containsKey(current)) {
        placed.add(current);
        cycle.add(current);
        current = owner.get(current);
      }
      if (cycle.isEmpty()) {
        placed.add(id);
        cycle.add(id);
      }
      long[] out = new long[cycle.size()];
      for (int i = 0; i < out.length; i++) {
        out[i] = cycle.get(i);
      }
      Arrays.sort(out);
      cycles.add(out);
    }
    return cycles;
  }

  /** {@code <log.dir>/td-<command_id>.json}, absolute. */
  File dumpFile(Map<String, String> args) {
    String id = args == null ? null : args.get(COMMAND_ID_ARG);
    if (id == null || !SAFE_ID.matcher(id).matches()) {
      id = "local-" + System.nanoTime() + "-" + LOCAL_IDS.incrementAndGet();
    }
    File dir = env.logDir();
    if (dir == null) {
      dir = new File(System.getProperty("java.io.tmpdir", "."));
    }
    return new File(dir.getAbsoluteFile(), "td-" + id + ".json");
  }

  private JdkDump jdkDump() {
    JdkDump dump = jdkDump;
    if (dump == null) {
      dump = JdkDump.lookUp();
      jdkDump = dump;
    }
    return dump;
  }

  /** True when the JVM has {@code dumpThreads} (JDK 21+); looks it up on first use. */
  boolean dumpThreadsAvailable() {
    return jdkDump().available();
  }

  /**
   * {@code HotSpotDiagnosticMXBean.dumpThreads(String, ThreadDumpFormat.JSON)}, found by reflection
   * once (JDK 21+). Not available on older JDKs and when the lookup fails.
   */
  static final class JdkDump {
    private final Object bean;
    private final Method method;
    private final Object json;

    private JdkDump(Object bean, Method method, Object json) {
      this.bean = bean;
      this.method = method;
      this.json = json;
    }

    boolean available() {
      return method != null;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    static JdkDump lookUp() {
      if (featureVersion() < 21) {
        return new JdkDump(null, null, null);
      }
      try {
        Class<?> beanType = Class.forName("com.sun.management.HotSpotDiagnosticMXBean");
        Class<?> formatType =
            Class.forName("com.sun.management.HotSpotDiagnosticMXBean$ThreadDumpFormat");
        Object json = Enum.valueOf((Class) formatType, "JSON");
        Method method = beanType.getMethod("dumpThreads", String.class, formatType);
        Object bean =
            ManagementFactory.getPlatformMXBean(
                (Class<? extends java.lang.management.PlatformManagedObject>) beanType);
        if (bean == null) {
          return new JdkDump(null, null, null);
        }
        return new JdkDump(bean, method, json);
      } catch (Throwable t) {
        return new JdkDump(null, null, null);
      }
    }

    /** Writes the dump to {@code file}, reads it and deletes it (also when reading fails). */
    JdkThreadDumpReader.Result dump(File file) throws Exception {
      File dir = file.getParentFile();
      if (dir != null && !dir.isDirectory() && !dir.mkdirs() && !dir.isDirectory()) {
        throw new IOException("thread dump directory cannot be created");
      }
      try {
        Files.deleteIfExists(file.toPath());
        method.invoke(bean, file.getAbsolutePath(), json);
        ownerOnly(file);
        try (InputStream input = Files.newInputStream(file.toPath());
            Reader reader =
                new java.io.BufferedReader(
                    new InputStreamReader(input, StandardCharsets.UTF_8), 64 * 1024)) {
          return JdkThreadDumpReader.read(reader);
        }
      } finally {
        try {
          Files.deleteIfExists(file.toPath());
        } catch (IOException | RuntimeException ignored) {
          file.deleteOnExit();
        }
      }
    }

    private static void ownerOnly(File file) {
      try {
        file.setReadable(false, false);
        file.setWritable(false, false);
        file.setReadable(true, true);
        file.setWritable(true, true);
      } catch (RuntimeException ignored) {
        // Best effort: the file is deleted right after reading.
      }
    }
  }

  /** The Java feature version ({@code 8} for 1.8). */
  static int featureVersion() {
    String spec = System.getProperty("java.specification.version", "1.8");
    try {
      if (spec.startsWith("1.")) {
        return Integer.parseInt(spec.substring(2));
      }
      int dot = spec.indexOf('.');
      return Integer.parseInt(dot < 0 ? spec : spec.substring(0, dot));
    } catch (NumberFormatException e) {
      return 8;
    }
  }
}
