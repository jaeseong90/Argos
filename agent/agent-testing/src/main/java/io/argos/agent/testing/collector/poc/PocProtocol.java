/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.testing.collector.poc;

import com.google.protobuf.ByteString;
import com.google.protobuf.Message;
import io.argos.agent.testing.collector.BatchKind;
import io.argos.agent.testing.collector.Collected;
import io.argos.agent.testing.collector.CollectorProtocol;
import io.argos.agent.testing.collector.FakeCollector;
import io.argos.agent.testing.collector.ReceivedBatch;
import io.argos.agent.testing.collector.SessionInfo;
import io.argos.agent.testing.export.RecordSource;
import io.argos.agent.testing.export.TransactionRecord;
import io.argos.agent.transportpoc.proto.Ack;
import io.argos.agent.transportpoc.proto.AckStatus;
import io.argos.agent.transportpoc.proto.ActiveTransactionSnapshot;
import io.argos.agent.transportpoc.proto.AgentHello;
import io.argos.agent.transportpoc.proto.AgentMessage;
import io.argos.agent.transportpoc.proto.AgentServiceGrpc;
import io.argos.agent.transportpoc.proto.BatchHeader;
import io.argos.agent.transportpoc.proto.Command;
import io.argos.agent.transportpoc.proto.CommandResult;
import io.argos.agent.transportpoc.proto.CommandType;
import io.argos.agent.transportpoc.proto.ConfigApplyResult;
import io.argos.agent.transportpoc.proto.ConfigPush;
import io.argos.agent.transportpoc.proto.CounterDelta;
import io.argos.agent.transportpoc.proto.DictionaryEntry;
import io.argos.agent.transportpoc.proto.HeartbeatAck;
import io.argos.agent.transportpoc.proto.MetricBatch;
import io.argos.agent.transportpoc.proto.MetricSample;
import io.argos.agent.transportpoc.proto.MetricSeries;
import io.argos.agent.transportpoc.proto.ProfileBatch;
import io.argos.agent.transportpoc.proto.ProfileStep;
import io.argos.agent.transportpoc.proto.RejectReason;
import io.argos.agent.transportpoc.proto.RejectedKey;
import io.argos.agent.transportpoc.proto.ServerHello;
import io.argos.agent.transportpoc.proto.ServerMessage;
import io.argos.agent.transportpoc.proto.SqlStat;
import io.argos.agent.transportpoc.proto.StatBatch;
import io.argos.agent.transportpoc.proto.TransactionBatch;
import io.argos.agent.transportpoc.proto.TransactionKind;
import io.argos.agent.transportpoc.proto.TransactionProfile;
import io.grpc.BindableService;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * {@link io.argos.agent.testing.collector.CollectorProtocol} over the WO-02 transport PoC protocol:
 * {@code test/agent/transport-poc/src/proto/argos/v1} (a snapshot of 11 §4, Java package {@code
 * io.argos.agent.transportpoc.proto}), generated into agent-testing's {@code collectorProto} source
 * set. The only main class that uses generated types. WO-11 replaces it with the real {@code
 * proto/argos/v1} contract.
 */
public final class PocProtocol implements CollectorProtocol {
  /** Fields of a converted transaction (see {@link #transactions}). */
  public static final RecordSource SOURCE =
      new RecordSource(
          "FakeCollector (WO-02 PoC proto)",
          Set.of(
              "tx_id",
              "trace_id",
              "span_id",
              "parent_span_id",
              "kind",
              "service",
              "start_epoch_ms",
              "elapsed_ms",
              "status",
              "error",
              "error_class",
              "error_message",
              "http_method",
              "path",
              "remote_ip",
              "user_id",
              "thread",
              "virtual_thread",
              "cpu_time_ms",
              "alloc_bytes",
              "sql_count",
              "ext_count",
              "env",
              "version",
              "flags",
              "steps",
              "instance_id",
              "batch_seq"),
          Set.of(
              "seq",
              "parent",
              "type",
              "name",
              "target",
              "start_offset_ms",
              "elapsed_ms",
              "fetch_ms",
              "rows",
              "status",
              "error",
              "detail"));

  @Override
  public String name() {
    return "WO-02 PoC proto (test/agent/transport-poc/src/proto/argos/v1)";
  }

  @Override
  public RecordSource recordSource() {
    return SOURCE;
  }

  @Override
  public BindableService service(FakeCollector collector) {
    return new Service(collector);
  }

  // --- gRPC service -----------------------------------------------------------------------------

  private static final class Service extends AgentServiceGrpc.AgentServiceImplBase {
    private final FakeCollector collector;

    Service(FakeCollector collector) {
      this.collector = collector;
    }

    @Override
    public void sendTransactions(TransactionBatch request, StreamObserver<Ack> response) {
      unary(BatchKind.TRANSACTIONS, request.getHeader(), request, response);
    }

    @Override
    public void sendProfiles(ProfileBatch request, StreamObserver<Ack> response) {
      unary(BatchKind.PROFILES, request.getHeader(), request, response);
    }

    @Override
    public void sendMetrics(MetricBatch request, StreamObserver<Ack> response) {
      unary(BatchKind.METRICS, request.getHeader(), request, response);
    }

    @Override
    public void sendStats(StatBatch request, StreamObserver<Ack> response) {
      unary(BatchKind.STATS, request.getHeader(), request, response);
    }

    @Override
    public void sendActiveSnapshot(
        ActiveTransactionSnapshot request, StreamObserver<Ack> response) {
      unary(BatchKind.ACTIVE_SNAPSHOT, request.getHeader(), request, response);
    }

    private void unary(
        BatchKind kind, BatchHeader header, Message body, StreamObserver<Ack> response) {
      long seq = header.getSeq();
      collector.onUnary(
          kind,
          Integer.toUnsignedLong(header.getInstanceId()),
          header.getAgentEpoch(),
          seq,
          body,
          body.getSerializedSize(),
          new FakeCollector.UnaryReply() {
            @Override
            public void ok() {
              try {
                response.onNext(
                    Ack.newBuilder().setLastSeq(seq).setStatus(AckStatus.ACK_OK).build());
                response.onCompleted();
              } catch (RuntimeException e) {
                // the client gave up (deadline, cancel)
              }
            }

            @Override
            public void fail(Status status) {
              try {
                response.onError(status.asRuntimeException());
              } catch (RuntimeException e) {
                // the client gave up (deadline, cancel)
              }
            }
          });
    }

    @Override
    public StreamObserver<AgentMessage> session(StreamObserver<ServerMessage> out) {
      Session channel = new Session(out);
      return new StreamObserver<>() {
        @Override
        public void onNext(AgentMessage message) {
          switch (message.getBodyCase()) {
            case HELLO -> {
              AgentHello hello = message.getHello();
              collector.onHello(
                  channel,
                  hello,
                  hello.getAppName(),
                  hello.getEnv(),
                  hello.getAppVersion(),
                  hello.getAgentVersion());
            }
            case HEARTBEAT -> {
              collector.onSessionMessage(message);
              if (collector.answering()) {
                long now = System.currentTimeMillis();
                channel.send(
                    ServerMessage.newBuilder()
                        .setHeartbeatAck(
                            HeartbeatAck.newBuilder()
                                .setT1Ms(message.getHeartbeat().getT1Ms())
                                .setT2Ms(now)
                                .setT3Ms(now))
                        .build());
              }
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
              for (RejectedKey key : result.getRejectedList()) {
                rejected.put(key.getKey(), key.getCode().name());
              }
              collector.onConfigApplyResult(
                  result.getChangeId(),
                  result.getAppliedKeysList(),
                  rejected,
                  result.getRetransformedClasses());
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
                  result.getTruncated());
            }
            default -> collector.onSessionMessage(message);
          }
        }

        @Override
        public void onError(Throwable error) {
          collector.onSessionClosed(channel);
        }

        @Override
        public void onCompleted() {
          collector.onSessionClosed(channel);
          channel.complete();
        }
      };
    }
  }

  /** The server side of one session stream. */
  private static final class Session implements SessionChannel {
    private final StreamObserver<ServerMessage> out;

    Session(StreamObserver<ServerMessage> out) {
      this.out = out;
    }

    synchronized void send(ServerMessage message) {
      try {
        out.onNext(message);
      } catch (RuntimeException e) {
        // the stream ended meanwhile
      }
    }

    synchronized void complete() {
      try {
        out.onCompleted();
      } catch (RuntimeException e) {
        // already ended
      }
    }

    @Override
    public void hello(boolean accepted, long instanceId, String rejectReason) {
      ServerHello.Builder hello =
          ServerHello.newBuilder()
              .setAccepted(accepted)
              .setServerVersion("argos-fake-collector")
              .setProtocolVersion(1)
              .setServerTimeMs(System.currentTimeMillis())
              .setHeartbeatIntervalS(30);
      if (accepted) {
        hello
            .setAppId(1)
            .setInstanceId((int) instanceId)
            .setSessionId(UUID.randomUUID().toString());
      } else {
        hello.setRejectReason(RejectReason.valueOf(rejectReason));
      }
      send(ServerMessage.newBuilder().setHello(hello).build());
    }

    @Override
    public void configPush(String changeId, Map<String, String> values) {
      send(
          ServerMessage.newBuilder()
              .setConfigPush(
                  ConfigPush.newBuilder()
                      .setChangeId(changeId)
                      .putAllValues(values)
                      .setIssuedAtMs(System.currentTimeMillis()))
              .build());
    }

    @Override
    public void command(String commandId, String type, Map<String, String> args, int ttlSeconds) {
      send(
          ServerMessage.newBuilder()
              .setCommand(
                  Command.newBuilder()
                      .setCommandId(commandId)
                      .setType(CommandType.valueOf(type))
                      .setIssuedAtMs(System.currentTimeMillis())
                      .setTtlS(ttlSeconds)
                      .putAllArgs(args))
              .build());
    }

    @Override
    public synchronized void close(Status status) {
      try {
        out.onError(status.asRuntimeException());
      } catch (RuntimeException e) {
        // already ended
      }
    }
  }

  // --- conversion to harness records ------------------------------------------------------------

  /**
   * Transaction records of every transaction batch (field names of the JSONL exporter where they
   * exist; {@code cpu_time_ms} and {@code alloc_bytes} only when the agent set the optional field,
   * so that a missing value stays missing), with the steps of the matching profile and {@code env}
   * and {@code version} from the instance's hello.
   */
  @Override
  public List<TransactionRecord> transactions(
      List<ReceivedBatch> batches, Map<Long, String> dictionary, Map<Long, SessionInfo> sessions) {
    Map<Long, TransactionProfile> profiles = new LinkedHashMap<>();
    for (ReceivedBatch batch : batches) {
      if (batch.kind() == BatchKind.PROFILES && batch.body() instanceof ProfileBatch profile) {
        for (TransactionProfile p : profile.getProfilesList()) {
          profiles.put(p.getTxId(), p);
        }
      }
    }
    List<TransactionRecord> records = new ArrayList<>();
    for (ReceivedBatch batch : batches) {
      if (batch.kind() != BatchKind.TRANSACTIONS
          || !(batch.body() instanceof TransactionBatch transactions)) {
        continue;
      }
      SessionInfo session = sessions.get(batch.instanceId());
      for (io.argos.agent.transportpoc.proto.TransactionRecord r : transactions.getRecordsList()) {
        Map<String, Object> f = new LinkedHashMap<>();
        f.put("tx_id", Long.toUnsignedString(r.getTxId()));
        f.put("trace_id", HexFormat.of().formatHex(r.getTraceId().toByteArray()));
        f.put("span_id", HexFormat.of().toHexDigits(r.getSpanId()));
        f.put(
            "parent_span_id",
            r.getParentSpanId() == 0 ? null : HexFormat.of().toHexDigits(r.getParentSpanId()));
        f.put(
            "kind",
            r.getKind() == TransactionKind.TRANSACTION_KIND_UNSPECIFIED
                ? null
                : r.getKind().name());
        f.put("service", text(dictionary, r.getServiceId()));
        f.put("start_epoch_ms", r.getStartTsMs());
        f.put("elapsed_ms", r.getElapsedUs() / 1000.0);
        f.put("status", (long) r.getHttpStatus());
        f.put("error", r.getError());
        f.put("error_class", text(dictionary, r.getErrorClassId()));
        f.put("error_message", emptyToNull(r.getErrorMessage()));
        f.put("http_method", emptyToNull(r.getHttpMethod()));
        f.put("path", emptyToNull(r.getUrlPath()));
        f.put("remote_ip", ip(r.getRemoteIp()));
        f.put("user_id", emptyToNull(r.getUserId()));
        f.put("thread", text(dictionary, r.getThreadNameId()));
        f.put("virtual_thread", r.getVirtualThread());
        if (r.hasCpuTimeUs()) {
          f.put("cpu_time_ms", r.getCpuTimeUs() / 1000.0);
        }
        if (r.hasAllocBytes()) {
          f.put("alloc_bytes", r.getAllocBytes());
        }
        f.put("sql_count", (long) r.getSqlCount());
        f.put("ext_count", (long) r.getExtCount());
        f.put("env", session == null ? null : emptyToNull(session.env()));
        String version = text(dictionary, r.getAppVersionId());
        f.put(
            "version",
            version != null ? version : session == null ? null : emptyToNull(session.appVersion()));
        f.put("instance_id", batch.instanceId());
        f.put("batch_seq", batch.seq());
        List<Object> flags = new ArrayList<>();
        if (r.getStreaming()) {
          flags.add("streaming");
        }
        if (r.getTimedOut()) {
          flags.add("timeout");
        }
        f.put("flags", flags);
        f.put("steps", steps(profiles.get(r.getTxId()), dictionary));
        records.add(new TransactionRecord(f, f.toString(), SOURCE));
      }
    }
    return records;
  }

  private static List<Object> steps(TransactionProfile profile, Map<Long, String> dictionary) {
    List<Object> steps = new ArrayList<>();
    if (profile == null) {
      return steps;
    }
    for (ProfileStep step : profile.getStepsList()) {
      Map<String, Object> s = new LinkedHashMap<>();
      s.put("seq", (long) step.getSeq());
      s.put("parent", step.getParentSeq() == 0 ? -1L : (long) step.getParentSeq());
      s.put("type", stepType(step.getType().name()));
      s.put("name", text(dictionary, step.getNameId()));
      s.put("target", text(dictionary, step.getTargetId()));
      s.put("start_offset_ms", step.getStartOffsetUs() / 1000.0);
      s.put("elapsed_ms", step.getElapsedUs() / 1000.0);
      s.put("fetch_ms", step.getFetchUs() / 1000.0);
      s.put("rows", (long) step.getRows());
      s.put("status", (long) step.getStatus());
      s.put("error", step.getError());
      s.put("detail", emptyToNull(step.getDetail()));
      steps.add(s);
    }
    return steps;
  }

  /** PoC enum names to the agent's {@code StepType} names (C-1). */
  static String stepType(String poc) {
    return switch (poc) {
      case "HTTP_CALL" -> "HTTP";
      case "MESSAGE_STEP" -> "MESSAGE";
      case "STEP_TYPE_UNSPECIFIED" -> null;
      default -> poc;
    };
  }

  @Override
  public List<Collected.SqlStat> sqlStats(
      List<ReceivedBatch> batches, Map<Long, String> dictionary) {
    Map<String, long[]> counts = new LinkedHashMap<>();
    Map<String, Double> sums = new LinkedHashMap<>();
    for (ReceivedBatch batch : batches) {
      if (batch.kind() == BatchKind.STATS && batch.body() instanceof StatBatch stats) {
        for (SqlStat stat : stats.getSqlList()) {
          String sql = text(dictionary, stat.getSqlId());
          String key = sql != null ? sql : "#" + Long.toUnsignedString(stat.getSqlId());
          long[] count = counts.computeIfAbsent(key, k -> new long[2]);
          count[0] += stat.getCount();
          count[1] += stat.getErrorCount();
          sums.merge(key, stat.getSumUs() / 1000.0, Double::sum);
        }
      }
    }
    List<Collected.SqlStat> stats = new ArrayList<>();
    counts.forEach(
        (sql, count) -> stats.add(new Collected.SqlStat(sql, count[0], count[1], sums.get(sql))));
    return stats;
  }

  @Override
  public List<Collected.MetricPoint> metrics(List<ReceivedBatch> batches) {
    List<Collected.MetricPoint> points = new ArrayList<>();
    for (ReceivedBatch batch : batches) {
      if (batch.kind() != BatchKind.METRICS || !(batch.body() instanceof MetricBatch metrics)) {
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
                sample.getTsMs()));
      }
    }
    return points;
  }

  @Override
  public Map<String, Long> counters(List<ReceivedBatch> batches) {
    Map<String, Long> counters = new LinkedHashMap<>();
    for (ReceivedBatch batch : batches) {
      if (batch.kind() == BatchKind.STATS && batch.body() instanceof StatBatch stats) {
        for (CounterDelta delta : stats.getCountersList()) {
          counters.merge(delta.getName(), delta.getDelta(), Long::sum);
        }
      }
    }
    return counters;
  }

  /** {@code name{k="v",...}} → {@code name}. */
  static String baseName(String series) {
    int brace = series.indexOf('{');
    return brace < 0 ? series : series.substring(0, brace);
  }

  /** {@code name{k="v",...}} → labels (the PoC series carries labels in its name). */
  static Map<String, String> labels(String series) {
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

  private static String text(Map<Long, String> dictionary, long id) {
    if (id == 0) {
      return null;
    }
    String text = dictionary.get(id);
    return text != null ? text : "#" + Long.toUnsignedString(id);
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
