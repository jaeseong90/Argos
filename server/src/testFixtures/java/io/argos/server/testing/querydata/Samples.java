/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.testing.querydata;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Query parameters taken from the generated data ({@code samples.json} of the generator): stored
 * transactions with their stored {@code ts}, transactions of the clock-skewed (+60 s) instances
 * whose {@code tx_id} carries the agent time (the C-50 fallback cases, ADR-0010 C-129 (6)), late
 * transactions, traces, user ids and request ids.
 *
 * @param transactions ordinary transactions (up to 200)
 * @param skewedInstanceTransactions transactions of the +60 s instances (up to 20)
 * @param lateTransactions transactions that arrived late (up to 20)
 * @param traces traces (up to 100)
 * @param users user ids (up to 50; the generator records them in hashed mode)
 * @param requests request ids (up to 50)
 */
public record Samples(
    List<Transaction> transactions,
    List<Transaction> skewedInstanceTransactions,
    List<Transaction> lateTransactions,
    List<Trace> traces,
    List<Value> users,
    List<Value> requests) {

  public Samples {
    transactions = List.copyOf(transactions);
    skewedInstanceTransactions = List.copyOf(skewedInstanceTransactions);
    lateTransactions = List.copyOf(lateTransactions);
    traces = List.copyOf(traces);
    users = List.copyOf(users);
    requests = List.copyOf(requests);
  }

  /**
   * A stored transaction.
   *
   * @param appId app
   * @param instanceId instance
   * @param txId the 64-bit id ({@code tx_id >> 23} = agent start time since 2026-01-01)
   * @param ts the stored start time (server-corrected for a skewed instance)
   * @param serviceId the service dictionary key
   * @param kind {@code HTTP} or {@code BATCH}
   * @param profileCaptured whether profile_step rows exist
   * @param clamped whether the D-15 guard clamped {@code ts}
   * @param late whether it arrived late
   */
  public record Transaction(
      int appId,
      int instanceId,
      long txId,
      Instant ts,
      long serviceId,
      String kind,
      boolean profileCaptured,
      boolean clamped,
      boolean late) {

    /** The tx_id as the API writes it (unsigned decimal). */
    public String txIdText() {
      return Long.toUnsignedString(txId);
    }
  }

  /**
   * A trace.
   *
   * @param traceIdHex 32 hex digits
   * @param appId the app of its root transaction
   * @param ts the root's start
   * @param argosGenerated whether Argos created the id (Unix seconds in its first 4 bytes)
   * @param transactions transactions in the trace
   */
  public record Trace(
      String traceIdHex, int appId, Instant ts, boolean argosGenerated, int transactions) {}

  /**
   * A user id or request id seen in a transaction.
   *
   * @param appId the app
   * @param value the stored value
   * @param ts the transaction start
   */
  public record Value(int appId, String value, Instant ts) {}

  /** The samples of the generator's {@code Manifest.samples()} map. */
  static Samples of(Map<String, Object> raw) {
    return new Samples(
        transactions(raw.get("transactions")),
        transactions(raw.get("skewedInstanceTransactions")),
        transactions(raw.get("lateTransactions")),
        traces(raw.get("traces")),
        values(raw.get("users"), "user_id"),
        values(raw.get("requests"), "request_id"));
  }

  private static List<Map<?, ?>> maps(Object list) {
    List<Map<?, ?>> out = new ArrayList<>();
    if (list instanceof List<?> l) {
      for (Object o : l) {
        if (o instanceof Map<?, ?> m) {
          out.add(m);
        }
      }
    }
    return out;
  }

  private static List<Transaction> transactions(Object list) {
    List<Transaction> out = new ArrayList<>();
    for (Map<?, ?> m : maps(list)) {
      out.add(
          new Transaction(
              ((Number) m.get("app_id")).intValue(),
              ((Number) m.get("instance_id")).intValue(),
              Long.parseUnsignedLong(String.valueOf(m.get("tx_id"))),
              Instant.parse(String.valueOf(m.get("ts"))),
              Long.parseUnsignedLong(String.valueOf(m.get("service_id"))),
              String.valueOf(m.get("kind")),
              Boolean.TRUE.equals(m.get("profile_captured")),
              Boolean.TRUE.equals(m.get("clamped")),
              Boolean.TRUE.equals(m.get("late"))));
    }
    return out;
  }

  private static List<Trace> traces(Object list) {
    List<Trace> out = new ArrayList<>();
    for (Map<?, ?> m : maps(list)) {
      out.add(
          new Trace(
              String.valueOf(m.get("trace_id")),
              ((Number) m.get("app_id")).intValue(),
              Instant.parse(String.valueOf(m.get("ts"))),
              Boolean.TRUE.equals(m.get("argos_generated")),
              ((Number) m.get("transactions")).intValue()));
    }
    return out;
  }

  private static List<Value> values(Object list, String key) {
    List<Value> out = new ArrayList<>();
    for (Map<?, ?> m : maps(list)) {
      out.add(
          new Value(
              ((Number) m.get("app_id")).intValue(),
              String.valueOf(m.get(key)),
              Instant.parse(String.valueOf(m.get("ts")))));
    }
    return out;
  }
}
