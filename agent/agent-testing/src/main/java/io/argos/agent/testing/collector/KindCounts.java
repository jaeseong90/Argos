/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.testing.collector;

/**
 * Running totals of one {@link BatchKind} (kept even when the collector does not retain batches,
 * {@link FakeCollector.Builder#retainBatches(boolean)}; the standalone collector prints them).
 *
 * @param batches calls received (every outcome)
 * @param items items of those calls (records, profiles, samples, stat rows, active items)
 * @param bytes serialized bytes of those calls
 * @param acked calls written and answered {@code ACK_OK} or {@code ACK_PARTIAL_DROP}
 * @param ackedItems items of those calls
 * @param duplicates calls answered {@code ACK_DUPLICATE}
 * @param failed calls answered with an error status (faults, token, session); a call the WAL-full
 *     fault acknowledged without writing it is in none of the counters but {@code batches}, {@code
 *     items} and {@code bytes}
 */
public record KindCounts(
    long batches,
    long items,
    long bytes,
    long acked,
    long ackedItems,
    long duplicates,
    long failed) {
  /** No calls. */
  public static final KindCounts ZERO = new KindCounts(0, 0, 0, 0, 0, 0, 0);

  KindCounts plus(int itemCount, int byteCount, String outcome) {
    boolean ack = ReceivedBatch.ACKED.equals(outcome);
    boolean duplicate = ReceivedBatch.DUPLICATE.equals(outcome);
    boolean noError =
        ReceivedBatch.HUNG.equals(outcome) || ReceivedBatch.NOT_WRITTEN.equals(outcome);
    return new KindCounts(
        batches + 1,
        items + itemCount,
        bytes + byteCount,
        acked + (ack ? 1 : 0),
        ackedItems + (ack ? itemCount : 0),
        duplicates + (duplicate ? 1 : 0),
        failed + (ack || duplicate || noError ? 0 : 1));
  }
}
