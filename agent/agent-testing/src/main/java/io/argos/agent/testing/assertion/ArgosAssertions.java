/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.testing.assertion;

import io.argos.agent.testing.collector.Collected;
import io.argos.agent.testing.export.TransactionRecord;
import java.util.function.Predicate;

/**
 * Static entry points of the agent assertion DSL (18 §5.5). Import statically next to AssertJ's
 * {@code Assertions}:
 *
 * <pre>
 * import static io.argos.agent.testing.assertion.ArgosAssertions.assertThat;
 * import static io.argos.agent.testing.assertion.ArgosAssertions.startsWith;
 *
 * assertThat(app.collected())
 *     .transactions().hasSize(1)
 *     .first().hasKind("HTTP").hasService("GET /api/orders/{id}").hasStatus(200)
 *     .steps().ofType("SQL").hasSize(1);
 * </pre>
 */
public final class ArgosAssertions {
  private ArgosAssertions() {}

  public static CollectedAssert assertThat(Collected collected) {
    return new CollectedAssert(collected);
  }

  public static TransactionAssert assertThat(TransactionRecord transaction) {
    return new TransactionAssert(transaction);
  }

  /** A thread-name predicate with a readable description ({@code startsWith("argos-")}). */
  public static Predicate<String> startsWith(String prefix) {
    return new Predicate<>() {
      @Override
      public boolean test(String name) {
        return name.startsWith(prefix);
      }

      @Override
      public String toString() {
        return "startsWith(\"" + prefix + "\")";
      }
    };
  }
}
