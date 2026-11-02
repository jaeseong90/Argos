/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.testing.assertion;

import io.argos.agent.testing.export.TransactionRecord.StepRecord;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Predicate;

/** Assertions on the steps of one transaction (18 §5.5); {@link #transaction()} goes back. */
public final class StepsAssert {
  private final TransactionAssert transaction;
  private final List<StepRecord> steps;

  StepsAssert(TransactionAssert transaction, List<StepRecord> steps) {
    this.transaction = transaction;
    this.steps = List.copyOf(steps);
  }

  /** The steps of {@code type} ({@code SQL}, {@code DBC}, {@code HTTP}, {@code ASYNC}, ...). */
  public StepsAssert ofType(String type) {
    List<StepRecord> matching = new ArrayList<>();
    for (StepRecord step : steps) {
      if (type.equals(step.type())) {
        matching.add(step);
      }
    }
    return new StepsAssert(transaction, matching);
  }

  /** The steps {@code filter} accepts. */
  public StepsAssert matching(Predicate<StepRecord> filter) {
    return new StepsAssert(transaction, steps.stream().filter(filter).toList());
  }

  public StepsAssert hasSize(int size) {
    if (steps.size() != size) {
      throw new AssertionError(
          "expected " + size + " step(s) but found " + steps.size() + ": " + steps + context());
    }
    return this;
  }

  public StepsAssert isEmpty() {
    return hasSize(0);
  }

  public StepsAssert isNotEmpty() {
    if (steps.isEmpty()) {
      throw new AssertionError("expected steps but found none" + context());
    }
    return this;
  }

  public StepsAssert allSatisfy(Consumer<StepAssert> requirement) {
    for (StepRecord step : steps) {
      requirement.accept(new StepAssert(this, step));
    }
    return this;
  }

  public StepsAssert anyMatch(Predicate<StepRecord> condition) {
    if (steps.stream().noneMatch(condition)) {
      throw new AssertionError(
          "expected a step matching " + condition + " in " + steps + context());
    }
    return this;
  }

  /** The first step (in sequence order). */
  public StepAssert first() {
    isNotEmpty();
    return new StepAssert(this, steps.get(0));
  }

  /** The only step. */
  public StepAssert single() {
    hasSize(1);
    return new StepAssert(this, steps.get(0));
  }

  /** The {@code index}-th step of this selection. */
  public StepAssert get(int index) {
    if (index >= steps.size()) {
      throw new AssertionError("no step " + index + " in " + steps + context());
    }
    return new StepAssert(this, steps.get(index));
  }

  /** All steps of the transaction again (a new selection). */
  public StepsAssert steps() {
    return transaction.steps();
  }

  /** {@link TransactionAssert#noStepFromThread}. */
  public TransactionAssert noStepFromThread(Predicate<String> thread) {
    return transaction.noStepFromThread(thread);
  }

  /** Back to the transaction. */
  public TransactionAssert transaction() {
    return transaction;
  }

  public List<StepRecord> actual() {
    return steps;
  }

  private String context() {
    return "\nin " + transaction.actualRecord();
  }
}
