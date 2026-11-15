/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.testing.baseline;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * Premain class budgets (ADR-0009 C-79, N-30): {@code
 * agent/agent-testing/baselines/premain-classes/<run>-<jdk>.txt} lists, sorted, the classes the
 * agent class loader defined on the premain thread in a reference run (the agent writes them next
 * to its self-diagnostics file, {@code <selfdiag file>.premain-classes}). The number of listed
 * classes is the budget:
 *
 * <ul>
 *   <li>a run that defines more classes than the budget fails and names the classes the file does
 *       not list (a start path that got heavier is reviewed, not absorbed);
 *   <li>a class of the transport ({@link #FORBIDDEN_PREFIXES}: shaded gRPC, Netty, protobuf and
 *       their companion libraries) on the premain thread always fails: the transport starts on
 *       agent threads (10 §4.3 P7);
 *   <li>fewer classes than the budget pass with a {@code [premain-budget]} note to re-record;
 *   <li>with {@code -Pagent.baseline.record=true} the file is written instead (reviewed in the PR
 *       like the transform baselines).
 * </ul>
 */
public final class PremainClassBudget {
  /**
   * Shaded transport packages that must never load on the premain thread: every package of {@code
   * AgentShading.RELOCATED_PACKAGES} (build-logic) that the gRPC transport brings, below {@code
   * io.argos.agent.shaded.}. Keep this list in step with that relocation list when a transport
   * dependency is added or removed. Not listed: ByteBuddy (premain builds the transformer with it),
   * JCTools (premain may create the agent queues) and the annotation-only packages (errorprone,
   * j2objc, checkerframework, animal_sniffer), which have no classes loaded at run time.
   */
  public static final List<String> FORBIDDEN_PREFIXES =
      List.of(
          "io.argos.agent.shaded.io.grpc.",
          "io.argos.agent.shaded.io.netty.",
          "io.argos.agent.shaded.io.perfmark.",
          "io.argos.agent.shaded.com.google.protobuf.",
          "io.argos.agent.shaded.com.google.common.",
          "io.argos.agent.shaded.com.google.thirdparty.",
          "io.argos.agent.shaded.com.google.gson.",
          "io.argos.agent.shaded.com.google.rpc.",
          "io.argos.agent.shaded.com.google.type.",
          "io.argos.agent.shaded.com.google.longrunning.",
          "io.argos.agent.shaded.com.google.api.",
          // The argos.v1 protocol classes of :proto, relocated in the agent jar (ADR-0010 C-104).
          "io.argos.agent.shaded.io.argos.proto.");

  static final String HEADER =
      "# Premain class budget (ADR-0009 C-79, N-30): classes the agent class loader defines on the"
          + " premain thread";

  /** Outcome of a comparison. */
  public record Result(int budget, int actual, List<String> added, List<String> forbidden) {
    public Result {
      added = List.copyOf(added);
      forbidden = List.copyOf(forbidden);
    }

    /** True when the run stays within the budget and loads no transport class. */
    public boolean passed() {
      return actual <= budget && forbidden.isEmpty();
    }

    /** One line for the test output. */
    public String describe() {
      return actual
          + " premain classes (budget "
          + budget
          + ")"
          + (added.isEmpty() ? "" : ", not in the budget file: " + added)
          + (forbidden.isEmpty() ? "" : ", transport classes on the premain thread: " + forbidden);
    }
  }

  private PremainClassBudget() {}

  /** {@code <directory of the transform baselines>/../premain-classes}. */
  public static Path directory() {
    return TransformBaseline.directory().resolveSibling("premain-classes");
  }

  /** {@code <run>-<jdk>.txt}. */
  public static String fileName(String run, int jdk) {
    return run + "-" + jdk + ".txt";
  }

  /** The transport classes of {@code classes} (always a failure on the premain thread). */
  public static List<String> forbidden(List<String> classes) {
    List<String> found = new ArrayList<>();
    for (String name : classes) {
      for (String prefix : FORBIDDEN_PREFIXES) {
        if (name.startsWith(prefix)) {
          found.add(name);
        }
      }
    }
    return found;
  }

  /** Writes {@code classes} (sorted, without duplicates) as the budget file. */
  public static void record(Path file, String title, List<String> classes) {
    StringBuilder text = new StringBuilder().append(HEADER).append('\n');
    text.append("# ").append(title).append('\n');
    for (String name : new TreeSet<>(classes)) {
      text.append(name).append('\n');
    }
    try {
      Files.createDirectories(file.getParent());
      Files.writeString(file, text, StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  /** The class names of a budget file (comments and blank lines skipped). */
  public static List<String> read(Path file) {
    try {
      return Files.readAllLines(file, StandardCharsets.UTF_8).stream()
          .map(String::strip)
          .filter(line -> !line.isEmpty() && !line.startsWith("#"))
          .toList();
    } catch (IOException e) {
      throw new UncheckedIOException("cannot read the premain class budget " + file, e);
    }
  }

  /** Compares {@code classes} of a run with the budget file {@code file}. */
  public static Result compare(Path file, List<String> classes) {
    List<String> listed = read(file);
    Set<String> known = new TreeSet<>(listed);
    Set<String> unique = new TreeSet<>(classes);
    List<String> added = unique.stream().filter(name -> !known.contains(name)).toList();
    return new Result(listed.size(), unique.size(), added, forbidden(classes));
  }
}
