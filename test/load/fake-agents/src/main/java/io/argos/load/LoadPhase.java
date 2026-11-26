/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.load;

import java.util.List;

/**
 * A measurement phase plugged into the runner (WO-29 step A2: attacks under load, the idle-session
 * phase, the dictionary flood). The runner calls {@link #underLoad} after the measured window while
 * the generator still sends the steady load, then {@link #afterLoad} once the load stopped and the
 * rows drained, and adds {@link #sections()} and {@link #criteria()} to the report.
 */
interface LoadPhase {

  /** A short name for the log. */
  String name();

  /** Runs while the steady load continues (after the measured window). */
  void underLoad(LoadContext context) throws Exception;

  /** Runs after the load stopped and the end check drained the rows. */
  default void afterLoad(LoadContext context) throws Exception {}

  /** Report sections of this phase. */
  List<ReportSection> sections();

  /** Further verdict rows of this phase. */
  default List<Criterion> criteria() {
    return List.of();
  }
}
