/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.core.trace;

import java.util.ArrayList;
import java.util.List;

/**
 * The application frame of an error (10 §9.1 {@code topAppFrameId}, ADR-0010 C-237 (6), C-131;
 * WO-19): the first frame of the error's stack whose class lies in one of the application packages
 * ({@code instrumentation.app-packages}, configured or inferred). Its FRAME text is {@code
 * class.method(file)} without the line number ({@link TracerImpl#frameText(StackTraceElement,
 * boolean)}), so that the error group fingerprint survives a redeploy that only moves lines.
 *
 * <p>Loaded at the first error transaction or the first package installation, never on the premain
 * thread. Stateless; the prefixes are an immutable array the tracer publishes through a volatile
 * reference.
 */
final class ErrorFrames {
  /** At most this many packages are matched (the {@code instrumentation.app-packages} list cap). */
  static final int MAX_PACKAGES = 32;

  /** The agent's own classes are never an application frame, whatever the packages say. */
  private static final String AGENT_PREFIX = "io.argos.agent.";

  private static final String[] NONE = new String[0];

  private ErrorFrames() {}

  /**
   * The class-name prefixes ({@code "com.example."}) of {@code packages}: blank items are skipped,
   * a trailing {@code ".*"}, {@code "*"} or {@code "."} is dropped, at most {@value #MAX_PACKAGES}.
   * Empty when there are none (no application frame is ever found).
   */
  static String[] prefixes(List<String> packages) {
    if (packages == null || packages.isEmpty()) {
      return NONE;
    }
    List<String> out = new ArrayList<String>(Math.min(packages.size(), MAX_PACKAGES));
    for (String item : packages) {
      if (out.size() >= MAX_PACKAGES) {
        break;
      }
      if (item == null) {
        continue;
      }
      String pkg = item.trim();
      while (pkg.endsWith("*") || pkg.endsWith(".")) {
        pkg = pkg.substring(0, pkg.length() - 1);
      }
      if (!pkg.isEmpty()) {
        out.add(pkg + ".");
      }
    }
    return out.toArray(new String[0]);
  }

  /**
   * The index of the first frame of {@code frames} (top of the stack first) whose class starts with
   * one of {@code prefixes}, or -1 when none does (no packages, no matching frame).
   */
  static int topAppFrame(StackTraceElement[] frames, String[] prefixes) {
    if (frames == null || prefixes == null || prefixes.length == 0) {
      return -1;
    }
    for (int i = 0; i < frames.length; i++) {
      StackTraceElement frame = frames[i];
      if (frame == null) {
        continue;
      }
      String className = frame.getClassName();
      if (className == null || className.startsWith(AGENT_PREFIX)) {
        continue;
      }
      for (String prefix : prefixes) {
        if (className.startsWith(prefix)) {
          return i;
        }
      }
    }
    return -1;
  }
}
