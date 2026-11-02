/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.testing.assertion;

/**
 * The failure of an assertion on data that the collected source cannot carry (18 §5.5): it never
 * passes silently. The message names the source and the work order that adds the data.
 */
public final class Unsupported {
  private Unsupported() {}

  /** An {@link AssertionError} "unsupported: ...". */
  public static AssertionError error(String what, String source, String arrives) {
    return new AssertionError(
        "unsupported: "
            + what
            + " is not produced by "
            + source
            + " (arrives with "
            + arrives
            + "); this assertion cannot pass on this source");
  }
}
