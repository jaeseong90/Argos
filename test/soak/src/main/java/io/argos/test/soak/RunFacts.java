/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.test.soak;

import io.argos.test.soak.SoakVerdicts.ConsistencyRun;
import io.argos.test.soak.SoakVerdicts.Firing;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** What the report says about one run besides the samples and verdicts (filled by the runner). */
public final class RunFacts {
  SoakOptions options;
  SoakSchedule schedule;
  Instant started;
  Instant windowStart;
  Instant windowEnd;
  Instant finished;
  boolean aborted;
  String commit = "unknown";
  String machine = "";
  String java = "";
  String stack = "";
  String serverJvm = "";
  String rssSource = "";
  String fdSource = "";

  /** Facts of the end of the run: instance states, agent log counts, drops, stored rows. */
  final Map<String, String> endState = new LinkedHashMap<>();

  /** Load generator totals. */
  long loadSent;

  long loadSegments;

  /** Batch runs started, failed to start, and the last state. */
  long batchRuns;

  long batchRefused;
  String batchLastState = "";

  List<ConsistencyRun> consistency;
  List<Firing> firing;
  List<String> oomFindings;
  List<String> oomUnavailable = List.of();
  List<String> settingsViolations;

  /** Notes with how often each came (a probe that fails every 10 minutes is one line). */
  private final Map<String, Integer> notes = new LinkedHashMap<>();

  final List<String> files = new ArrayList<>();

  /** Adds a note (problems of the run that are not verdicts); a repeated note is counted. */
  synchronized void note(String text) {
    notes.merge(text.replace('\n', ' '), 1, Integer::sum);
  }

  /** The notes in first-seen order, {@code (N times)} appended to a repeated one. */
  synchronized List<String> notes() {
    List<String> out = new ArrayList<>();
    notes.forEach((text, n) -> out.add(n == 1 ? text : text + " (" + n + " times)"));
    return out;
  }
}
