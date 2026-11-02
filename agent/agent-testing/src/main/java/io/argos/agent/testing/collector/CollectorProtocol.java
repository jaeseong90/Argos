/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.testing.collector;

import io.argos.agent.testing.export.RecordSource;
import io.argos.agent.testing.export.TransactionRecord;
import io.grpc.BindableService;
import io.grpc.Status;
import java.util.List;
import java.util.Map;

/**
 * The wire seam of the {@link FakeCollector}: everything that depends on generated protobuf types.
 * Tests use only {@link FakeCollector}, {@link FaultInjector}, {@link Collected} and the records of
 * this package, so switching the wire contract touches one implementation of this interface.
 *
 * <p>Today: {@code io.argos.agent.testing.collector.poc.PocProtocol} over the WO-02 transport PoC
 * protocol ({@code test/agent/transport-poc/src/proto/argos/v1}, a snapshot of 11 §4). WO-11 adds
 * the real {@code proto/argos/v1} contract and replaces it with an implementation over those stubs
 * (11 §16 "FakeCollector 계약 테스트").
 */
public interface CollectorProtocol {
  /** Description for reports ({@code "WO-02 PoC proto"}). */
  String name();

  /** The gRPC {@code AgentService} that forwards every call to {@code collector}. */
  BindableService service(FakeCollector collector);

  /** The fields that {@link #transactions} records can carry. */
  RecordSource recordSource();

  /** Transaction batches (with profile batches for steps) as harness records. */
  List<TransactionRecord> transactions(
      List<ReceivedBatch> batches, Map<Long, String> dictionary, Map<Long, SessionInfo> sessions);

  /** SQL statistics of the stat batches, summed per statement. */
  List<Collected.SqlStat> sqlStats(List<ReceivedBatch> batches, Map<Long, String> dictionary);

  /** Metric samples of the metric batches. */
  List<Collected.MetricPoint> metrics(List<ReceivedBatch> batches);

  /** Counter deltas of the stat batches, summed per name. */
  Map<String, Long> counters(List<ReceivedBatch> batches);

  /**
   * The server side of one open {@code Session} stream; implemented by the protocol, driven by the
   * {@link FakeCollector}. Calls are serialized by the collector.
   */
  interface SessionChannel {
    /** Sends {@code ServerHello}. */
    void hello(boolean accepted, long instanceId, String rejectReason);

    /** Sends {@code ConfigPush}. */
    void configPush(String changeId, Map<String, String> values);

    /** Sends {@code Command}. */
    void command(String commandId, String type, Map<String, String> args, int ttlSeconds);

    /** Ends the stream with {@code status}. */
    void close(Status status);
  }
}
