/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.testing.jvm;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parsed {@code jcmd <pid> VM.native_memory summary} (18 §5.9 "네이티브", N-26): committed KB per
 * category and the thread count.
 *
 * <pre>
 * Total: reserved=2541623KB, committed=212147KB
 * -                 Java Heap (reserved=1048576KB, committed=65536KB)
 * -                    Thread (reserved=34867KB, committed=1335KB)
 *                             (thread #34)
 * -                  Internal (reserved=1166KB, committed=1166KB)
 * -                     Other (reserved=48KB, committed=48KB)
 * </pre>
 *
 * JDK 8 prints the categories without the leading dash; both forms are read. The 18 §5.9 check is
 * {@code (Internal + Other) delta <= 16 MB} and {@code Thread} count delta {@code <= 6} between a
 * baseline and a later summary ({@link #diff}).
 */
public final class NmtSummary {
  private static final Pattern CATEGORY =
      Pattern.compile(
          "^[-\\s]*([A-Za-z][A-Za-z ]*?)\\s+\\(reserved=(\\d+)KB, committed=(\\d+)KB.*$");
  private static final Pattern THREADS = Pattern.compile("^\\s*\\(threads? #(\\d+)\\)\\s*$");

  private final Map<String, Long> committedKb;
  private final long threads;

  private NmtSummary(Map<String, Long> committedKb, long threads) {
    this.committedKb = Map.copyOf(committedKb);
    this.threads = threads;
  }

  /** Parses the summary output; fails when it has no category (NMT off). */
  public static NmtSummary parse(String output) {
    Map<String, Long> committed = new LinkedHashMap<>();
    long threads = -1;
    for (String line : output.split("\\R", -1)) {
      Matcher category = CATEGORY.matcher(line);
      if (category.matches() && !line.startsWith("Total")) {
        committed.put(category.group(1).trim(), Long.parseLong(category.group(3)));
        continue;
      }
      Matcher thread = THREADS.matcher(line);
      if (thread.matches()) {
        threads = Long.parseLong(thread.group(1));
      }
    }
    if (committed.isEmpty()) {
      throw new IllegalArgumentException(
          "no NMT categories (start the JVM with -XX:NativeMemoryTracking=summary):\n" + output);
    }
    return new NmtSummary(committed, threads);
  }

  /** Committed KB of {@code category} ({@code Internal}, {@code Other}, ...), 0 when absent. */
  public long committedKb(String category) {
    return committedKb.getOrDefault(category, 0L);
  }

  public Map<String, Long> committedKb() {
    return committedKb;
  }

  /** Thread count ({@code (thread #n)}), -1 when the output has none. */
  public long threads() {
    return threads;
  }

  /** The 18 §5.9 deltas of {@code later} over this baseline. */
  public Diff diff(NmtSummary later) {
    long internalOther =
        later.committedKb("Internal")
            + later.committedKb("Other")
            - committedKb("Internal")
            - committedKb("Other");
    return new Diff(internalOther, later.threads - threads);
  }

  /**
   * Deltas between two summaries.
   *
   * @param internalOtherKb committed KB delta of {@code Internal + Other}
   * @param threads thread count delta
   */
  public record Diff(long internalOtherKb, long threads) {
    /** 18 §5.9 limits: {@code Internal+Other <= 16 MB}, {@code Thread <= 6}. */
    public boolean withinLimits() {
      return internalOtherKb <= 16 * 1024 && threads <= 6;
    }
  }
}
