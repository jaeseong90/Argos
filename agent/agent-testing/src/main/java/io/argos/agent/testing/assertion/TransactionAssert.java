/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.testing.assertion;

import io.argos.agent.testing.export.TransactionRecord;
import org.assertj.core.api.AbstractAssert;

/**
 * AssertJ assertions on one exported transaction (WO-02 minimal form of the 18 §5.5 DSL; WO-05 adds
 * collections, steps trees and counters).
 *
 * <pre>
 * assertThatTransaction(tx)
 *     .hasKind("HTTP").hasService("GET /api/orders/{id}").hasStatus(200).isNotError()
 *     .hasElapsed().hasStepCount("SQL", 1);
 * </pre>
 */
public final class TransactionAssert extends AbstractAssert<TransactionAssert, TransactionRecord> {

  private TransactionAssert(TransactionRecord actual) {
    super(actual, TransactionAssert.class);
  }

  public static TransactionAssert assertThatTransaction(TransactionRecord actual) {
    return new TransactionAssert(actual);
  }

  public TransactionAssert hasKind(String kind) {
    isNotNull();
    if (!kind.equals(actual.kind())) {
      failWithMessage("expected kind <%s> but was <%s> in%n%s", kind, actual.kind(), actual);
    }
    return this;
  }

  public TransactionAssert hasService(String service) {
    isNotNull();
    if (!service.equals(actual.service())) {
      failWithMessage(
          "expected service <%s> but was <%s> in%n%s", service, actual.service(), actual);
    }
    return this;
  }

  public TransactionAssert hasStatus(int status) {
    isNotNull();
    if (actual.status() == null || actual.status() != status) {
      failWithMessage("expected status <%s> but was <%s> in%n%s", status, actual.status(), actual);
    }
    return this;
  }

  public TransactionAssert isError() {
    isNotNull();
    if (!actual.error()) {
      failWithMessage("expected an error transaction:%n%s", actual);
    }
    return this;
  }

  public TransactionAssert isNotError() {
    isNotNull();
    if (actual.error()) {
      failWithMessage("expected no error:%n%s", actual);
    }
    return this;
  }

  public TransactionAssert hasHttpMethod(String method) {
    isNotNull();
    if (!method.equals(actual.httpMethod())) {
      failWithMessage(
          "expected http_method <%s> but was <%s> in%n%s", method, actual.httpMethod(), actual);
    }
    return this;
  }

  public TransactionAssert hasPath(String path) {
    isNotNull();
    if (!path.equals(actual.path())) {
      failWithMessage("expected path <%s> but was <%s> in%n%s", path, actual.path(), actual);
    }
    return this;
  }

  /** Elapsed time present and not negative (D-01). */
  public TransactionAssert hasElapsed() {
    isNotNull();
    Double elapsed = actual.elapsedMs();
    if (elapsed == null || elapsed < 0) {
      failWithMessage("expected elapsed_ms >= 0 but was <%s> in%n%s", elapsed, actual);
    }
    return this;
  }

  public TransactionAssert hasElapsedAtLeast(double millis) {
    hasElapsed();
    if (actual.elapsedMs() < millis) {
      failWithMessage(
          "expected elapsed_ms >= %s but was <%s> in%n%s", millis, actual.elapsedMs(), actual);
    }
    return this;
  }

  /** Exactly {@code count} steps of {@code type} ({@code SQL}, {@code HTTP}, ...). */
  public TransactionAssert hasStepCount(String type, int count) {
    isNotNull();
    int steps = actual.steps(type).size();
    if (steps != count) {
      failWithMessage("expected %s %s step(s) but found %s in%n%s", count, type, steps, actual);
    }
    return this;
  }
}
