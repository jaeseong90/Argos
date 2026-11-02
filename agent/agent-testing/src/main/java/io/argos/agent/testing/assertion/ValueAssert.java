/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.testing.assertion;

import java.util.Objects;

/**
 * Assertions on one field value that tell "missing" from zero (18 §5.5, D-23: a value the agent
 * could not measure is absent, never 0). Every assertion returns the parent assertion so that a
 * chain can continue ({@code .cpuTimeMs().isMissing().steps()...}).
 *
 * @param <P> the parent assertion type
 */
public final class ValueAssert<P> {
  private final P parent;
  private final String field;
  private final Object value;
  private final String context;

  ValueAssert(P parent, String field, Object value, String context) {
    this.parent = parent;
    this.field = field;
    this.value = value;
    this.context = context;
  }

  /** The value is absent (or null): the agent did not record it. */
  public P isMissing() {
    if (value != null) {
      throw new AssertionError(
          "expected " + field + " to be missing but was <" + value + "> in\n" + context);
    }
    return parent;
  }

  /** The value is present (not null). */
  public P isPresent() {
    present();
    return parent;
  }

  /** The value is present and numerically zero (missing is not zero). */
  public P isZero() {
    if (number().doubleValue() != 0) {
      throw new AssertionError(
          "expected " + field + " to be 0 but was <" + value + "> in\n" + context);
    }
    return parent;
  }

  /** The value equals {@code expected} (numbers compare by value). */
  public P isEqualTo(Object expected) {
    present();
    boolean equal =
        (value instanceof Number n && expected instanceof Number e)
            ? n.doubleValue() == e.doubleValue()
            : Objects.equals(value, expected);
    if (!equal) {
      throw new AssertionError(
          "expected " + field + " <" + expected + "> but was <" + value + "> in\n" + context);
    }
    return parent;
  }

  public P isGreaterThan(double bound) {
    if (!(number().doubleValue() > bound)) {
      throw new AssertionError(
          "expected " + field + " > " + bound + " but was <" + value + "> in\n" + context);
    }
    return parent;
  }

  public P isGreaterThanOrEqualTo(double bound) {
    if (!(number().doubleValue() >= bound)) {
      throw new AssertionError(
          "expected " + field + " >= " + bound + " but was <" + value + "> in\n" + context);
    }
    return parent;
  }

  public P isLessThanOrEqualTo(double bound) {
    if (!(number().doubleValue() <= bound)) {
      throw new AssertionError(
          "expected " + field + " <= " + bound + " but was <" + value + "> in\n" + context);
    }
    return parent;
  }

  private void present() {
    if (value == null) {
      throw new AssertionError(
          "expected " + field + " to be present but it is missing in\n" + context);
    }
  }

  private Number number() {
    present();
    if (!(value instanceof Number n)) {
      throw new AssertionError(
          "expected " + field + " to be a number but was <" + value + "> in\n" + context);
    }
    return n;
  }
}
