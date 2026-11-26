/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.test.chaos;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * What a scenario has measured so far, shared by {@link ScenarioDriver} and its {@link Fault}: the
 * stack, the phase times, the counts before and after, the load generator's result, and values the
 * fault records for its own checks ({@link #put}).
 */
public final class ScenarioContext {

  private final String id;
  private final ChaosStack stack;
  private final ChaosOptions options;
  private final Duration faultLength;
  private final Map<String, Object> values = new LinkedHashMap<>();
  private final List<String> notes = new ArrayList<>();

  Instant start;
  Instant faultStart;
  Instant faultEnd;
  Instant end;
  long xlogBefore;
  long xlogAfter;
  AgentCounts countsBefore;
  AgentCounts countsAfter;
  LoadSummary load;
  List<LoadInterval> intervals = List.of();

  ScenarioContext(String id, ChaosStack stack, ChaosOptions options, Duration faultLength) {
    this.id = id;
    this.stack = stack;
    this.options = options;
    this.faultLength = faultLength;
  }

  /** The scenario's test ID. */
  public String id() {
    return id;
  }

  /** The stack. */
  public ChaosStack stack() {
    return stack;
  }

  /** The run's options. */
  public ChaosOptions options() {
    return options;
  }

  /** How long the fault lasts by the profile. */
  public Duration faultLength() {
    return faultLength;
  }

  /** When the scenario started (before the load). */
  public Instant start() {
    return start;
  }

  /** When the fault was injected. */
  public Instant faultStart() {
    return faultStart;
  }

  /** When the fault was removed. */
  public Instant faultEnd() {
    return faultEnd;
  }

  /** When the scenario's measurements ended. */
  public Instant end() {
    return end;
  }

  /** The agent counters before the load. */
  public AgentCounts countsBefore() {
    return countsBefore;
  }

  /** The agent counters after the drain. */
  public AgentCounts countsAfter() {
    return countsAfter;
  }

  /** The xlog rows of the application before the load. */
  public long xlogBefore() {
    return xlogBefore;
  }

  /** The xlog rows of the application after the drain. */
  public long xlogAfter() {
    return xlogAfter;
  }

  /** The load generator's summary. */
  public LoadSummary load() {
    return load;
  }

  /** The load generator's intervals. */
  public List<LoadInterval> intervals() {
    return intervals;
  }

  /** Records a value for the fault's checks. */
  public void put(String key, Object value) {
    values.put(key, value);
  }

  /** A recorded value, or null. */
  public Object get(String key) {
    return values.get(key);
  }

  /** Adds a note to the scenario's report section. */
  public void note(String text) {
    notes.add(text);
  }

  /** The notes so far. */
  public List<String> notes() {
    return List.copyOf(notes);
  }
}
