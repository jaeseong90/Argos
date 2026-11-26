/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.testing.collector.v1;

import com.google.protobuf.ByteString;
import com.google.protobuf.Message;
import io.argos.agent.testing.collector.BatchKind;
import io.argos.agent.testing.collector.Collected;
import io.argos.agent.testing.collector.CollectorProtocol;
import io.argos.agent.testing.collector.FakeCollector;
import io.argos.agent.testing.collector.ReceivedBatch;
import io.argos.agent.testing.collector.ReceivedToken;
import io.argos.agent.testing.collector.SessionInfo;
import io.argos.agent.testing.export.RecordSource;
import io.argos.agent.testing.export.TransactionRecord;
import io.argos.proto.ProtocolLimits;
import io.argos.proto.ProtocolVersion;
import io.argos.proto.TransactionFlags;
import io.argos.proto.v1.Ack;
import io.argos.proto.v1.AckStatus;
import io.argos.proto.v1.ActiveTransaction;
import io.argos.proto.v1.ActiveTransactionSnapshot;
import io.argos.proto.v1.AgentBye;
import io.argos.proto.v1.AgentEvent;
import io.argos.proto.v1.AgentEventType;
import io.argos.proto.v1.AgentHello;
import io.argos.proto.v1.AgentMessage;
import io.argos.proto.v1.AgentServiceGrpc;
import io.argos.proto.v1.BatchHeader;
import io.argos.proto.v1.CloseReason;
import io.argos.proto.v1.Command;
import io.argos.proto.v1.CommandResult;
import io.argos.proto.v1.CommandType;
import io.argos.proto.v1.ConfigApplyResult;
import io.argos.proto.v1.ConfigPush;
import io.argos.proto.v1.CounterDelta;
import io.argos.proto.v1.DictionaryEntry;
import io.argos.proto.v1.DictionaryKind;
import io.argos.proto.v1.DictionaryRef;
import io.argos.proto.v1.DictionaryRequest;
import io.argos.proto.v1.ExtStat;
import io.argos.proto.v1.Heartbeat;
import io.argos.proto.v1.HeartbeatAck;
import io.argos.proto.v1.InstanceState;
import io.argos.proto.v1.KeyValue;
import io.argos.proto.v1.MetricBatch;
import io.argos.proto.v1.MetricSample;
import io.argos.proto.v1.MetricSeries;
import io.argos.proto.v1.ProfileBatch;
import io.argos.proto.v1.ProfileStep;
import io.argos.proto.v1.RejectReason;
import io.argos.proto.v1.RejectedKey;
import io.argos.proto.v1.ServerHello;
import io.argos.proto.v1.ServerMessage;
import io.argos.proto.v1.SessionClose;
import io.argos.proto.v1.SqlStat;
import io.argos.proto.v1.StatBatch;
import io.argos.proto.v1.Throttle;
import io.argos.proto.v1.TransactionBatch;
import io.argos.proto.v1.TransactionKind;
import io.argos.proto.v1.TransactionProfile;
import io.grpc.BindableService;
import io.grpc.Context;
import io.grpc.Contexts;
import io.grpc.ForwardingServerCallListener;
import io.grpc.Grpc;
import io.grpc.Metadata;
import io.grpc.MethodDescriptor;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import io.grpc.ServerInterceptors;
import io.grpc.ServerServiceDefinition;
import io.grpc.Status;
import io.grpc.stub.ServerCallStreamObserver;
import io.grpc.stub.StreamObserver;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * {@link CollectorProtocol} over argos.v1 ({@code :proto}, {@code io.argos.proto.v1}, ADR-0010
 * C-104): the gRPC {@code AgentService} of the {@link FakeCollector}, its metadata interceptor (11
 * §2: {@code authorization}, {@code argos-instance-id}, {@code argos-session-id}, TLS or not), and
 * the conversion of received batches into harness records. The only main class of the harness that
 * uses generated types.
 */
public final class V1Protocol implements CollectorProtocol {
  /** {@code authorization: Bearer argos_<base64url>} (11 §2). */
  public static final Metadata.Key<String> AUTHORIZATION =
      Metadata.Key.of("authorization", Metadata.ASCII_STRING_MARSHALLER);

  /** {@code argos-instance-id: <u32>} of data calls (11 §2). */
  public static final Metadata.Key<String> INSTANCE_ID =
      Metadata.Key.of("argos-instance-id", Metadata.ASCII_STRING_MARSHALLER);

  /** {@code argos-session-id: <uuid>} of data calls (11 §2). */
  public static final Metadata.Key<String> SESSION_ID =
      Metadata.Key.of("argos-session-id", Metadata.ASCII_STRING_MARSHALLER);

  /** Trailer {@code argos-retry-after-ms} (11 §10). */
  public static final Metadata.Key<String> RETRY_AFTER_MS =
      Metadata.Key.of("argos-retry-after-ms", Metadata.ASCII_STRING_MARSHALLER);

  /** Trailer {@code argos-reject-reason} (11 §10). */
  public static final Metadata.Key<String> REJECT_REASON =
      Metadata.Key.of("argos-reject-reason", Metadata.ASCII_STRING_MARSHALLER);

  /** {@code ServerHello.active_snapshot_interval_s} (11 §3: 5 s). */
  static final int ACTIVE_SNAPSHOT_INTERVAL_S = 5;

  /** {@code ServerHello.stat_window_s} (11 §3: 10 s). */
  static final int STAT_WINDOW_S = 10;

  private static final String UNRESOLVED_PREFIX = "#";

  /**
   * The {@code ServerHello.app_id} the collector answers for {@code appName}: a stable number from
   * 1 to 30,000 derived from the name (the real server assigns app ids from its registry), so that
   * two apps on two collectors get different ids and a callee's {@code caller_app_id} names the
   * caller (distributed traces, WO-14 T-ACC-05). Null or empty names get 1.
   */
  public static int appIdOf(String appName) {
    if (appName == null || appName.isEmpty()) {
      return 1;
    }
    return 1 + Math.floorMod(appName.hashCode(), 30_000);
  }

  private static final Context.Key<CallMetadata> METADATA = Context.key("argos-call-metadata");
  private static final Context.Key<Runnable> ANSWERED = Context.key("argos-unary-answered");

  /** Fields of a converted transaction (see {@link #transactions}). */
  public static final RecordSource SOURCE =
      new RecordSource(
          "FakeCollector (argos.v1)",
          Set.of(
              "tx_id",
              "trace_id",
              "span_id",
              "parent_span_id",
              "incoming_trace",
              "caller_app_id",
              "kind",
              "service",
              "start_epoch_ms",
              "elapsed_ms",
              "status",
              "error",
              "error_class",
              "error_message",
              "error_top_frame",
              "http_method",
              "path",
              "remote_ip",
              "user_id",
              "thread",
              "thread_name",
              "thread_name_id",
              "virtual_thread",
              "cpu_time_ms",
              "alloc_bytes",
              "sql_count",
              "sql_time_ms",
              "ext_count",
              "ext_time_ms",
              "dbc_wait_ms",
              "sql_ids",
              "dispatch_count",
              "streaming",
              "timed_out",
              "headers",
              "request_id",
              "env",
              "version",
              "flags",
              "xlog_flags",
              "profile_captured",
              "truncated",
              "total_steps",
              "steps",
              "summary",
              "instance_id",
              "batch_seq"),
          Set.of(
              "seq",
              "parent",
              "type",
              "name_id",
              "name",
              "target_id",
              "target",
              "start_offset_ms",
              "elapsed_ms",
              "fetch_ms",
              "rows",
              "status",
              "failure",
              "error",
              "detail",
              "stack",
              "batch_size",
              "repeat_count"));

  @Override
  public String name() {
    return "argos.v1 (:proto, protocol version " + ProtocolVersion.CURRENT + ")";
  }

  @Override
  public RecordSource recordSource() {
    return SOURCE;
  }

  @Override
  public BindableService service(FakeCollector collector) {
    ServerServiceDefinition definition =
        ServerInterceptors.intercept(new Service(collector), new MetadataInterceptor(collector));
    return () -> definition;
  }

  // --- metadata interceptor ---------------------------------------------------------------------

  /**
   * Reads the 11 §2 metadata into the call's {@link Context} and counts data calls in flight: from
   * arrival until the answer is sent (or the client cancels), so the high-water mark never exceeds
   * the client's own number of outstanding calls (T-AG-071).
   */
  private static final class MetadataInterceptor implements ServerInterceptor {
    private final FakeCollector collector;

    MetadataInterceptor(FakeCollector collector) {
      this.collector = collector;
    }

    @Override
    public <Q, R> ServerCall.Listener<Q> interceptCall(
        ServerCall<Q, R> call, Metadata headers, ServerCallHandler<Q, R> next) {
      CallMetadata metadata =
          new CallMetadata(
              ReceivedToken.fromAuthorization(headers.get(AUTHORIZATION)),
              headers.get(INSTANCE_ID),
              headers.get(SESSION_ID),
              call.getAttributes().get(Grpc.TRANSPORT_ATTR_SSL_SESSION) == null);
      if (call.getMethodDescriptor().getType() != MethodDescriptor.MethodType.UNARY) {
        return Contexts.interceptCall(
            Context.current().withValue(METADATA, metadata), call, headers, next);
      }
      collector.unaryStarted();
      AtomicBoolean done = new AtomicBoolean();
      Runnable answered =
          () -> {
            if (done.compareAndSet(false, true)) {
              collector.unaryFinished();
            }
          };
      Context context = Context.current().withValues(METADATA, metadata, ANSWERED, answered);
      ServerCall.Listener<Q> listener;
      try {
        listener = Contexts.interceptCall(context, call, headers, next);
      } catch (RuntimeException e) {
        answered.run();
        throw e;
      }
      return new ForwardingServerCallListener.SimpleForwardingServerCallListener<>(listener) {
        @Override
        public void onCancel() {
          try {
            super.onCancel();
          } finally {
            answered.run();
          }
        }

        @Override
        public void onComplete() {
          try {
            super.onComplete();
          } finally {
            answered.run();
          }
        }
      };
    }
  }

  // --- gRPC service -----------------------------------------------------------------------------

  private static final class Service extends AgentServiceGrpc.AgentServiceImplBase {
    private final FakeCollector collector;

    Service(FakeCollector collector) {
      this.collector = collector;
    }

    @Override
    public void sendTransactions(TransactionBatch request, StreamObserver<Ack> response) {
      unary(
          BatchKind.TRANSACTIONS,
          request.getHeader(),
          request,
          request.getRecordsCount(),
          response);
    }

    @Override
    public void sendProfiles(ProfileBatch request, StreamObserver<Ack> response) {
      unary(BatchKind.PROFILES, request.getHeader(), request, request.getProfilesCount(), response);
    }

    @Override
    public void sendMetrics(MetricBatch request, StreamObserver<Ack> response) {
      unary(BatchKind.METRICS, request.getHeader(), request, request.getSamplesCount(), response);
    }

    @Override
    public void sendStats(StatBatch request, StreamObserver<Ack> response) {
      unary(
          BatchKind.STATS,
          request.getHeader(),
          request,
          request.getSqlCount() + request.getExtCount() + request.getCountersCount(),
          response);
    }

    @Override
    public void sendActiveSnapshot(
        ActiveTransactionSnapshot request, StreamObserver<Ack> response) {
      unary(
          BatchKind.ACTIVE_SNAPSHOT,
          request.getHeader(),
          request,
          request.getItemsCount(),
          response);
    }

    private void unary(
        BatchKind kind, BatchHeader header, Message body, int items, StreamObserver<Ack> response) {
      CallMetadata metadata = METADATA.get();
      Runnable answered = ANSWERED.get();
      collector.onUnary(
          new UnaryCall(
              kind,
              Integer.toUnsignedLong(header.getInstanceId()),
              header.getAgentEpoch(),
              header.getSeq(),
              header.getSentAtMs(),
              body,
              body.getSerializedSize(),
              items,
              metadata),
          new Reply(response, answered == null ? () -> {} : answered));
    }

    @Override
    public StreamObserver<AgentMessage> session(StreamObserver<ServerMessage> out) {
      ServerCallStreamObserver<ServerMessage> call = (ServerCallStreamObserver<ServerMessage>) out;
      // The collector requests each message itself so that it can stop reading a stream
      // (FaultInjector#pauseSessionReads: no more flow control credit for the client).
      call.disableAutoRequest();
      Channel channel = new Channel(call, METADATA.get(), collector);
      collector.onSessionOpened(channel);
      call.request(1);
      return new StreamObserver<>() {
        @Override
        public void onNext(AgentMessage message) {
          handle(message);
          channel.readNext();
        }

        private void handle(AgentMessage message) {
          switch (message.getBodyCase()) {
            case HELLO -> {
              AgentHello hello = message.getHello();
              channel.appId(appIdOf(hello.getAppName()));
              collector.onHello(
                  channel,
                  hello,
                  new HelloFields(
                      hello.getAppName(),
                      hello.getEnv(),
                      hello.getInstanceName(),
                      hello.getAppVersion(),
                      hello.getAgentVersion(),
                      hello.getProtocolVersion(),
                      hello.getAgentEpoch(),
                      hello.getSentAtMs(),
                      hello.getPlaintext(),
                      hello.getCheckOnly()));
            }
            case HEARTBEAT -> {
              Heartbeat heartbeat = message.getHeartbeat();
              collector.onHeartbeat(
                  channel,
                  message,
                  heartbeat.getT1Ms(),
                  heartbeat.getPrevT1Ms(),
                  heartbeat.getPrevT4Ms(),
                  heartbeat.getDroppedTotal());
            }
            case DICTIONARY_ENTRIES -> {
              List<Collected.DictionaryItem> items = new ArrayList<>();
              for (DictionaryEntry entry : message.getDictionaryEntries().getEntriesList()) {
                items.add(
                    new Collected.DictionaryItem(
                        entry.getKind().name(), entry.getHash(), entry.getText()));
              }
              collector.onDictionary(items);
            }
            case CONFIG_APPLY_RESULT -> {
              ConfigApplyResult result = message.getConfigApplyResult();
              Map<String, String> rejected = new LinkedHashMap<>();
              Map<String, String> messages = new LinkedHashMap<>();
              for (RejectedKey key : result.getRejectedList()) {
                rejected.put(key.getKey(), key.getCode().name());
                if (!key.getMessage().isEmpty()) {
                  messages.put(key.getKey(), key.getMessage());
                }
              }
              collector.onConfigApplyResult(
                  result.getChangeId(),
                  result.getAppliedKeysList(),
                  rejected,
                  result.getRetransformedClasses(),
                  result.getEffectiveConfigHash(),
                  Integer.toUnsignedLong(result.getDurationMs()),
                  messages);
            }
            case COMMAND_RESULT -> {
              CommandResult result = message.getCommandResult();
              collector.onCommandResult(
                  result.getCommandId(),
                  result.getStatus().name(),
                  result.getChunkIndex(),
                  result.getChunkCount(),
                  result.getPayload(),
                  result.getContentType(),
                  result.getError(),
                  result.getTruncated(),
                  Integer.toUnsignedLong(result.getDurationMs()));
            }
            case BYE -> {
              AgentBye bye = message.getBye();
              collector.onBye(
                  channel,
                  message,
                  bye.getReason().name(),
                  bye.getLastTxSeq(),
                  bye.getLastProfileSeq(),
                  bye.getLastMetricSeq(),
                  bye.getLastStatSeq());
            }
            case INSTANCE_UPDATE -> {
              if (message.getInstanceUpdate().hasInstanceName()) {
                collector.onInstanceName(channel, message.getInstanceUpdate().getInstanceName());
              }
              if (message.getInstanceUpdate().hasConfigHash()) {
                collector.onConfigHash(channel, message.getInstanceUpdate().getConfigHash());
              }
              collector.onSessionMessage(message);
            }
            case EVENT -> {
              AgentEvent event = message.getEvent();
              collector.onAgentEvent(
                  channel,
                  event.getType() == AgentEventType.UNRECOGNIZED
                      ? "UNRECOGNIZED"
                      : event.getType().name(),
                  event.getModule(),
                  event.getDetail(),
                  event.getAtMs());
              collector.onSessionMessage(message);
            }
            default -> collector.onSessionMessage(message);
          }
        }

        @Override
        public void onError(Throwable error) {
          channel.ended();
        }

        @Override
        public void onCompleted() {
          collector.onSessionClosed(channel);
          channel.close(Status.OK, Map.of());
        }
      };
    }
  }

  /** The answer of one data call; the first answer wins, later ones do nothing. */
  private static final class Reply implements UnaryReply {
    private final StreamObserver<Ack> response;
    private final Runnable answered;
    private final AtomicBoolean sent = new AtomicBoolean();

    Reply(StreamObserver<Ack> response, Runnable answered) {
      this.response = response;
      this.answered = answered;
    }

    @Override
    public void ack(AckAnswer answer) {
      if (!sent.compareAndSet(false, true)) {
        return;
      }
      Ack.Builder ack =
          Ack.newBuilder()
              .setLastSeq(answer.lastSeq())
              .setStatus(AckStatus.valueOf(answer.status()))
              .setDroppedItems(answer.droppedItems());
      if (answer.throttle() != null) {
        ack.setThrottle(throttle(answer.throttle()));
      }
      answered.run();
      try {
        response.onNext(ack.build());
        response.onCompleted();
      } catch (RuntimeException e) {
        // the client gave up (deadline, cancel)
      }
    }

    @Override
    public void fail(Status status, Map<String, String> trailers) {
      if (!sent.compareAndSet(false, true)) {
        return;
      }
      answered.run();
      try {
        response.onError(status.asRuntimeException(V1Protocol.trailers(trailers)));
      } catch (RuntimeException e) {
        // the client gave up (deadline, cancel)
      }
    }
  }

  static Metadata trailers(Map<String, String> trailers) {
    Metadata metadata = new Metadata();
    trailers.forEach(
        (key, value) ->
            metadata.put(Metadata.Key.of(key, Metadata.ASCII_STRING_MARSHALLER), value));
    return metadata;
  }

  static Throttle throttle(ThrottleHint hint) {
    Throttle.Builder throttle = Throttle.newBuilder();
    if (hint.profileNormalRate() != null) {
      throttle.setProfileNormalRate(hint.profileNormalRate());
    }
    if (hint.sendBackoffMs() != null) {
      throttle.setSendBackoffMs(hint.sendBackoffMs());
    }
    return throttle.build();
  }

  /** The server side of one session stream; every method does nothing once the stream ended. */
  private static final class Channel implements SessionChannel {
    private final ServerCallStreamObserver<ServerMessage> out;
    private final CallMetadata metadata;
    private final FakeCollector collector;
    private boolean closed;
    private boolean heldBack;
    private int appId = 1;

    Channel(
        ServerCallStreamObserver<ServerMessage> out,
        CallMetadata metadata,
        FakeCollector collector) {
      this.out = out;
      this.metadata = metadata != null ? metadata : new CallMetadata(null, null, null, true);
      this.collector = collector;
    }

    /** The {@code app_id} of the next {@code ServerHello} ({@link #appIdOf}). */
    synchronized void appId(int value) {
      this.appId = value;
    }

    /** One message was handled: asks for the next one unless reads are paused. */
    synchronized void readNext() {
      if (closed) {
        return;
      }
      if (collector.sessionReadsPaused()) {
        heldBack = true;
      } else {
        out.request(1);
      }
    }

    @Override
    public synchronized void resumeReads() {
      if (heldBack && !closed) {
        heldBack = false;
        out.request(1);
      }
    }

    @Override
    public CallMetadata metadata() {
      return metadata;
    }

    synchronized void send(ServerMessage message) {
      if (closed) {
        return;
      }
      try {
        out.onNext(message);
      } catch (RuntimeException e) {
        closed = true;
      }
    }

    synchronized void ended() {
      closed = true;
      collector.onSessionClosed(this);
    }

    @Override
    public synchronized void hello(HelloAnswer answer) {
      ServerHello.Builder hello =
          ServerHello.newBuilder()
              .setAccepted(answer.accepted())
              .setServerVersion(FakeCollector.SERVER_VERSION)
              .setProtocolVersion(ProtocolVersion.CURRENT)
              .setServerTimeMs(answer.serverTimeMs())
              .setHeartbeatIntervalS(answer.heartbeatIntervalS())
              .setRetryAfterS(answer.retryAfterS());
      if (answer.accepted()) {
        hello
            .setAppId(appId)
            .setInstanceId((int) answer.instanceId())
            .setSessionId(answer.sessionId())
            .setActiveSnapshotIntervalS(ACTIVE_SNAPSHOT_INTERVAL_S)
            .setStatWindowS(STAT_WINDOW_S)
            .setLimits(ProtocolLimits.defaults())
            .putAllConfig(answer.config())
            .setUserIdSalt(answer.userIdSalt());
      } else {
        hello
            .setRejectReason(RejectReason.valueOf(answer.rejectReason()))
            .setRejectMessage(answer.rejectMessage() == null ? "" : answer.rejectMessage());
      }
      send(ServerMessage.newBuilder().setHello(hello).build());
    }

    @Override
    public void heartbeatAck(long t1, long t2, long t3, long clockOffsetMs, String state) {
      send(
          ServerMessage.newBuilder()
              .setHeartbeatAck(
                  HeartbeatAck.newBuilder()
                      .setT1Ms(t1)
                      .setT2Ms(t2)
                      .setT3Ms(t3)
                      .setClockOffsetMs(
                          (int)
                              Math.max(
                                  Integer.MIN_VALUE, Math.min(Integer.MAX_VALUE, clockOffsetMs)))
                      .setState(InstanceState.valueOf(state)))
              .build());
    }

    @Override
    public void configPush(
        String changeId, Map<String, String> values, boolean revert, long issuedAtMs) {
      send(
          ServerMessage.newBuilder()
              .setConfigPush(
                  ConfigPush.newBuilder()
                      .setChangeId(changeId)
                      .putAllValues(values)
                      .setRevert(revert)
                      .setIssuedAtMs(issuedAtMs))
              .build());
    }

    @Override
    public void command(
        String commandId, String type, Map<String, String> args, int ttlSeconds, long issuedAtMs) {
      send(
          ServerMessage.newBuilder()
              .setCommand(
                  Command.newBuilder()
                      .setCommandId(commandId)
                      .setType(CommandType.valueOf(type))
                      .setIssuedAtMs(issuedAtMs)
                      .setTtlS(ttlSeconds)
                      .putAllArgs(args))
              .build());
    }

    @Override
    public void commandNumber(
        String commandId,
        int typeNumber,
        Map<String, String> args,
        int ttlSeconds,
        long issuedAtMs) {
      send(
          ServerMessage.newBuilder()
              .setCommand(
                  Command.newBuilder()
                      .setCommandId(commandId)
                      .setTypeValue(typeNumber)
                      .setIssuedAtMs(issuedAtMs)
                      .setTtlS(ttlSeconds)
                      .putAllArgs(args))
              .build());
    }

    @Override
    public void dictionaryRequest(String kind, long[] hashes) {
      DictionaryRequest.Builder request = DictionaryRequest.newBuilder();
      for (long hash : hashes) {
        request.addRefs(
            DictionaryRef.newBuilder().setKind(DictionaryKind.valueOf(kind)).setHash(hash));
      }
      send(ServerMessage.newBuilder().setDictionaryRequest(request).build());
    }

    @Override
    public void throttle(ThrottleHint hint) {
      send(ServerMessage.newBuilder().setThrottle(V1Protocol.throttle(hint)).build());
    }

    @Override
    public void sessionClose(String reason, String message, int retryAfterS) {
      send(
          ServerMessage.newBuilder()
              .setClose(
                  SessionClose.newBuilder()
                      .setReason(CloseReason.valueOf(reason))
                      .setMessage(message)
                      .setRetryAfterS(retryAfterS))
              .build());
    }

    @Override
    public synchronized void close(Status status, Map<String, String> trailers) {
      if (closed) {
        return;
      }
      closed = true;
      collector.onSessionClosed(this);
      try {
        if (status.isOk()) {
          out.onCompleted();
        } else {
          out.onError(status.asRuntimeException(V1Protocol.trailers(trailers)));
        }
      } catch (RuntimeException e) {
        // already ended by the peer
      }
    }
  }

  // --- conversion to harness records ------------------------------------------------------------

  /**
   * Transaction records of every transaction batch, so that the assertion DSL reads the same record
   * as from the JSONL exporter: its field names where they exist, step {@code seq} and {@code
   * parent} in its numbering (0-based, -1 for a root; 1-based with 0 for a root on the wire),
   * summary entries in {@code summary[]} instead of {@code steps[]}. {@code cpu_time_ms} and {@code
   * alloc_bytes} are there only when the agent set the optional field, so that a missing value
   * stays missing (C-4). The steps, {@code truncated} and {@code total_steps} come from the
   * matching profile (absent without one), {@code env} and {@code version} from the instance's
   * hello (or the {@code APP_VERSION} dictionary). The wire has no {@code service_fixed}, {@code
   * summary_overflow} or step {@code thread}, and {@code thread} is the normalized name. Duplicates
   * ({@code ACK_DUPLICATE}) are skipped; refused batches too (only written batches count).
   */
  @Override
  public List<TransactionRecord> transactions(
      List<ReceivedBatch> batches, Map<Long, String> dictionary, Map<Long, SessionInfo> sessions) {
    Map<Long, TransactionProfile> profiles = new LinkedHashMap<>();
    for (ReceivedBatch batch : batches) {
      if (batch.kind() == BatchKind.PROFILES
          && batch.acked()
          && batch.body() instanceof ProfileBatch profile) {
        for (TransactionProfile p : profile.getProfilesList()) {
          profiles.put(p.getTxId(), p);
        }
      }
    }
    List<TransactionRecord> records = new ArrayList<>();
    for (ReceivedBatch batch : batches) {
      if (batch.kind() != BatchKind.TRANSACTIONS
          || !batch.acked()
          || !(batch.body() instanceof TransactionBatch transactions)) {
        continue;
      }
      SessionInfo session = sessions.get(batch.instanceId());
      for (io.argos.proto.v1.TransactionRecord r : transactions.getRecordsList()) {
        records.add(record(r, batch, session, profiles.get(r.getTxId()), dictionary));
      }
    }
    return records;
  }

  private static TransactionRecord record(
      io.argos.proto.v1.TransactionRecord r,
      ReceivedBatch batch,
      SessionInfo session,
      TransactionProfile profile,
      Map<Long, String> dictionary) {
    Map<String, Object> f = new LinkedHashMap<>();
    f.put("tx_id", Long.toUnsignedString(r.getTxId()));
    f.put("trace_id", HexFormat.of().formatHex(r.getTraceId().toByteArray()));
    f.put("span_id", HexFormat.of().toHexDigits(r.getSpanId()));
    f.put(
        "parent_span_id",
        r.getParentSpanId() == 0 ? null : HexFormat.of().toHexDigits(r.getParentSpanId()));
    f.put("incoming_trace", r.getIncomingTrace());
    f.put("caller_app_id", Integer.toUnsignedLong(r.getCallerAppId()));
    f.put(
        "kind",
        r.getKind() == TransactionKind.TRANSACTION_KIND_UNSPECIFIED ? null : r.getKind().name());
    f.put("service", text(dictionary, r.getServiceId()));
    f.put("start_epoch_ms", r.getStartTsMs());
    f.put("elapsed_ms", r.getElapsedUs() / 1000.0);
    f.put("status", Integer.toUnsignedLong(r.getHttpStatus()));
    f.put("error", r.getError());
    f.put("error_class", text(dictionary, r.getErrorClassId()));
    f.put("error_message", emptyToNull(r.getErrorMessage()));
    f.put("error_top_frame", text(dictionary, r.getErrorTopFrameId()));
    f.put("http_method", emptyToNull(r.getHttpMethod()));
    f.put("path", emptyToNull(r.getUrlPath()));
    f.put("remote_ip", ip(r.getRemoteIp()));
    f.put("user_id", emptyToNull(r.getUserId()));
    String thread = text(dictionary, r.getThreadNameId());
    f.put("thread", thread);
    f.put("thread_name", thread);
    f.put("thread_name_id", Long.toUnsignedString(r.getThreadNameId()));
    f.put("virtual_thread", r.getVirtualThread());
    if (r.hasCpuTimeUs()) {
      f.put("cpu_time_ms", r.getCpuTimeUs() / 1000.0);
    }
    if (r.hasAllocBytes()) {
      f.put("alloc_bytes", r.getAllocBytes());
    }
    f.put("sql_count", Integer.toUnsignedLong(r.getSqlCount()));
    f.put("sql_time_ms", Integer.toUnsignedLong(r.getSqlTimeMs()));
    f.put("ext_count", Integer.toUnsignedLong(r.getExtCount()));
    f.put("ext_time_ms", Integer.toUnsignedLong(r.getExtTimeMs()));
    f.put("dbc_wait_ms", Integer.toUnsignedLong(r.getDbcWaitMs()));
    List<Object> sqlIds = new ArrayList<>();
    for (long id : r.getSqlIdsList()) {
      sqlIds.add(Long.toUnsignedString(id));
    }
    f.put("sql_ids", sqlIds);
    f.put("dispatch_count", Integer.toUnsignedLong(r.getDispatchCount()));
    f.put("streaming", r.getStreaming());
    f.put("timed_out", r.getTimedOut());
    Map<String, Object> headers = new LinkedHashMap<>();
    for (KeyValue header : r.getHeadersList()) {
      headers.put(header.getKey(), header.getValue());
    }
    f.put("headers", headers);
    f.put("request_id", emptyToNull(r.getRequestId()));
    f.put("env", session == null ? null : emptyToNull(session.env()));
    String version = text(dictionary, r.getAppVersionId());
    f.put(
        "version",
        version != null ? version : session == null ? null : emptyToNull(session.appVersion()));
    f.put("instance_id", batch.instanceId());
    f.put("batch_seq", batch.seq());
    int bits = r.getFlags();
    List<Object> flags = new ArrayList<>();
    if ((bits & TransactionFlags.VIRTUAL_THREAD) != 0) {
      flags.add("virtual_thread");
    }
    if ((bits & TransactionFlags.LEAKED) != 0) {
      flags.add("leaked");
    }
    if ((bits & TransactionFlags.SUMMARIZED) != 0) {
      flags.add("summarized");
    }
    if (r.getStreaming()) {
      flags.add("streaming");
    }
    if (r.getTimedOut()) {
      flags.add("timeout");
    }
    f.put("flags", flags);
    f.put("xlog_flags", Integer.toUnsignedLong(bits));
    f.put("profile_captured", r.getProfileCaptured());
    f.put("truncated", profile != null && profile.getTruncated());
    if (profile != null) {
      f.put("total_steps", Integer.toUnsignedLong(profile.getTotalSteps()));
    }
    f.put("steps", steps(profile, dictionary));
    f.put("summary", summary(profile, dictionary));
    return new TransactionRecord(f, f.toString(), SOURCE);
  }

  /**
   * False until the profile of a sampled transaction arrived and every dictionary text it names is
   * known (see {@link #text}: {@code #<id>} until then).
   */
  @Override
  public boolean complete(TransactionRecord record) {
    if (Boolean.TRUE.equals(record.profileCaptured()) && record.totalSteps() == null) {
      return false;
    }
    if (unresolved(record.service())
        || unresolved(record.threadName())
        || unresolved(record.errorClass())) {
      return false;
    }
    for (TransactionRecord.StepRecord step : record.steps()) {
      if (unresolved(step.name()) || unresolved(step.target())) {
        return false;
      }
    }
    for (TransactionRecord.SummaryRecord entry : record.summary()) {
      if (unresolved(entry.name())) {
        return false;
      }
    }
    return true;
  }

  private static boolean unresolved(String text) {
    return text != null && text.startsWith(UNRESOLVED_PREFIX);
  }

  /** The recorded steps of {@code profile} in the JSONL numbering, without summary entries. */
  private static List<Object> steps(TransactionProfile profile, Map<Long, String> dictionary) {
    List<Object> steps = new ArrayList<>();
    if (profile == null) {
      return steps;
    }
    for (ProfileStep step : profile.getStepsList()) {
      if (step.getType() == io.argos.proto.v1.StepType.SUMMARY) {
        continue;
      }
      Map<String, Object> s = new LinkedHashMap<>();
      s.put("seq", Integer.toUnsignedLong(step.getSeq()) - 1);
      s.put("parent", Integer.toUnsignedLong(step.getParentSeq()) - 1);
      s.put("type", stepType(step.getType().name()));
      s.put("name_id", Long.toUnsignedString(step.getNameId()));
      s.put("name", text(dictionary, step.getNameId()));
      s.put("target_id", Long.toUnsignedString(step.getTargetId()));
      s.put("target", text(dictionary, step.getTargetId()));
      s.put("start_offset_ms", Integer.toUnsignedLong(step.getStartOffsetUs()) / 1000.0);
      s.put("elapsed_ms", Integer.toUnsignedLong(step.getElapsedUs()) / 1000.0);
      s.put("fetch_ms", Integer.toUnsignedLong(step.getFetchUs()) / 1000.0);
      s.put("rows", Integer.toUnsignedLong(step.getRows()));
      s.put("status", Integer.toUnsignedLong(step.getStatus()));
      s.put("failure", step.getFailure().name());
      s.put("error", step.getError());
      s.put("detail", emptyToNull(step.getDetail()));
      List<Object> stack = new ArrayList<>();
      for (long frame : step.getFrameIdsList()) {
        stack.add(text(dictionary, frame));
      }
      s.put("stack", stack);
      s.put("batch_size", Integer.toUnsignedLong(step.getBatchSize()));
      s.put("repeat_count", Integer.toUnsignedLong(step.getRepeatCount()));
      steps.add(s);
    }
    return steps;
  }

  /**
   * The {@code SUMMARY} steps of {@code profile} as the JSONL's {@code summary[]} entries. The
   * agent encodes them (ADR-0010 C-141, {@code BatchAssembler}): {@code status} = the summarized
   * step type (C-1 number), {@code repeat_count} = the step count, {@code elapsed_us} = their total
   * time, {@code fetch_us} = the longest one, {@code rows} = the failed ones.
   */
  private static List<Object> summary(TransactionProfile profile, Map<Long, String> dictionary) {
    List<Object> entries = new ArrayList<>();
    if (profile == null) {
      return entries;
    }
    for (ProfileStep step : profile.getStepsList()) {
      if (step.getType() != io.argos.proto.v1.StepType.SUMMARY) {
        continue;
      }
      io.argos.proto.v1.StepType type = io.argos.proto.v1.StepType.forNumber(step.getStatus());
      Map<String, Object> entry = new LinkedHashMap<>();
      entry.put("type", type == null ? null : stepType(type.name()));
      entry.put("name", text(dictionary, step.getNameId()));
      entry.put("count", Integer.toUnsignedLong(step.getRepeatCount()));
      entry.put("total_ms", Integer.toUnsignedLong(step.getElapsedUs()) / 1000.0);
      entry.put("max_ms", Integer.toUnsignedLong(step.getFetchUs()) / 1000.0);
      entry.put("errors", Integer.toUnsignedLong(step.getRows()));
      entries.add(entry);
    }
    return entries;
  }

  /** argos.v1 {@code StepType} names to the agent's {@code StepType} names (C-1 numbers agree). */
  public static String stepType(String v1) {
    return switch (v1) {
      case "HTTP_CALL" -> "HTTP";
      case "MESSAGE_STEP" -> "MESSAGE";
      case "STEP_TYPE_UNSPECIFIED", "UNRECOGNIZED" -> null;
      default -> v1;
    };
  }

  /** SQL statistics of the written stat batches, summed per statement text. */
  @Override
  public List<Collected.SqlStat> sqlStats(
      List<ReceivedBatch> batches, Map<Long, String> dictionary) {
    Map<String, long[]> counts = new LinkedHashMap<>();
    Map<String, Double> sums = new LinkedHashMap<>();
    for (ReceivedBatch batch : batches) {
      if (batch.kind() == BatchKind.STATS
          && batch.acked()
          && batch.body() instanceof StatBatch stats) {
        for (SqlStat stat : stats.getSqlList()) {
          String sql = text(dictionary, stat.getSqlId());
          String key = sql != null ? sql : "#" + Long.toUnsignedString(stat.getSqlId());
          long[] count = counts.computeIfAbsent(key, k -> new long[2]);
          count[0] += Integer.toUnsignedLong(stat.getCount());
          count[1] += Integer.toUnsignedLong(stat.getErrorCount());
          sums.merge(key, stat.getSumUs() / 1000.0, Double::sum);
        }
      }
    }
    List<Collected.SqlStat> stats = new ArrayList<>();
    counts.forEach(
        (sql, count) -> stats.add(new Collected.SqlStat(sql, count[0], count[1], sums.get(sql))));
    return stats;
  }

  /** Metric samples of the written metric batches (labels from the OpenMetrics series name). */
  @Override
  public List<Collected.MetricPoint> metrics(List<ReceivedBatch> batches) {
    List<Collected.MetricPoint> points = new ArrayList<>();
    for (ReceivedBatch batch : batches) {
      if (batch.kind() != BatchKind.METRICS
          || !batch.acked()
          || !(batch.body() instanceof MetricBatch metrics)) {
        continue;
      }
      Map<Integer, MetricSeries> series = new LinkedHashMap<>();
      for (MetricSeries s : metrics.getSeriesList()) {
        series.put(s.getIndex(), s);
      }
      for (MetricSample sample : metrics.getSamplesList()) {
        MetricSeries s = series.get(sample.getSeriesIndex());
        String name = s == null ? "#" + sample.getSeriesIndex() : s.getName();
        points.add(
            new Collected.MetricPoint(
                baseName(name),
                labels(name),
                s == null ? null : s.getKind().name(),
                sample.getValue(),
                sample.getTsMs(),
                sample.getDelta()));
      }
    }
    return points;
  }

  /** Counter deltas of the written stat batches, summed per name. */
  @Override
  public Map<String, Long> counters(List<ReceivedBatch> batches) {
    Map<String, Long> counters = new LinkedHashMap<>();
    for (ReceivedBatch batch : batches) {
      if (batch.kind() == BatchKind.STATS
          && batch.acked()
          && batch.body() instanceof StatBatch stats) {
        for (CounterDelta delta : stats.getCountersList()) {
          counters.merge(delta.getName(), delta.getDelta(), Long::sum);
        }
      }
    }
    return counters;
  }

  /** External-call statistics of the written stat batches, summed per host text and kind. */
  @Override
  public List<Collected.ExtStat> extStats(
      List<ReceivedBatch> batches, Map<Long, String> dictionary) {
    Map<String, long[]> counts = new LinkedHashMap<>();
    Map<String, Double> sums = new LinkedHashMap<>();
    Map<String, String[]> keys = new LinkedHashMap<>();
    for (ReceivedBatch batch : batches) {
      if (batch.kind() == BatchKind.STATS
          && batch.acked()
          && batch.body() instanceof StatBatch stats) {
        for (ExtStat stat : stats.getExtList()) {
          String host = text(dictionary, stat.getHostId());
          String hostText = host != null ? host : "#" + Long.toUnsignedString(stat.getHostId());
          String kind = stat.getKind().name();
          String key = hostText + "|" + kind;
          keys.putIfAbsent(key, new String[] {hostText, kind});
          long[] count = counts.computeIfAbsent(key, k -> new long[4]);
          count[0] += Integer.toUnsignedLong(stat.getCount());
          count[1] += Integer.toUnsignedLong(stat.getErrorCount());
          count[2] += Integer.toUnsignedLong(stat.getTimeoutCount());
          count[3] += Integer.toUnsignedLong(stat.getConnectFailCount());
          sums.merge(key, stat.getSumUs() / 1000.0, Double::sum);
        }
      }
    }
    List<Collected.ExtStat> stats = new ArrayList<>();
    counts.forEach(
        (key, c) ->
            stats.add(
                new Collected.ExtStat(
                    keys.get(key)[0], keys.get(key)[1], c[0], c[1], c[2], c[3], sums.get(key))));
    return stats;
  }

  /** Every written stat batch as one window, rows with their texts. */
  @Override
  public List<Collected.StatWindow> statWindows(
      List<ReceivedBatch> batches, Map<Long, String> dictionary) {
    List<Collected.StatWindow> windows = new ArrayList<>();
    for (ReceivedBatch batch : batches) {
      if (batch.kind() != BatchKind.STATS
          || !batch.acked()
          || !(batch.body() instanceof StatBatch stats)) {
        continue;
      }
      List<Collected.SqlRow> sql = new ArrayList<>();
      for (SqlStat s : stats.getSqlList()) {
        sql.add(
            new Collected.SqlRow(
                s.getSqlId(),
                text(dictionary, s.getSqlId()),
                s.getDbId(),
                s.getKind().name(),
                Integer.toUnsignedLong(s.getCount()),
                Integer.toUnsignedLong(s.getErrorCount()),
                s.getSumUs(),
                Integer.toUnsignedLong(s.getMaxUs()),
                s.getFetchRows(),
                s.hasSumExecUs() ? s.getSumExecUs() : null,
                s.hasSumFetchUs() ? s.getSumFetchUs() : null,
                unsigned(s.getBucketsList())));
      }
      List<Collected.ExtRow> ext = new ArrayList<>();
      for (ExtStat e : stats.getExtList()) {
        ext.add(
            new Collected.ExtRow(
                e.getHostId(),
                text(dictionary, e.getHostId()),
                e.getKind().name(),
                Integer.toUnsignedLong(e.getCount()),
                Integer.toUnsignedLong(e.getErrorCount()),
                Integer.toUnsignedLong(e.getTimeoutCount()),
                Integer.toUnsignedLong(e.getConnectFailCount()),
                e.getSumUs(),
                Integer.toUnsignedLong(e.getMaxUs()),
                unsigned(e.getBucketsList())));
      }
      List<String> counterNames = new ArrayList<>();
      for (CounterDelta delta : stats.getCountersList()) {
        counterNames.add(delta.getName());
      }
      windows.add(
          new Collected.StatWindow(
              stats.getWindowStartMs(), stats.getWindowLenS(), sql, ext, counterNames));
    }
    return windows;
  }

  /** Every acknowledged active snapshot, texts resolved. */
  @Override
  public List<Collected.ActiveSnapshot> activeSnapshots(
      List<ReceivedBatch> batches, Map<Long, String> dictionary) {
    List<Collected.ActiveSnapshot> snapshots = new ArrayList<>();
    for (ReceivedBatch batch : batches) {
      if (batch.kind() != BatchKind.ACTIVE_SNAPSHOT
          || !batch.acked()
          || !(batch.body() instanceof ActiveTransactionSnapshot snapshot)) {
        continue;
      }
      List<Collected.ActiveItem> items = new ArrayList<>();
      for (ActiveTransaction item : snapshot.getItemsList()) {
        items.add(
            new Collected.ActiveItem(
                item.getTxId(),
                text(dictionary, item.getServiceId()),
                item.getKind().name(),
                item.getStartTsMs(),
                Integer.toUnsignedLong(item.getElapsedMs()),
                item.getState().name(),
                item.getStateNameId(),
                text(dictionary, item.getStateNameId()),
                text(dictionary, item.getThreadNameId()),
                item.getThreadId(),
                item.getVirtualThread(),
                emptyToNull(item.getUserId()),
                ip(item.getRemoteIp())));
      }
      snapshots.add(
          new Collected.ActiveSnapshot(
              snapshot.getSnapshotTsMs(),
              Integer.toUnsignedLong(snapshot.getTotalActive()),
              snapshot.getTruncated(),
              items,
              batch.receivedAt().toEpochMilli()));
    }
    return snapshots;
  }

  private static List<Long> unsigned(List<Integer> values) {
    List<Long> out = new ArrayList<>(values.size());
    for (int v : values) {
      out.add(Integer.toUnsignedLong(v));
    }
    return out;
  }

  /** {@code name{k="v",...}} → {@code name}. */
  public static String baseName(String series) {
    int brace = series.indexOf('{');
    return brace < 0 ? series : series.substring(0, brace);
  }

  /** {@code name{k="v",...}} → labels (OpenMetrics series names carry their labels). */
  public static Map<String, String> labels(String series) {
    Map<String, String> labels = new LinkedHashMap<>();
    int brace = series.indexOf('{');
    if (brace < 0 || !series.endsWith("}")) {
      return labels;
    }
    for (String pair : series.substring(brace + 1, series.length() - 1).split(",", -1)) {
      int eq = pair.indexOf('=');
      if (eq > 0) {
        String value = pair.substring(eq + 1).trim();
        if (value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
          value = value.substring(1, value.length() - 1);
        }
        labels.put(pair.substring(0, eq).trim(), value);
      }
    }
    return labels;
  }

  /** The text of a dictionary id; null for no id, {@code #<id>} while the text is unknown. */
  private static String text(Map<Long, String> dictionary, long id) {
    if (id == 0) {
      return null;
    }
    String text = dictionary.get(id);
    return text != null ? text : UNRESOLVED_PREFIX + Long.toUnsignedString(id);
  }

  private static String emptyToNull(String value) {
    return value == null || value.isEmpty() ? null : value;
  }

  private static String ip(ByteString bytes) {
    if (bytes.isEmpty()) {
      return null;
    }
    byte[] address = bytes.toByteArray();
    if (address.length == 4) {
      return (address[0] & 0xff)
          + "."
          + (address[1] & 0xff)
          + "."
          + (address[2] & 0xff)
          + "."
          + (address[3] & 0xff);
    }
    return HexFormat.of().formatHex(address);
  }
}
