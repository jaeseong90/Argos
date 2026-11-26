/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.load;

/**
 * A section of the load report contributed by a {@link LoadPhase} (the attack, session, dictionary
 * and proposal sections of WO-29 step A2).
 *
 * @param title the {@code ##} heading
 * @param markdown the body (Markdown, English)
 */
public record ReportSection(String title, String markdown) {

  /** The planned sections of step A2; a report without one shows a placeholder. */
  public static final java.util.List<String> PLANNED =
      java.util.List.of(
          "Attack scenarios under load (T-SEC-11..15, N-10d)",
          "Session cap memory (C-157 (1))",
          "Dictionary under dict-flood (C-238 (14))",
          "HdrHistogram and pinned virtual threads (12 open items 3 and 4)",
          "Memory budget and ratio proposals (12 §11, 17 §7.3, 17 open item 9)");
}
