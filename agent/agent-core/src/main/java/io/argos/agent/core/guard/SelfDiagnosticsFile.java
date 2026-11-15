/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.core.guard;

import io.argos.agent.core.log.AgentLogger;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;

/**
 * The self-diagnostics snapshot for the test harness (ADR-0010 C-95, 18 §19): with {@code
 * argos.test.selfdiag-file=<file>} (development builds only; a release build drops every {@code
 * test.*} key, T-BLD-010, C-89) the agent rewrites {@code <file>} with {@link
 * SelfDiagnostics#toJson()} every 2 s on {@code argos-watch} and once more at shutdown. Each write
 * goes to {@code <file>.tmp} first and is then moved over {@code <file>} (atomically where the file
 * system can), so a reader never sees a partial snapshot. WO-17 serves the same snapshot through
 * the {@code SELF_DIAG} command.
 *
 * <p>Next to it, {@code <file>.premain-classes} lists once, sorted, the classes the agent class
 * loader defined on the premain thread (ADR-0009 C-79; the premain class budget test of
 * agent-testing reads it). Both files hold names and numbers only, never collected data (SEC-26).
 */
public final class SelfDiagnosticsFile {
  /** Suffix of the premain class list next to the snapshot file. */
  public static final String PREMAIN_CLASSES_SUFFIX = ".premain-classes";

  static final String PREMAIN_HEADER =
      "# Argos premain classes: defined by the agent class loader on the premain thread (C-79)";

  private final File file;
  private final SelfDiagnosticsCollector collector;
  private volatile boolean failureLogged;

  public SelfDiagnosticsFile(File file, SelfDiagnosticsCollector collector) {
    this.file = file;
    this.collector = collector;
  }

  public File file() {
    return file;
  }

  /** Writes a fresh snapshot (agent threads: {@code argos-watch}, the shutdown hook). */
  public synchronized void write() {
    try {
      replace(file, collector.snapshot().toJson() + "\n");
    } catch (Throwable t) {
      failed(t);
    }
  }

  /** Writes {@code <file>.premain-classes}: a header, then {@code names} sorted, one per line. */
  public synchronized void writePremainClasses(String[] names) {
    if (names == null) {
      return;
    }
    try {
      String[] sorted = names.clone();
      Arrays.sort(sorted);
      StringBuilder out = new StringBuilder(64 * (sorted.length + 1));
      out.append(PREMAIN_HEADER).append('\n');
      for (String name : sorted) {
        out.append(name).append('\n');
      }
      replace(new File(file.getPath() + PREMAIN_CLASSES_SUFFIX), out.toString());
    } catch (Throwable t) {
      failed(t);
    }
  }

  private void failed(Throwable t) {
    if (!failureLogged) {
      failureLogged = true;
      AgentLogger.warn(
          "argos.test.selfdiag-file: cannot write {}: {}", file, t.getClass().getName());
    }
  }

  /** Writes {@code content} to {@code target} through a temporary file in the same directory. */
  static void replace(File target, String content) throws IOException {
    Path path = target.toPath();
    Path parent = path.toAbsolutePath().getParent();
    if (parent != null) {
      Files.createDirectories(parent);
    }
    Path temporary = new File(target.getPath() + ".tmp").toPath();
    Files.write(temporary, content.getBytes(StandardCharsets.UTF_8));
    try {
      Files.move(
          temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    } catch (AtomicMoveNotSupportedException e) {
      Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING);
    } catch (IOException e) {
      // Windows refuses to replace a file another process has open: try once more.
      Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING);
    }
  }
}
