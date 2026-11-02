/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.testing.assertion;

import io.argos.agent.testing.export.TransactionRecord;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Predicate;

/** Assertions on a list of collected transactions (18 §5.5). */
public final class TransactionsAssert {
  private final List<TransactionRecord> transactions;
  private final String source;

  TransactionsAssert(List<TransactionRecord> transactions, String source) {
    this.transactions = List.copyOf(transactions);
    this.source = source;
  }

  public TransactionsAssert hasSize(int size) {
    if (transactions.size() != size) {
      throw new AssertionError(
          "expected "
              + size
              + " transaction(s) but found "
              + transactions.size()
              + " in "
              + source
              + ":\n"
              + lines());
    }
    return this;
  }

  public TransactionsAssert isEmpty() {
    return hasSize(0);
  }

  /** The transactions {@code filter} accepts. */
  public TransactionsAssert filteredOn(Predicate<TransactionRecord> filter) {
    return new TransactionsAssert(transactions.stream().filter(filter).toList(), source);
  }

  /** The transactions of service {@code service}. */
  public TransactionsAssert withService(String service) {
    return filteredOn(tx -> service.equals(tx.service()));
  }

  /** The transactions of request path {@code path}. */
  public TransactionsAssert withPath(String path) {
    return filteredOn(tx -> path.equals(tx.path()));
  }

  public TransactionAssert first() {
    if (transactions.isEmpty()) {
      throw new AssertionError("expected a transaction but found none in " + source);
    }
    return new TransactionAssert(transactions.get(0));
  }

  public TransactionAssert single() {
    hasSize(1);
    return new TransactionAssert(transactions.get(0));
  }

  public TransactionAssert get(int index) {
    if (index >= transactions.size()) {
      throw new AssertionError("no transaction " + index + " in " + source + ":\n" + lines());
    }
    return new TransactionAssert(transactions.get(index));
  }

  public TransactionsAssert allSatisfy(Consumer<TransactionAssert> requirement) {
    for (TransactionRecord tx : transactions) {
      requirement.accept(new TransactionAssert(tx));
    }
    return this;
  }

  /** No transaction was recorded on a thread {@code thread} accepts ({@code argos-*}). */
  public TransactionsAssert noneFromThread(Predicate<String> thread) {
    return allSatisfy(tx -> tx.isNotFromThread(thread));
  }

  /** No transaction has a step from a thread {@code thread} accepts (needs step threads). */
  public TransactionsAssert noStepFromThread(Predicate<String> thread) {
    return allSatisfy(tx -> tx.noStepFromThread(thread));
  }

  /** No transaction's service ends with {@code suffix} (e.g. {@code " /error"}, C-48). */
  public TransactionsAssert noServiceEndingWith(String suffix) {
    for (TransactionRecord tx : transactions) {
      if (tx.service() != null && tx.service().endsWith(suffix)) {
        throw new AssertionError("unexpected service " + tx.service() + " in " + tx);
      }
    }
    return this;
  }

  public List<TransactionRecord> actual() {
    return transactions;
  }

  private String lines() {
    StringBuilder out = new StringBuilder();
    for (TransactionRecord tx : transactions) {
      out.append(tx).append('\n');
    }
    return out.toString();
  }
}
