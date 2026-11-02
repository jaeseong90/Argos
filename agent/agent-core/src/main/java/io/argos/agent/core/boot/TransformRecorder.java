/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.core.boot;

import io.argos.agent.core.log.AgentLogger;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.dynamic.DynamicType;
import net.bytebuddy.utility.JavaModule;

/**
 * Records which classes were transformed by which modules (10 §5.2 step 4, N-27 baseline diff,
 * T-AG-005) and attributes transformation events to modules.
 *
 * <p>{@link ModuleTransformer} marks the modules that transform the current type in a thread-local
 * bit set; this listener reads and clears it when ByteBuddy reports the outcome of the type. With
 * {@code debug.transform-list=<file>} the list is written to that file by the watch thread (and at
 * shutdown), one line per class sorted by name:
 *
 * <pre>
 * # Argos transformed classes: &lt;class&gt; &lt;module ids&gt; &lt;loader&gt; [retransformed]
 * example.trivial.TrivialApp$Handler test-probe app
 * java.util.concurrent.ThreadPoolExecutor test-probe-jdk bootstrap retransformed
 * </pre>
 *
 * <p>With {@code debug.transform-log=<file>} (18 §5.6, T-AG-005) the same classes go to that file
 * with a {@code T} prefix, followed by every transformation failure ({@code E}, reported by {@link
 * TransformErrorListener} or by a type matcher that met a {@code ClassCircularityError}) and a
 * summary line with the counts:
 *
 * <pre>
 * # Argos transform log: T &lt;class&gt; &lt;modules&gt; &lt;loader&gt; [retransformed] | E &lt;class&gt; &lt;modules&gt; &lt;loader&gt; &lt;error&gt;
 * T example.trivial.TrivialApp$Handler test-probe app
 * E com.example.Broken jdbc app java.lang.IllegalStateException
 * # summary transformed=2 onError=1 classCircularityError=0
 * </pre>
 *
 * <p>With {@code debug.transform-timing=true} the time from discovery to completion of every
 * transformed class is logged at INFO (10 §4.3).
 */
final class TransformRecorder extends AgentBuilder.Listener.Adapter {
  static final int MAX_ENTRIES = 100_000;
  static final int MAX_ERROR_ENTRIES = 10_000;
  static final String HEADER =
      "# Argos transformed classes: <class> <modules> <loader> [retransformed]";
  static final String LOG_HEADER =
      "# Argos transform log: T <class> <modules> <loader> [retransformed]"
          + " | E <class> <modules> <loader> <error>";

  private static final ThreadLocal<long[]> CURRENT =
      new ThreadLocal<long[]>() {
        @Override
        protected long[] initialValue() {
          // [0] module bits of the type being transformed, [1] discovery time (nanos)
          return new long[2];
        }
      };

  private final File listFile;
  private final File logFile;
  private final boolean timing;
  private final String[] moduleNames;
  private final ConcurrentHashMap<String, String> entries = new ConcurrentHashMap<String, String>();
  private final ConcurrentHashMap<String, String> errorEntries =
      new ConcurrentHashMap<String, String>();
  private final AtomicLong transformed = new AtomicLong();
  private final AtomicLong errors = new AtomicLong();
  private final AtomicLong circularity = new AtomicLong();
  private final AtomicLong version = new AtomicLong();
  private long writtenVersion = -1;

  /**
   * @param listFile {@code debug.transform-list} file, or null to keep counts only
   * @param moduleNames module id to module name (index = module id)
   */
  TransformRecorder(File listFile, boolean timing, String[] moduleNames) {
    this(listFile, null, timing, moduleNames);
  }

  /**
   * @param listFile {@code debug.transform-list} file, or null
   * @param logFile {@code debug.transform-log} file, or null
   * @param moduleNames module id to module name (index = module id)
   */
  TransformRecorder(File listFile, File logFile, boolean timing, String[] moduleNames) {
    this.listFile = listFile;
    this.logFile = logFile;
    this.timing = timing;
    this.moduleNames = moduleNames.clone();
  }

  private boolean recording() {
    return listFile != null || logFile != null;
  }

  /** Called by {@link ModuleTransformer}: {@code moduleId} transforms the current type. */
  static void markModule(int moduleId) {
    CURRENT.get()[0] |= 1L << moduleId;
  }

  /** Module bits of the type being transformed on this thread, cleared. */
  static long takeModules() {
    long[] current = CURRENT.get();
    long bits = current[0];
    current[0] = 0;
    return bits;
  }

  @Override
  public void onDiscovery(
      String typeName, ClassLoader classLoader, JavaModule module, boolean loaded) {
    long[] current = CURRENT.get();
    current[0] = 0;
    if (timing) {
      current[1] = System.nanoTime();
    }
  }

  @Override
  public void onTransformation(
      TypeDescription type,
      ClassLoader classLoader,
      JavaModule module,
      boolean loaded,
      DynamicType dynamicType) {
    long[] current = CURRENT.get();
    long bits = current[0];
    transformed.incrementAndGet();
    if (recording() && entries.size() < MAX_ENTRIES) {
      entries.put(
          type.getName() + " " + loaderName(classLoader),
          type.getName()
              + " "
              + modules(bits)
              + " "
              + loaderName(classLoader)
              + (loaded ? " retransformed" : ""));
      version.incrementAndGet();
    }
    if (timing) {
      AgentLogger.info(
          "transform {} by {} in {} us",
          type.getName(),
          modules(bits),
          TimeUnit.NANOSECONDS.toMicros(System.nanoTime() - current[1]));
    }
  }

  @Override
  public void onComplete(
      String typeName, ClassLoader classLoader, JavaModule module, boolean loaded) {
    CURRENT.get()[0] = 0;
  }

  /**
   * A transformation failure ({@link TransformErrorListener#onError}); {@code moduleBits} are the
   * modules that were transforming the type.
   */
  void recordError(String typeName, ClassLoader classLoader, long moduleBits, Throwable error) {
    errors.incrementAndGet();
    if (error instanceof ClassCircularityError) {
      circularity.incrementAndGet();
    }
    addError(typeName, loaderName(classLoader), modules(moduleBits), error);
  }

  /**
   * A type matcher met an error while resolving {@code typeName} ({@code SafeMatchers}, 10 §14):
   * only a {@code ClassCircularityError} is recorded (other resolution failures of foreign types
   * are normal: missing optional dependencies).
   */
  void recordResolutionError(String typeName, Throwable error) {
    if (error instanceof ClassCircularityError) {
      circularity.incrementAndGet();
      addError(typeName, "-", "-", error);
    }
  }

  private void addError(String typeName, String loader, String modules, Throwable error) {
    if (logFile != null && errorEntries.size() < MAX_ERROR_ENTRIES) {
      String className = error == null ? "-" : error.getClass().getName();
      errorEntries.put(
          typeName + " " + loader + " " + className,
          "E " + typeName + " " + modules + " " + loader + " " + className);
    }
    version.incrementAndGet();
  }

  /** {@code onError} events so far. */
  long errorCount() {
    return errors.get();
  }

  /** {@code ClassCircularityError}s so far (in {@code onError} and in type matchers). */
  long circularityCount() {
    return circularity.get();
  }

  private String modules(long bits) {
    if (bits == 0) {
      return "-";
    }
    StringBuilder out = new StringBuilder();
    for (int id = 0; id < 64; id++) {
      if ((bits & (1L << id)) != 0) {
        if (out.length() > 0) {
          out.append(',');
        }
        out.append(id < moduleNames.length && moduleNames[id] != null ? moduleNames[id] : id);
      }
    }
    return out.toString();
  }

  @SuppressWarnings("ReferenceEquality") // class loader identity
  static String loaderName(ClassLoader loader) {
    if (loader == null) {
      return "bootstrap";
    }
    if (loader == ClassLoader.getSystemClassLoader()) {
      return "app";
    }
    ClassLoader system = ClassLoader.getSystemClassLoader();
    if (system != null && loader == system.getParent()) {
      return "platform";
    }
    return loader.getClass().getName();
  }

  /** Classes transformed so far (all loaders). */
  long transformedCount() {
    return transformed.get();
  }

  /** Writes the list and log files when they changed (watch thread and shutdown hook). */
  synchronized void writeIfChanged() {
    if (!recording()) {
      return;
    }
    long current = version.get();
    if (current == writtenVersion) {
      return;
    }
    List<String> lines = new ArrayList<String>(entries.values());
    Collections.sort(lines);
    boolean written = true;
    if (listFile != null) {
      StringBuilder text = new StringBuilder(HEADER).append('\n');
      for (String line : lines) {
        text.append(line).append('\n');
      }
      written = write(listFile, text, "debug.transform-list");
    }
    if (logFile != null) {
      StringBuilder text = new StringBuilder(LOG_HEADER).append('\n');
      for (String line : lines) {
        text.append("T ").append(line).append('\n');
      }
      List<String> failures = new ArrayList<String>(errorEntries.values());
      Collections.sort(failures);
      for (String line : failures) {
        text.append(line).append('\n');
      }
      text.append("# summary transformed=")
          .append(transformed.get())
          .append(" onError=")
          .append(errors.get())
          .append(" classCircularityError=")
          .append(circularity.get())
          .append('\n');
      written &= write(logFile, text, "debug.transform-log");
    }
    if (written) {
      writtenVersion = current;
    }
  }

  private static boolean write(File file, StringBuilder text, String key) {
    try {
      File parent = file.getAbsoluteFile().getParentFile();
      if (parent != null && !parent.isDirectory() && !parent.mkdirs() && !parent.isDirectory()) {
        throw new IOException("cannot create " + parent);
      }
      try (OutputStream out = Files.newOutputStream(file.toPath())) {
        out.write(text.toString().getBytes(StandardCharsets.UTF_8));
      }
      return true;
    } catch (IOException e) {
      AgentLogger.warnOnce(key + "-write", "cannot write {} {}: {}", key, file, e.toString());
      return false;
    }
  }
}
