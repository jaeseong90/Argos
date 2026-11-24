/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.core.check;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** One line of the {@code --check} table (10 §13.3): item number, check name, status, detail. */
final class CheckRow {
  /** The result of one check; {@link #FAIL} makes the exit code 1. */
  enum Status {
    PASS,
    WARN,
    FAIL,
    /** Not run because an earlier check failed or the input is missing. */
    SKIP,
    /** A fact that is not a problem. */
    INFO
  }

  /** Upper bound of the notes of one row. */
  static final int MAX_NOTES = 20;

  final int item;
  final String name;
  final Status status;
  final String detail;
  final List<String> notes;

  CheckRow(int item, String name, Status status, String detail, List<String> notes) {
    this.item = item;
    this.name = name;
    this.status = status;
    this.detail = detail == null ? "" : detail;
    List<String> kept = new ArrayList<String>();
    if (notes != null) {
      for (String note : notes) {
        if (kept.size() == MAX_NOTES) {
          kept.add("... " + (notes.size() - MAX_NOTES) + " more");
          break;
        }
        kept.add(note);
      }
    }
    this.notes = Collections.unmodifiableList(kept);
  }

  static CheckRow of(int item, String name, Status status, String detail) {
    return new CheckRow(item, name, status, detail, Collections.<String>emptyList());
  }

  @Override
  public String toString() {
    return item + " " + name + " " + status + " " + detail;
  }
}
