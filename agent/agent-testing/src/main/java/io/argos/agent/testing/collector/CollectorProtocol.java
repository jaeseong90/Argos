/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.testing.collector;

import com.google.protobuf.ByteString;
import com.google.protobuf.Message;
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
 * <p>The implementation is {@link io.argos.agent.testing.collector.v1.V1Protocol} over {@code
 * :proto} ({@code proto/src/main/proto/argos/v1}, ADR-0010 C-104). It decodes calls into the
 * protocol-neutral records below, the {@link FakeCollector} applies the 11 §16 contract (session
 * registry, deduplication, clock, faults) and the implementation encodes the answers.
 */
public interface CollectorProtocol {
  /** Description for reports ({@code "argos.v1 (:proto)"}). */
  String name();

  /**
   * The gRPC {@code AgentService} (with its metadata interceptor) that drives {@code collector}.
   */
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

  /** External-call statistics of the stat batches, summed per host and kind (WO-16). */
  List<Collected.ExtStat> extStats(List<ReceivedBatch> batches, Map<Long, String> dictionary);

  /** Every written stat batch as one window, in arrival order (WO-16). */
  List<Collected.StatWindow> statWindows(List<ReceivedBatch> batches, Map<Long, String> dictionary);

  /** Every received active snapshot, in arrival order (WO-16). */
  List<Collected.ActiveSnapshot> activeSnapshots(
      List<ReceivedBatch> batches, Map<Long, String> dictionary);

  /**
   * False while a transaction record is still waiting for data that travels on its own: the steps
   * of a sampled transaction (a separate profile batch, 11 §3) or a dictionary text (11 §7: up to
   * 100 ms after the data batch). {@link Collected#awaitTransactions} gives such a record a moment
   * to complete.
   */
  boolean complete(TransactionRecord record);

  /**
   * The metadata of one call (11 §2).
   *
   * @param token the {@code authorization: Bearer} token, or null when absent or another scheme
   * @param instanceId the {@code argos-instance-id} value, or null
   * @param sessionId the {@code argos-session-id} value, or null
   * @param plaintext the connection has no TLS session
   */
  record CallMetadata(
      ReceivedToken token, String instanceId, String sessionId, boolean plaintext) {}

  /**
   * One unary data call, decoded.
   *
   * @param kind the RPC
   * @param instanceId {@code BatchHeader.instance_id}
   * @param agentEpoch {@code BatchHeader.agent_epoch}
   * @param seq {@code BatchHeader.seq}
   * @param sentAtMs {@code BatchHeader.sent_at_ms}
   * @param body the request
   * @param bytes serialized size
   * @param items records, profiles, samples, stat rows or active items of the batch
   * @param metadata the call's metadata
   */
  record UnaryCall(
      BatchKind kind,
      long instanceId,
      long agentEpoch,
      long seq,
      long sentAtMs,
      Message body,
      int bytes,
      int items,
      CallMetadata metadata) {}

  /**
   * {@code Throttle} fields; null leaves a field absent (11 §4.4).
   *
   * @param profileNormalRate {@code profile_normal_rate}
   * @param sendBackoffMs {@code send_backoff_ms}
   */
  record ThrottleHint(Float profileNormalRate, Integer sendBackoffMs) {}

  /**
   * An {@code Ack}.
   *
   * @param status {@code ACK_OK}, {@code ACK_DUPLICATE} or {@code ACK_PARTIAL_DROP}
   * @param lastSeq {@code Ack.last_seq}
   * @param droppedItems {@code Ack.dropped_items}
   * @param throttle {@code Ack.throttle}, or null
   */
  record AckAnswer(String status, long lastSeq, int droppedItems, ThrottleHint throttle) {}

  /** The answer to one unary call. Exactly one method is called, at most once. */
  interface UnaryReply {
    void ack(AckAnswer answer);

    /** Ends the call with {@code status} and these trailers (11 §10 trailer keys). */
    void fail(Status status, Map<String, String> trailers);
  }

  /**
   * The fields of an {@code AgentHello} the collector decides on (the whole message is kept in
   * {@link SessionInfo#hello()}).
   */
  record HelloFields(
      String appName,
      String env,
      String instanceName,
      String appVersion,
      String agentVersion,
      int protocolVersion,
      long agentEpoch,
      long sentAtMs,
      boolean plaintextDeclared) {}

  /**
   * A {@code ServerHello}.
   *
   * @param accepted {@code accepted}
   * @param instanceId {@code instance_id} (accepted only)
   * @param sessionId {@code session_id} (accepted only)
   * @param rejectReason {@code reject_reason} name (rejected only)
   * @param rejectMessage {@code reject_message} (rejected only; never a token)
   * @param retryAfterS {@code retry_after_s}
   * @param serverTimeMs {@code server_time_ms}
   * @param heartbeatIntervalS {@code heartbeat_interval_s}
   * @param config {@code config}
   * @param userIdSalt {@code user_id_salt} (empty: none)
   */
  record HelloAnswer(
      boolean accepted,
      long instanceId,
      String sessionId,
      String rejectReason,
      String rejectMessage,
      int retryAfterS,
      long serverTimeMs,
      int heartbeatIntervalS,
      Map<String, String> config,
      ByteString userIdSalt) {}

  /**
   * The server side of one open {@code Session} stream; implemented by the protocol, driven by the
   * {@link FakeCollector}. Every method is safe to call after the stream ended (it does nothing).
   */
  interface SessionChannel {
    /** The metadata of the {@code Session} call. */
    CallMetadata metadata();

    /** Sends {@code ServerHello}. */
    void hello(HelloAnswer answer);

    /** Sends {@code HeartbeatAck} ({@code state}: {@code UP}, {@code WARN}, ...). */
    void heartbeatAck(long t1, long t2, long t3, long clockOffsetMs, String state);

    /** Sends {@code ConfigPush}. */
    void configPush(String changeId, Map<String, String> values, boolean revert, long issuedAtMs);

    /** Sends {@code Command}. */
    void command(
        String commandId, String type, Map<String, String> args, int ttlSeconds, long issuedAtMs);

    /** Sends {@code DictionaryRequest} for these hashes of {@code kind}. */
    void dictionaryRequest(String kind, long[] hashes);

    /** Sends {@code Throttle}. */
    void throttle(ThrottleHint hint);

    /** Sends {@code SessionClose}. */
    void sessionClose(String reason, String message, int retryAfterS);

    /** Ends the stream: {@code onCompleted} for OK, else {@code onError} with these trailers. */
    void close(Status status, Map<String, String> trailers);

    /** Reads on after {@link FaultInjector#pauseSessionReads()} held the next message back. */
    void resumeReads();
  }
}
