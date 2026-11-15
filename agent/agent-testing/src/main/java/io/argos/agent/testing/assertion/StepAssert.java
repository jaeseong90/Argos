/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.testing.assertion;

import io.argos.agent.testing.export.TransactionRecord.StepRecord;
import java.util.function.Predicate;

/** Assertions on one step (18 §5.5); {@link #steps()} and {@link #transaction()} go back. */
public final class StepAssert {
  private final StepsAssert steps;
  private final StepRecord step;

  StepAssert(StepsAssert steps, StepRecord step) {
    this.steps = steps;
    this.step = step;
  }

  public StepAssert hasType(String type) {
    if (!type.equals(step.type())) {
      throw new AssertionError("expected step type <" + type + "> but was " + step);
    }
    return this;
  }

  public StepAssert hasName(String name) {
    if (!name.equals(step.name())) {
      throw new AssertionError("expected step name <" + name + "> but was " + step);
    }
    return this;
  }

  public StepAssert hasTarget(String target) {
    if (!target.equals(step.target())) {
      throw new AssertionError("expected step target <" + target + "> but was " + step);
    }
    return this;
  }

  public StepAssert isError() {
    if (!step.error()) {
      throw new AssertionError("expected an error step but was " + step);
    }
    return this;
  }

  public StepAssert isNotError() {
    if (step.error()) {
      throw new AssertionError("expected no error but was " + step);
    }
    return this;
  }

  /** The step has no parent (top level). */
  public StepAssert isTopLevel() {
    Integer parent = step.parent();
    if (parent != null && parent >= 0) {
      throw new AssertionError(
          "expected a top-level step but its parent is " + parent + ": " + step);
    }
    return this;
  }

  /** The step's parent is the step with sequence number {@code seq}. */
  public StepAssert hasParent(int seq) {
    Integer parent = step.parent();
    if (parent == null || parent != seq) {
      throw new AssertionError("expected parent step " + seq + " but was " + parent + ": " + step);
    }
    return this;
  }

  public ValueAssert<StepAssert> elapsedMs() {
    return field("elapsed_ms");
  }

  public ValueAssert<StepAssert> fetchMs() {
    return field("fetch_ms");
  }

  public ValueAssert<StepAssert> rows() {
    return field("rows");
  }

  /** Consecutive identical steps folded into this one (11 {@code repeat_count}). */
  public ValueAssert<StepAssert> repeatCount() {
    return field("repeat_count");
  }

  /** Any field by its record name; unsupported fields fail. */
  public ValueAssert<StepAssert> field(String name) {
    if (!step.source().supportsStepField(name)) {
      throw Unsupported.error(
          "step field '" + name + "'", step.source().name(), TransactionAssert.arrivesWith(name));
    }
    return new ValueAssert<>(this, name, step.field(name), step.toString());
  }

  /** The step of {@code thread}'s predicate (needs step threads in the source). */
  public TransactionAssert noStepFromThread(Predicate<String> thread) {
    return steps.noStepFromThread(thread);
  }

  /** All steps of the transaction again. */
  public StepsAssert steps() {
    return steps.steps();
  }

  /** Back to the transaction. */
  public TransactionAssert transaction() {
    return steps.transaction();
  }

  public StepRecord actual() {
    return step;
  }
}
