/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.collector.limits;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.protobuf.ByteString;
import io.argos.proto.ProtocolLimits;
import io.argos.proto.v1.ActiveTransaction;
import io.argos.proto.v1.ActiveTransactionSnapshot;
import io.argos.proto.v1.AgentHello;
import io.argos.proto.v1.BatchHeader;
import io.argos.proto.v1.CounterDelta;
import io.argos.proto.v1.DictionaryEntries;
import io.argos.proto.v1.DictionaryEntry;
import io.argos.proto.v1.DictionaryKind;
import io.argos.proto.v1.ExtStat;
import io.argos.proto.v1.InstanceUpdate;
import io.argos.proto.v1.KeyValue;
import io.argos.proto.v1.MetricBatch;
import io.argos.proto.v1.MetricKind;
import io.argos.proto.v1.MetricSeries;
import io.argos.proto.v1.ProfileBatch;
import io.argos.proto.v1.ProfileStep;
import io.argos.proto.v1.SqlStat;
import io.argos.proto.v1.StatBatch;
import io.argos.proto.v1.StepType;
import io.argos.proto.v1.TransactionBatch;
import io.argos.proto.v1.TransactionKind;
import io.argos.proto.v1.TransactionProfile;
import io.argos.proto.v1.TransactionRecord;
import io.grpc.Status;
import java.util.Collections;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * T-SV-012 unit part: every cap of 11 §4.4 / 12 §3.3 at its boundary (the cap passes, one more is
 * refused with the right code and reason), format errors, configured caps, and the rule that enums
 * never reject (ADR-0010 C-143 (1)).
 */
@Tag("req:SEC-11")
@Tag("req:F-S33")
class LimitsCheckerTest {

  private static final int INSTANCE = 7;
  private static final LimitsChecker CHECKER = new LimitsChecker(CollectorLimits.defaults());

  static BatchHeader header() {
    return BatchHeader.newBuilder().setInstanceId(INSTANCE).setAgentEpoch(1).setSeq(1).build();
  }

  static TransactionRecord.Builder record() {
    return TransactionRecord.newBuilder()
        .setTxId(1)
        .setTraceId(ByteString.copyFrom(new byte[16]))
        .setKind(TransactionKind.HTTP)
        .setServiceId(11);
  }

  static TransactionBatch tx(int records, UnaryOperator<TransactionRecord.Builder> change) {
    TransactionRecord r = change.apply(record()).build();
    return TransactionBatch.newBuilder()
        .setHeader(header())
        .addAllRecords(Collections.nCopies(records, r))
        .build();
  }

  static String bytes(int n) {
    return "a".repeat(n);
  }

  static void passes(Violation v) {
    assertThat(v).isNull();
  }

  static void refused(Violation v, Status.Code code, String reason) {
    assertThat(v).isNotNull();
    assertThat(v.code()).isEqualTo(code);
    assertThat(v.reason()).isEqualTo(reason);
  }

  @Nested
  @DisplayName("class 2: items per batch")
  class Items {

    @Test
    @DisplayName(
        "T-SV-012 transactions: 5,000 records pass, 5,001 are RESOURCE_EXHAUSTED batch_items")
    void records() {
      passes(CHECKER.check(tx(5_000, b -> b), INSTANCE));
      refused(
          CHECKER.check(tx(5_001, b -> b), INSTANCE),
          Status.Code.RESOURCE_EXHAUSTED,
          Violation.BATCH_ITEMS);
    }

    @Test
    @DisplayName("T-SV-012 profiles: 50,000 steps over all profiles pass, 50,001 are refused")
    void steps() {
      ProfileStep step = ProfileStep.newBuilder().setSeq(1).setType(StepType.METHOD).build();
      TransactionProfile full =
          TransactionProfile.newBuilder()
              .setTxId(1)
              .setKind(TransactionKind.HTTP)
              .addAllSteps(Collections.nCopies(1_000, step))
              .build();
      ProfileBatch ok =
          ProfileBatch.newBuilder()
              .setHeader(header())
              .addAllProfiles(Collections.nCopies(50, full))
              .build();
      passes(CHECKER.check(ok, INSTANCE));
      ProfileBatch over =
          ok.toBuilder().addProfiles(full.toBuilder().clearSteps().addSteps(step)).build();
      refused(CHECKER.check(over, INSTANCE), Status.Code.RESOURCE_EXHAUSTED, Violation.BATCH_ITEMS);
    }

    @Test
    @DisplayName("T-SV-012 metrics: 500 series pass, 501 are refused; samples have no item cap")
    void series() {
      MetricBatch.Builder b = MetricBatch.newBuilder().setHeader(header());
      for (int i = 0; i < 500; i++) {
        b.addSeries(
            MetricSeries.newBuilder().setIndex(i).setName("m" + i).setKind(MetricKind.GAUGE));
      }
      passes(CHECKER.check(b.build(), INSTANCE));
      b.addSeries(
          MetricSeries.newBuilder().setIndex(500).setName("m500").setKind(MetricKind.GAUGE));
      refused(
          CHECKER.check(b.build(), INSTANCE),
          Status.Code.RESOURCE_EXHAUSTED,
          Violation.BATCH_ITEMS);
    }

    @Test
    @DisplayName(
        "T-SV-012 statistics: 5,000 SQL and 1,000 outgoing-call rows pass, one more is refused")
    void stats() {
      SqlStat sql = SqlStat.newBuilder().setSqlId(1).setKind(TransactionKind.HTTP).build();
      ExtStat ext = ExtStat.newBuilder().setHostId(1).setKind(TransactionKind.HTTP).build();
      StatBatch ok =
          StatBatch.newBuilder()
              .setHeader(header())
              .addAllSql(Collections.nCopies(5_000, sql))
              .addAllExt(Collections.nCopies(1_000, ext))
              .build();
      passes(CHECKER.check(ok, INSTANCE));
      refused(
          CHECKER.check(ok.toBuilder().addSql(sql).build(), INSTANCE),
          Status.Code.RESOURCE_EXHAUSTED,
          Violation.BATCH_ITEMS);
      refused(
          CHECKER.check(ok.toBuilder().addExt(ext).build(), INSTANCE),
          Status.Code.RESOURCE_EXHAUSTED,
          Violation.BATCH_ITEMS);
    }

    @Test
    @DisplayName("T-SV-012 active snapshot: 500 items pass, 501 are refused")
    void active() {
      ActiveTransaction item = ActiveTransaction.newBuilder().setTxId(1).build();
      ActiveTransactionSnapshot ok =
          ActiveTransactionSnapshot.newBuilder()
              .setHeader(header())
              .addAllItems(Collections.nCopies(500, item))
              .build();
      passes(CHECKER.check(ok, INSTANCE));
      refused(
          CHECKER.check(ok.toBuilder().addItems(item).build(), INSTANCE),
          Status.Code.RESOURCE_EXHAUSTED,
          Violation.BATCH_ITEMS);
    }

    @Test
    @DisplayName("T-SV-012 dictionary: 1,000 entries per message pass, 1,001 are refused")
    void dictionary() {
      DictionaryEntry e =
          DictionaryEntry.newBuilder()
              .setKind(DictionaryKind.SERVICE)
              .setHash(5)
              .setText("x")
              .build();
      DictionaryEntries ok =
          DictionaryEntries.newBuilder().addAllEntries(Collections.nCopies(1_000, e)).build();
      passes(CHECKER.check(ok));
      refused(
          CHECKER.check(ok.toBuilder().addEntries(e).build()),
          Status.Code.RESOURCE_EXHAUSTED,
          Violation.BATCH_ITEMS);
    }

    @Test
    @DisplayName("T-SV-012 configured caps (argos.collector.limits.*) replace the 11 §4.2 defaults")
    void configured() {
      CollectorLimits d = CollectorLimits.defaults();
      LimitsChecker small =
          new LimitsChecker(
              new CollectorLimits(
                  d.maxMessageBytes(),
                  10,
                  d.maxStepsPerBatch(),
                  100,
                  d.maxStringBytes(),
                  d.maxErrorFrames(),
                  d.maxStackFrames(),
                  d.maxActiveItems(),
                  d.maxSeriesPerBatch(),
                  d.maxDictionaryEntriesPerHour()));
      passes(small.check(tx(10, b -> b), INSTANCE));
      refused(
          small.check(tx(11, b -> b), INSTANCE),
          Status.Code.RESOURCE_EXHAUSTED,
          Violation.BATCH_ITEMS);
      ProfileBatch detail =
          ProfileBatch.newBuilder()
              .setHeader(header())
              .addProfiles(
                  TransactionProfile.newBuilder()
                      .setKind(TransactionKind.HTTP)
                      .addSteps(
                          ProfileStep.newBuilder().setType(StepType.SQL).setDetail(bytes(101))))
              .build();
      refused(
          small.check(detail, INSTANCE), Status.Code.RESOURCE_EXHAUSTED, Violation.STRING_LENGTH);
    }
  }

  @Nested
  @DisplayName("class 3: string lengths (UTF-8 bytes)")
  class Strings {

    void boundary(
        int cap,
        java.util.function.BiFunction<TransactionRecord.Builder, String, TransactionRecord.Builder>
            set) {
      passes(CHECKER.check(tx(1, b -> set.apply(b, bytes(cap))), INSTANCE));
      refused(
          CHECKER.check(tx(1, b -> set.apply(b, bytes(cap + 1))), INSTANCE),
          Status.Code.RESOURCE_EXHAUSTED,
          Violation.STRING_LENGTH);
    }

    @Test
    @DisplayName(
        "T-SV-012 url_path 2 KiB, error_message 1 KiB, user_id and request_id 128 B, http_method 16 B")
    void recordStrings() {
      boundary(ProtocolLimits.MAX_URL_PATH_BYTES, TransactionRecord.Builder::setUrlPath);
      boundary(ProtocolLimits.MAX_ERROR_MESSAGE_BYTES, TransactionRecord.Builder::setErrorMessage);
      boundary(ProtocolLimits.MAX_USER_ID_BYTES, TransactionRecord.Builder::setUserId);
      boundary(ProtocolLimits.MAX_REQUEST_ID_BYTES, TransactionRecord.Builder::setRequestId);
      boundary(ProtocolLimits.MAX_HTTP_METHOD_BYTES, TransactionRecord.Builder::setHttpMethod);
    }

    @Test
    @DisplayName(
        "T-SV-012 multi-byte characters count as bytes: 1,024 bytes of 'é' pass, 1,026 do not")
    void utf8Bytes() {
      passes(CHECKER.check(tx(1, b -> b.setErrorMessage("é".repeat(512))), INSTANCE));
      refused(
          CHECKER.check(tx(1, b -> b.setErrorMessage("é".repeat(513))), INSTANCE),
          Status.Code.RESOURCE_EXHAUSTED,
          Violation.STRING_LENGTH);
    }

    @Test
    @DisplayName("T-SV-012 header key 128 B and value 1 KiB")
    void headers() {
      passes(
          CHECKER.check(
              tx(
                  1,
                  b ->
                      b.addHeaders(
                          KeyValue.newBuilder().setKey(bytes(128)).setValue(bytes(1_024)))),
              INSTANCE));
      refused(
          CHECKER.check(
              tx(1, b -> b.addHeaders(KeyValue.newBuilder().setKey(bytes(129)))), INSTANCE),
          Status.Code.RESOURCE_EXHAUSTED,
          Violation.STRING_LENGTH);
      refused(
          CHECKER.check(
              tx(1, b -> b.addHeaders(KeyValue.newBuilder().setKey("k").setValue(bytes(1_025)))),
              INSTANCE),
          Status.Code.RESOURCE_EXHAUSTED,
          Violation.STRING_LENGTH);
    }

    @Test
    @DisplayName("T-SV-012 step detail 4 KiB (max-detail-bytes)")
    void detail() {
      for (int n : new int[] {4_096, 4_097}) {
        ProfileBatch b =
            ProfileBatch.newBuilder()
                .setHeader(header())
                .addProfiles(
                    TransactionProfile.newBuilder()
                        .setKind(TransactionKind.HTTP)
                        .addSteps(
                            ProfileStep.newBuilder().setType(StepType.SQL).setDetail(bytes(n))))
                .build();
        if (n == 4_096) {
          passes(CHECKER.check(b, INSTANCE));
        } else {
          refused(
              CHECKER.check(b, INSTANCE), Status.Code.RESOURCE_EXHAUSTED, Violation.STRING_LENGTH);
        }
      }
    }

    @Test
    @DisplayName("T-SV-012 metric and counter names 256 B")
    void metricNames() {
      MetricBatch ok =
          MetricBatch.newBuilder()
              .setHeader(header())
              .addSeries(MetricSeries.newBuilder().setName(bytes(256)).setKind(MetricKind.GAUGE))
              .build();
      passes(CHECKER.check(ok, INSTANCE));
      refused(
          CHECKER.check(
              ok.toBuilder()
                  .addSeries(MetricSeries.newBuilder().setIndex(1).setName(bytes(257)))
                  .build(),
              INSTANCE),
          Status.Code.RESOURCE_EXHAUSTED,
          Violation.STRING_LENGTH);
      StatBatch counters =
          StatBatch.newBuilder()
              .setHeader(header())
              .addCounters(CounterDelta.newBuilder().setName(bytes(257)).setDelta(1))
              .build();
      refused(
          CHECKER.check(counters, INSTANCE),
          Status.Code.RESOURCE_EXHAUSTED,
          Violation.STRING_LENGTH);
    }

    @Test
    @DisplayName("T-SV-012 dictionary text: SQL_TEXT 64 KiB, other kinds 8 KiB")
    void dictionaryText() {
      for (DictionaryKind kind :
          new DictionaryKind[] {DictionaryKind.SQL_TEXT, DictionaryKind.SERVICE}) {
        int cap =
            kind == DictionaryKind.SQL_TEXT
                ? ProtocolLimits.MAX_SQL_TEXT_BYTES
                : ProtocolLimits.MAX_DICTIONARY_TEXT_BYTES;
        passes(CHECKER.check(entries(kind, bytes(cap))));
        refused(
            CHECKER.check(entries(kind, bytes(cap + 1))),
            Status.Code.RESOURCE_EXHAUSTED,
            Violation.STRING_LENGTH);
      }
    }

    private DictionaryEntries entries(DictionaryKind kind, String text) {
      return DictionaryEntries.newBuilder()
          .addEntries(DictionaryEntry.newBuilder().setKind(kind).setHash(9).setText(text))
          .build();
    }

    @Test
    @DisplayName("T-SV-012 Hello: app_name 128 B, env 32 B, other strings 2 KiB (max-string-bytes)")
    void hello() {
      AgentHello ok =
          AgentHello.newBuilder()
              .setAppName(bytes(128))
              .setEnv(bytes(32))
              .setInstanceName(bytes(2_048))
              .addRemoteConfigAllow(bytes(2_048))
              .build();
      passes(CHECKER.check(ok));
      refused(
          CHECKER.check(ok.toBuilder().setAppName(bytes(129)).build()),
          Status.Code.RESOURCE_EXHAUSTED,
          Violation.STRING_LENGTH);
      refused(
          CHECKER.check(ok.toBuilder().setEnv(bytes(33)).build()),
          Status.Code.RESOURCE_EXHAUSTED,
          Violation.STRING_LENGTH);
      refused(
          CHECKER.check(ok.toBuilder().setHost(bytes(2_049)).build()),
          Status.Code.RESOURCE_EXHAUSTED,
          Violation.STRING_LENGTH);
      refused(
          CHECKER.check(ok.toBuilder().addInstrumentationModules(bytes(2_049)).build()),
          Status.Code.RESOURCE_EXHAUSTED,
          Violation.STRING_LENGTH);
      passes(CHECKER.check(InstanceUpdate.newBuilder().setInstanceName(bytes(2_048)).build()));
      refused(
          CHECKER.check(InstanceUpdate.newBuilder().addConnectors(bytes(2_049)).build()),
          Status.Code.RESOURCE_EXHAUSTED,
          Violation.STRING_LENGTH);
    }
  }

  @Nested
  @DisplayName("class 4: repeated lengths")
  class Repeated {

    ProfileBatch frames(StepType type, int count) {
      ProfileStep.Builder step = ProfileStep.newBuilder().setType(type);
      for (int i = 1; i <= count; i++) {
        step.addFrameIds(i);
      }
      return ProfileBatch.newBuilder()
          .setHeader(header())
          .addProfiles(TransactionProfile.newBuilder().setKind(TransactionKind.HTTP).addSteps(step))
          .build();
    }

    @Test
    @DisplayName("T-SV-012 frame_ids: 30 for ERROR, 64 for STACK")
    void frameIds() {
      passes(CHECKER.check(frames(StepType.ERROR, 30), INSTANCE));
      refused(
          CHECKER.check(frames(StepType.ERROR, 31), INSTANCE),
          Status.Code.RESOURCE_EXHAUSTED,
          Violation.REPEATED_LENGTH);
      passes(CHECKER.check(frames(StepType.STACK, 64), INSTANCE));
      refused(
          CHECKER.check(frames(StepType.STACK, 65), INSTANCE),
          Status.Code.RESOURCE_EXHAUSTED,
          Violation.REPEATED_LENGTH);
    }

    @Test
    @DisplayName("T-SV-012 sql_ids 64 and headers 10 per record")
    void sqlIdsAndHeaders() {
      passes(CHECKER.check(tx(1, b -> addSqlIds(b, 64)), INSTANCE));
      refused(
          CHECKER.check(tx(1, b -> addSqlIds(b, 65)), INSTANCE),
          Status.Code.RESOURCE_EXHAUSTED,
          Violation.REPEATED_LENGTH);
      passes(CHECKER.check(tx(1, b -> addHeaders(b, 10)), INSTANCE));
      refused(
          CHECKER.check(tx(1, b -> addHeaders(b, 11)), INSTANCE),
          Status.Code.RESOURCE_EXHAUSTED,
          Violation.REPEATED_LENGTH);
    }

    @Test
    @DisplayName("T-SV-012 statistics buckets: at most 15 (DSN-13-03)")
    void buckets() {
      SqlStat.Builder sql = SqlStat.newBuilder().setSqlId(1).setKind(TransactionKind.HTTP);
      for (int i = 0; i < 15; i++) {
        sql.addBuckets(1);
      }
      StatBatch ok = StatBatch.newBuilder().setHeader(header()).addSql(sql).build();
      passes(CHECKER.check(ok, INSTANCE));
      refused(
          CHECKER.check(
              StatBatch.newBuilder().setHeader(header()).addSql(sql.addBuckets(1)).build(),
              INSTANCE),
          Status.Code.RESOURCE_EXHAUSTED,
          Violation.REPEATED_LENGTH);
    }

    private StatBatch counters(int n) {
      StatBatch.Builder b = StatBatch.newBuilder().setHeader(header());
      for (int i = 0; i < n; i++) {
        b.addCounters(CounterDelta.newBuilder().setName("c" + i).setDelta(1));
      }
      return b.build();
    }

    @Test
    @Tag("req:C-157")
    @DisplayName(
        "T-SV-012 C-157 (2) StatBatch.counters: 500 pass, 501 refuse the batch (RESOURCE_EXHAUSTED,"
            + " batch_items)")
    void statCounters() {
      assertThat(LimitsChecker.MAX_STAT_COUNTERS_PER_BATCH).isEqualTo(500);
      passes(CHECKER.check(counters(500), INSTANCE));
      refused(
          CHECKER.check(counters(501), INSTANCE),
          Status.Code.RESOURCE_EXHAUSTED,
          Violation.BATCH_ITEMS);
    }

    @Test
    @Tag("req:C-157")
    @DisplayName(
        "T-SV-012 C-157 (2) AgentHello.remote_config_allow and instrumentation_modules: 256 pass,"
            + " 257 refuse the Hello (RESOURCE_EXHAUSTED, repeated_length)")
    void helloLists() {
      AgentHello.Builder allow = AgentHello.newBuilder().setAppName("a").setEnv("prod");
      AgentHello.Builder modules = AgentHello.newBuilder().setAppName("a").setEnv("prod");
      for (int i = 0; i < 256; i++) {
        allow.addRemoteConfigAllow("key." + i);
        modules.addInstrumentationModules("module-" + i);
      }
      assertThat(LimitsChecker.MAX_REMOTE_CONFIG_ALLOW).isEqualTo(256);
      assertThat(LimitsChecker.MAX_INSTRUMENTATION_MODULES).isEqualTo(256);
      passes(CHECKER.check(allow.build()));
      passes(CHECKER.check(modules.build()));
      refused(
          CHECKER.check(allow.addRemoteConfigAllow("key.256").build()),
          Status.Code.RESOURCE_EXHAUSTED,
          Violation.REPEATED_LENGTH);
      refused(
          CHECKER.check(modules.addInstrumentationModules("module-256").build()),
          Status.Code.RESOURCE_EXHAUSTED,
          Violation.REPEATED_LENGTH);
    }

    @Test
    @Tag("req:C-157")
    @DisplayName(
        "T-SV-012 C-157 (2) InstanceUpdate.connectors: 256 pass, 257 refuse the update"
            + " (RESOURCE_EXHAUSTED, repeated_length)")
    void connectors() {
      InstanceUpdate.Builder u = InstanceUpdate.newBuilder();
      for (int i = 0; i < 256; i++) {
        u.addConnectors("http-nio-" + i);
      }
      assertThat(LimitsChecker.MAX_CONNECTORS).isEqualTo(256);
      passes(CHECKER.check(u.build()));
      refused(
          CHECKER.check(u.addConnectors("http-nio-256").build()),
          Status.Code.RESOURCE_EXHAUSTED,
          Violation.REPEATED_LENGTH);
    }

    private TransactionRecord.Builder addSqlIds(TransactionRecord.Builder b, int n) {
      for (int i = 1; i <= n; i++) {
        b.addSqlIds(i);
      }
      return b;
    }

    private TransactionRecord.Builder addHeaders(TransactionRecord.Builder b, int n) {
      for (int i = 0; i < n; i++) {
        b.addHeaders(KeyValue.newBuilder().setKey("h" + i).setValue("v"));
      }
      return b;
    }
  }

  @Nested
  @DisplayName("class 5: format (INVALID_ARGUMENT)")
  class Format {

    @Test
    @DisplayName("T-SV-012 trace_id must be 16 bytes")
    void traceId() {
      for (int n : new int[] {0, 15, 17}) {
        refused(
            CHECKER.check(tx(1, b -> b.setTraceId(ByteString.copyFrom(new byte[n]))), INSTANCE),
            Status.Code.INVALID_ARGUMENT,
            Violation.FORMAT);
      }
    }

    @Test
    @DisplayName(
        "T-SV-012 dictionary hash 0: service_id, sql_ids, frame_ids, SqlStat.sql_id, ExtStat.host_id, DictionaryEntry.hash")
    void hashZero() {
      refused(
          CHECKER.check(tx(1, b -> b.setServiceId(0)), INSTANCE),
          Status.Code.INVALID_ARGUMENT,
          Violation.FORMAT);
      refused(
          CHECKER.check(tx(1, b -> b.addSqlIds(0)), INSTANCE),
          Status.Code.INVALID_ARGUMENT,
          Violation.FORMAT);
      ProfileBatch frame =
          ProfileBatch.newBuilder()
              .setHeader(header())
              .addProfiles(
                  TransactionProfile.newBuilder()
                      .setKind(TransactionKind.HTTP)
                      .addSteps(ProfileStep.newBuilder().setType(StepType.ERROR).addFrameIds(0)))
              .build();
      refused(CHECKER.check(frame, INSTANCE), Status.Code.INVALID_ARGUMENT, Violation.FORMAT);
      refused(
          CHECKER.check(
              StatBatch.newBuilder()
                  .setHeader(header())
                  .addSql(SqlStat.newBuilder().setSqlId(0))
                  .build(),
              INSTANCE),
          Status.Code.INVALID_ARGUMENT,
          Violation.FORMAT);
      refused(
          CHECKER.check(
              StatBatch.newBuilder()
                  .setHeader(header())
                  .addExt(ExtStat.newBuilder().setHostId(0))
                  .build(),
              INSTANCE),
          Status.Code.INVALID_ARGUMENT,
          Violation.FORMAT);
      refused(
          CHECKER.check(
              DictionaryEntries.newBuilder()
                  .addEntries(
                      DictionaryEntry.newBuilder().setKind(DictionaryKind.SERVICE).setHash(0))
                  .build()),
          Status.Code.INVALID_ARGUMENT,
          Violation.FORMAT);
    }

    @Test
    @DisplayName("T-SV-012 remote_ip must be empty, 4 or 16 bytes")
    void remoteIp() {
      for (int n : new int[] {0, 4, 16}) {
        passes(
            CHECKER.check(tx(1, b -> b.setRemoteIp(ByteString.copyFrom(new byte[n]))), INSTANCE));
      }
      refused(
          CHECKER.check(tx(1, b -> b.setRemoteIp(ByteString.copyFrom(new byte[5]))), INSTANCE),
          Status.Code.INVALID_ARGUMENT,
          Violation.FORMAT);
    }

    @Test
    @DisplayName(
        "T-SV-012 the batch header needs a seq, and its instance (when set) is the session's")
    void batchHeader() {
      TransactionBatch noSeq =
          tx(1, b -> b).toBuilder()
              .setHeader(BatchHeader.newBuilder().setInstanceId(INSTANCE))
              .build();
      refused(CHECKER.check(noSeq, INSTANCE), Status.Code.INVALID_ARGUMENT, Violation.FORMAT);
      TransactionBatch other =
          tx(1, b -> b).toBuilder().setHeader(header().toBuilder().setInstanceId(8)).build();
      refused(CHECKER.check(other, INSTANCE), Status.Code.INVALID_ARGUMENT, Violation.FORMAT);
      TransactionBatch unset =
          tx(1, b -> b).toBuilder().setHeader(header().toBuilder().setInstanceId(0)).build();
      passes(CHECKER.check(unset, INSTANCE));
    }

    @Test
    @DisplayName("C-143 (1) an unspecified or unknown enum never rejects the batch")
    void enumsDoNotReject() {
      passes(
          CHECKER.check(
              tx(1, b -> b.setKind(TransactionKind.TRANSACTION_KIND_UNSPECIFIED)), INSTANCE));
      passes(CHECKER.check(tx(1, b -> b.setKindValue(99)), INSTANCE));
      ProfileBatch steps =
          ProfileBatch.newBuilder()
              .setHeader(header())
              .addProfiles(
                  TransactionProfile.newBuilder()
                      .setKindValue(42)
                      .addSteps(ProfileStep.newBuilder().setTypeValue(77)))
              .build();
      passes(CHECKER.check(steps, INSTANCE));
    }
  }

  @Test
  @DisplayName("T-SV-012 default limits equal the 11 §4.2 defaults of ProtocolLimits")
  void defaults() {
    assertThat(CollectorLimits.defaults().toProto()).isEqualTo(ProtocolLimits.defaults());
  }
}
