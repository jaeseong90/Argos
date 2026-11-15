/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.testing.collector;

import io.argos.proto.v1.ActiveTransactionSnapshot;
import io.argos.proto.v1.DictionaryEntry;
import io.argos.proto.v1.MetricBatch;
import io.argos.proto.v1.ProfileBatch;
import io.argos.proto.v1.StatBatch;
import io.argos.proto.v1.TransactionBatch;
import io.argos.server.ingest.api.DictionaryIntake;
import io.argos.server.ingest.api.IngestPipeline;
import io.argos.server.session.api.SessionContext;
import io.argos.server.wal.api.WalPosition;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Records what the collector hands to the ingest path ({@link IngestPipeline}, {@link
 * DictionaryIntake}) in its tests; decoding and ClickHouse are lane durable-ingest's.
 */
public final class RecordingIngest implements IngestPipeline {

  /**
   * One pipeline call.
   *
   * @param kind {@code transactions}, {@code profiles}, {@code metrics}, {@code stats}, {@code
   *     active}
   * @param context the receipt context
   * @param position the WAL position ({@code null} for {@code active})
   * @param batch the batch as handed over
   */
  public record Call(String kind, SessionContext context, WalPosition position, Object batch) {}

  /**
   * One dictionary message.
   *
   * @param appId the session's app
   * @param instanceId the session's instance
   * @param entries the entries handed over
   */
  public record Dictionary(int appId, int instanceId, List<DictionaryEntry> entries) {}

  private final List<Call> calls = new CopyOnWriteArrayList<>();
  private final List<Dictionary> dictionary = new CopyOnWriteArrayList<>();

  @Override
  public void onTransactions(SessionContext s, WalPosition p, TransactionBatch b) {
    calls.add(new Call("transactions", s, p, b));
  }

  @Override
  public void onProfiles(SessionContext s, WalPosition p, ProfileBatch b) {
    calls.add(new Call("profiles", s, p, b));
  }

  @Override
  public void onMetrics(SessionContext s, WalPosition p, MetricBatch b) {
    calls.add(new Call("metrics", s, p, b));
  }

  @Override
  public void onStats(SessionContext s, WalPosition p, StatBatch b) {
    calls.add(new Call("stats", s, p, b));
  }

  @Override
  public void onActive(SessionContext s, ActiveTransactionSnapshot snapshot) {
    calls.add(new Call("active", s, null, snapshot));
  }

  /**
   * The recording {@link DictionaryIntake} (a separate object, so that a Spring context sees one
   * bean per port).
   */
  public DictionaryIntake dictionaryIntake() {
    return (appId, instanceId, entries) ->
        dictionary.add(new Dictionary(appId, instanceId, List.copyOf(entries)));
  }

  /** Every pipeline call, in order. */
  public List<Call> calls() {
    return List.copyOf(calls);
  }

  /** Pipeline calls of {@code kind}. */
  public List<Call> calls(String kind) {
    return calls.stream().filter(c -> c.kind().equals(kind)).toList();
  }

  /** Every dictionary message, in order. */
  public List<Dictionary> dictionary() {
    return List.copyOf(dictionary);
  }
}
