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
 * <p>With {@code debug.transform-timing=true} the time from discovery to completion of every
 * transformed class is logged at INFO (10 §4.3).
 */
final class TransformRecorder extends AgentBuilder.Listener.Adapter {
  static final int MAX_ENTRIES = 100_000;
  static final String HEADER =
      "# Argos transformed classes: <class> <modules> <loader> [retransformed]";

  private static final ThreadLocal<long[]> CURRENT =
      new ThreadLocal<long[]>() {
        @Override
        protected long[] initialValue() {
          // [0] module bits of the type being transformed, [1] discovery time (nanos)
          return new long[2];
        }
      };

  private final File listFile;
  private final boolean timing;
  private final String[] moduleNames;
  private final ConcurrentHashMap<String, String> entries = new ConcurrentHashMap<String, String>();
  private final AtomicLong transformed = new AtomicLong();
  private final AtomicLong version = new AtomicLong();
  private long writtenVersion = -1;

  /**
   * @param listFile {@code debug.transform-list} file, or null to keep counts only
   * @param moduleNames module id to module name (index = module id)
   */
  TransformRecorder(File listFile, boolean timing, String[] moduleNames) {
    this.listFile = listFile;
    this.timing = timing;
    this.moduleNames = moduleNames.clone();
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
    if (listFile != null && entries.size() < MAX_ENTRIES) {
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

  /** Writes the list file when it changed (watch thread and shutdown hook). */
  synchronized void writeIfChanged() {
    if (listFile == null) {
      return;
    }
    long current = version.get();
    if (current == writtenVersion) {
      return;
    }
    List<String> lines = new ArrayList<String>(entries.values());
    Collections.sort(lines);
    StringBuilder text = new StringBuilder(HEADER).append('\n');
    for (String line : lines) {
      text.append(line).append('\n');
    }
    try {
      File parent = listFile.getAbsoluteFile().getParentFile();
      if (parent != null && !parent.isDirectory() && !parent.mkdirs() && !parent.isDirectory()) {
        throw new IOException("cannot create " + parent);
      }
      try (OutputStream out = Files.newOutputStream(listFile.toPath())) {
        out.write(text.toString().getBytes(StandardCharsets.UTF_8));
      }
      writtenVersion = current;
    } catch (IOException e) {
      AgentLogger.warnOnce(
          "transform-list-write",
          "cannot write debug.transform-list {}: {}",
          listFile,
          e.toString());
    }
  }
}
