/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.test.chaos;

import java.util.Locale;

/**
 * Every transaction of a scenario is stored once or counted as dropped (D-13, D-06; T-CH-001 "드롭
 * 카운트 정확", T-CH-003/009 D-13): over the scenario,
 *
 * <pre>
 * stored (count of xlog rows) + agent drops (argos_agent_dropped_total{queue="transaction"})
 *   + server drops (argos_agent_server_dropped_items_total{kind="transactions"}) = transactions
 * </pre>
 *
 * The transactions are what the load generator saw answered; a request without an answer (timeout)
 * or still in flight at the end may also have run in the application, so the identity holds exactly
 * when there are none and otherwise must fall within [answered, answered + without answer + in
 * flight] ({@link LoadSummary}).
 *
 * @param stored xlog rows of the application added during the scenario
 * @param agentDropped agent transaction drops during the scenario
 * @param serverDropped transactions the server reported dropped (quota, full WAL)
 * @param minTransactions answered requests
 * @param maxTransactions answered + without answer + in flight at the end
 */
public record DropAccounting(
    long stored,
    long agentDropped,
    long serverDropped,
    long minTransactions,
    long maxTransactions) {

  /** Stored plus counted drops. */
  public long accounted() {
    return stored + agentDropped + serverDropped;
  }

  /** Whether the transactions are known exactly (no request without an answer). */
  public boolean exact() {
    return minTransactions == maxTransactions;
  }

  /** Whether every transaction is stored or counted, none twice. */
  public boolean holds() {
    long a = accounted();
    return a >= minTransactions && a <= maxTransactions;
  }

  /** The report's text. */
  public String describe() {
    String transactions =
        exact()
            ? Long.toString(minTransactions)
            : String.format(Locale.ROOT, "%d..%d", minTransactions, maxTransactions);
    return String.format(
        Locale.ROOT,
        "stored %d + agent drops %d + server drops %d = %d; transactions %s",
        stored,
        agentDropped,
        serverDropped,
        accounted(),
        transactions);
  }
}
