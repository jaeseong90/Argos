/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.collector.limits;

import com.google.protobuf.ByteString;
import io.argos.proto.LatencyBuckets;
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
import io.argos.proto.v1.MetricSeries;
import io.argos.proto.v1.ProfileBatch;
import io.argos.proto.v1.ProfileStep;
import io.argos.proto.v1.SqlStat;
import io.argos.proto.v1.StatBatch;
import io.argos.proto.v1.StepType;
import io.argos.proto.v1.TransactionBatch;
import io.argos.proto.v1.TransactionProfile;
import io.argos.proto.v1.TransactionRecord;
import java.util.List;
import java.util.Objects;

/**
 * The second check of every received message after decoding (11 §4.4, 12 §3.3, SEC-11): items per
 * batch, string lengths, repeated lengths and format. The message size (class 1) is checked before
 * decoding by the transport ({@code maxInboundMessageSize} and the bounded decompressor).
 *
 * <p>A violation rejects the whole message (ADR-0010 C-130 (2)): caps are {@code
 * RESOURCE_EXHAUSTED}, format errors {@code INVALID_ARGUMENT}. Enum values never reject (ADR-0010
 * C-143 (1)); the required-enum filter removes such items afterwards. Timestamps are not checked
 * here: the D-15 guard applies per record after the WAL (12 §3.5, C-129 (4)).
 *
 * <p>Byte caps count the UTF-8 bytes as they arrived ({@code getXxxBytes().size()}, no decoding).
 * Every method returns {@code null} when the message passes. Stateless and thread-safe.
 */
public final class LimitsChecker {

  /**
   * {@code StatBatch.counters} per batch (ADR-0010 C-157 (2)); the agent sends at most 113. Above
   * it the batch is refused as a whole ({@code RESOURCE_EXHAUSTED}, {@code batch_items}). A server
   * constant until {@code ProtocolLimits}, proto/README.md and 11 §4.4 carry it.
   */
  public static final int MAX_STAT_COUNTERS_PER_BATCH = 500;

  /**
   * {@code AgentHello.remote_config_allow} entries (C-157 (2); the agent sends a fixed key list).
   * Above it the Hello is refused ({@code REJECT_REASON_UNSPECIFIED}, {@code RESOURCE_EXHAUSTED},
   * {@code repeated_length}).
   */
  public static final int MAX_REMOTE_CONFIG_ALLOW = 256;

  /** {@code AgentHello.instrumentation_modules} entries (C-157 (2); a fixed module list). */
  public static final int MAX_INSTRUMENTATION_MODULES = 256;

  /**
   * {@code InstanceUpdate.connectors} entries (C-157 (2)); above it the update is dropped and
   * counted like any other violating session message ({@code repeated_length}).
   */
  public static final int MAX_CONNECTORS = 256;

  private final CollectorLimits limits;

  public LimitsChecker(CollectorLimits limits) {
    this.limits = Objects.requireNonNull(limits, "limits");
  }

  /** The configured caps. */
  public CollectorLimits limits() {
    return limits;
  }

  /** {@code SendTransactions}. */
  public Violation check(TransactionBatch batch, int sessionInstanceId) {
    Violation v = header(batch.getHeader(), sessionInstanceId);
    if (v != null) {
      return v;
    }
    if (batch.getRecordsCount() > limits.maxTransactionsPerBatch()) {
      return items("records", batch.getRecordsCount(), limits.maxTransactionsPerBatch());
    }
    for (TransactionRecord r : batch.getRecordsList()) {
      v = record(r);
      if (v != null) {
        return v;
      }
    }
    return null;
  }

  /** {@code SendProfiles}. */
  public Violation check(ProfileBatch batch, int sessionInstanceId) {
    Violation v = header(batch.getHeader(), sessionInstanceId);
    if (v != null) {
      return v;
    }
    long steps = 0;
    for (TransactionProfile p : batch.getProfilesList()) {
      steps += p.getStepsCount();
    }
    if (steps > limits.maxStepsPerBatch()) {
      return items("steps", steps, limits.maxStepsPerBatch());
    }
    for (TransactionProfile p : batch.getProfilesList()) {
      for (ProfileStep s : p.getStepsList()) {
        v = step(s);
        if (v != null) {
          return v;
        }
      }
    }
    return null;
  }

  /** {@code SendMetrics}. */
  public Violation check(MetricBatch batch, int sessionInstanceId) {
    Violation v = header(batch.getHeader(), sessionInstanceId);
    if (v != null) {
      return v;
    }
    if (batch.getSeriesCount() > limits.maxSeriesPerBatch()) {
      return items("series", batch.getSeriesCount(), limits.maxSeriesPerBatch());
    }
    for (MetricSeries s : batch.getSeriesList()) {
      v = string("series.name", s.getNameBytes(), ProtocolLimits.MAX_METRIC_NAME_BYTES);
      if (v != null) {
        return v;
      }
    }
    return null;
  }

  /** {@code SendStats}. */
  public Violation check(StatBatch batch, int sessionInstanceId) {
    Violation v = header(batch.getHeader(), sessionInstanceId);
    if (v != null) {
      return v;
    }
    if (batch.getSqlCount() > ProtocolLimits.MAX_SQL_STATS_PER_BATCH) {
      return items("sql", batch.getSqlCount(), ProtocolLimits.MAX_SQL_STATS_PER_BATCH);
    }
    if (batch.getExtCount() > ProtocolLimits.MAX_EXT_STATS_PER_BATCH) {
      return items("ext", batch.getExtCount(), ProtocolLimits.MAX_EXT_STATS_PER_BATCH);
    }
    if (batch.getCountersCount() > MAX_STAT_COUNTERS_PER_BATCH) {
      return items("counters", batch.getCountersCount(), MAX_STAT_COUNTERS_PER_BATCH);
    }
    for (SqlStat s : batch.getSqlList()) {
      if (s.getSqlId() == 0) {
        return hashZero("sql.sql_id");
      }
      v = repeated("sql.buckets", s.getBucketsCount(), LatencyBuckets.COUNT);
      if (v != null) {
        return v;
      }
    }
    for (ExtStat s : batch.getExtList()) {
      if (s.getHostId() == 0) {
        return hashZero("ext.host_id");
      }
      v = repeated("ext.buckets", s.getBucketsCount(), LatencyBuckets.COUNT);
      if (v != null) {
        return v;
      }
    }
    for (CounterDelta c : batch.getCountersList()) {
      v = string("counters.name", c.getNameBytes(), ProtocolLimits.MAX_METRIC_NAME_BYTES);
      if (v != null) {
        return v;
      }
    }
    return null;
  }

  /** {@code SendActiveSnapshot}. */
  public Violation check(ActiveTransactionSnapshot snapshot, int sessionInstanceId) {
    Violation v = header(snapshot.getHeader(), sessionInstanceId);
    if (v != null) {
      return v;
    }
    if (snapshot.getItemsCount() > limits.maxActiveItems()) {
      return items("items", snapshot.getItemsCount(), limits.maxActiveItems());
    }
    for (ActiveTransaction a : snapshot.getItemsList()) {
      v = string("items.user_id", a.getUserIdBytes(), ProtocolLimits.MAX_USER_ID_BYTES);
      if (v == null) {
        v = ip("items.remote_ip", a.getRemoteIp());
      }
      if (v != null) {
        return v;
      }
    }
    return null;
  }

  /**
   * {@code DictionaryEntries} on the session stream (11 §4.4, §7): at most 1,000 entries, text caps
   * (SQL_TEXT 64 KiB, other kinds 8 KiB), no hash 0.
   */
  public Violation check(DictionaryEntries entries) {
    if (entries.getEntriesCount() > ProtocolLimits.MAX_DICTIONARY_ENTRIES_PER_MESSAGE) {
      return items(
          "entries", entries.getEntriesCount(), ProtocolLimits.MAX_DICTIONARY_ENTRIES_PER_MESSAGE);
    }
    for (DictionaryEntry e : entries.getEntriesList()) {
      if (e.getHash() == 0) {
        return hashZero("entries.hash");
      }
      int cap =
          e.getKind() == DictionaryKind.SQL_TEXT
              ? ProtocolLimits.MAX_SQL_TEXT_BYTES
              : ProtocolLimits.MAX_DICTIONARY_TEXT_BYTES;
      Violation v = string("entries.text", e.getTextBytes(), cap);
      if (v != null) {
        return v;
      }
    }
    return null;
  }

  /**
   * {@code AgentHello} (11 §4.2 field caps): {@code app_name} 128 B, {@code env} 32 B, the other
   * strings and every list entry at most {@code max-string-bytes}; at most {@value
   * #MAX_REMOTE_CONFIG_ALLOW} {@code remote_config_allow} and {@value #MAX_INSTRUMENTATION_MODULES}
   * {@code instrumentation_modules} entries (ADR-0010 C-157 (2)).
   */
  public Violation check(AgentHello hello) {
    Violation v =
        repeated("remote_config_allow", hello.getRemoteConfigAllowCount(), MAX_REMOTE_CONFIG_ALLOW);
    if (v == null) {
      v =
          repeated(
              "instrumentation_modules",
              hello.getInstrumentationModulesCount(),
              MAX_INSTRUMENTATION_MODULES);
    }
    if (v == null) {
      v = string("app_name", hello.getAppNameBytes(), ProtocolLimits.MAX_APP_NAME_BYTES);
    }
    if (v == null) {
      v = string("env", hello.getEnvBytes(), ProtocolLimits.MAX_ENV_BYTES);
    }
    int general = limits.maxStringBytes();
    for (ByteString value :
        List.of(
            hello.getAgentVersionBytes(),
            hello.getInstanceNameBytes(),
            hello.getHostBytes(),
            hello.getJvmVersionBytes(),
            hello.getJvmVendorBytes(),
            hello.getOsBytes(),
            hello.getArchBytes(),
            hello.getCgroupBytes(),
            hello.getConfigHashBytes(),
            hello.getAppVersionBytes())) {
      if (v == null) {
        v = string("hello", value, general);
      }
    }
    for (int i = 0; v == null && i < hello.getRemoteConfigAllowCount(); i++) {
      v = string("remote_config_allow", hello.getRemoteConfigAllowBytes(i), general);
    }
    for (int i = 0; v == null && i < hello.getInstrumentationModulesCount(); i++) {
      v = string("instrumentation_modules", hello.getInstrumentationModulesBytes(i), general);
    }
    return v;
  }

  /**
   * {@code InstanceUpdate}: every string and list entry at most {@code max-string-bytes}, at most
   * {@value #MAX_CONNECTORS} {@code connectors} (ADR-0010 C-157 (2)).
   */
  public Violation check(InstanceUpdate update) {
    int general = limits.maxStringBytes();
    Violation v = repeated("connectors", update.getConnectorsCount(), MAX_CONNECTORS);
    if (v == null) {
      v = string("instance_name", update.getInstanceNameBytes(), general);
    }
    if (v == null) {
      v = string("app_version", update.getAppVersionBytes(), general);
    }
    if (v == null) {
      v = string("config_hash", update.getConfigHashBytes(), general);
    }
    for (int i = 0; v == null && i < update.getConnectorsCount(); i++) {
      v = string("connectors", update.getConnectorsBytes(i), general);
    }
    return v;
  }

  // --- parts ---------------------------------------------------------------------------------

  /**
   * Every batch has a header with a seq (11 §6: from 1); its instance is the session's (0 = not
   * filled, accepted).
   */
  private static Violation header(BatchHeader header, int sessionInstanceId) {
    if (header.getSeq() == 0) {
      return Violation.format("Batch header without seq");
    }
    if (header.getInstanceId() != 0 && header.getInstanceId() != sessionInstanceId) {
      return Violation.format("Batch header instance_id differs from argos-instance-id");
    }
    return null;
  }

  private static Violation record(TransactionRecord r) {
    if (r.getTraceId().size() != ProtocolLimits.TRACE_ID_BYTES) {
      return Violation.format(
          "trace_id must be "
              + ProtocolLimits.TRACE_ID_BYTES
              + " bytes, got "
              + r.getTraceId().size());
    }
    if (r.getServiceId() == 0) {
      return hashZero("records.service_id");
    }
    Violation v = repeated("records.sql_ids", r.getSqlIdsCount(), ProtocolLimits.MAX_SQL_IDS);
    if (v != null) {
      return v;
    }
    for (int i = 0; i < r.getSqlIdsCount(); i++) {
      if (r.getSqlIds(i) == 0) {
        return hashZero("records.sql_ids");
      }
    }
    v = repeated("records.headers", r.getHeadersCount(), ProtocolLimits.MAX_HEADERS);
    if (v != null) {
      return v;
    }
    for (KeyValue h : r.getHeadersList()) {
      v = string("records.headers.key", h.getKeyBytes(), ProtocolLimits.MAX_KEY_BYTES);
      if (v == null) {
        v = string("records.headers.value", h.getValueBytes(), ProtocolLimits.MAX_VALUE_BYTES);
      }
      if (v != null) {
        return v;
      }
    }
    v = string("records.http_method", r.getHttpMethodBytes(), ProtocolLimits.MAX_HTTP_METHOD_BYTES);
    if (v == null) {
      v = string("records.url_path", r.getUrlPathBytes(), ProtocolLimits.MAX_URL_PATH_BYTES);
    }
    if (v == null) {
      v =
          string(
              "records.error_message",
              r.getErrorMessageBytes(),
              ProtocolLimits.MAX_ERROR_MESSAGE_BYTES);
    }
    if (v == null) {
      v = string("records.user_id", r.getUserIdBytes(), ProtocolLimits.MAX_USER_ID_BYTES);
    }
    if (v == null) {
      v = string("records.request_id", r.getRequestIdBytes(), ProtocolLimits.MAX_REQUEST_ID_BYTES);
    }
    if (v == null) {
      v = ip("records.remote_ip", r.getRemoteIp());
    }
    return v;
  }

  private Violation step(ProfileStep s) {
    Violation v = string("steps.detail", s.getDetailBytes(), limits.maxDetailBytes());
    if (v != null) {
      return v;
    }
    int frameCap =
        s.getType() == StepType.ERROR ? limits.maxErrorFrames() : limits.maxStackFrames();
    v = repeated("steps.frame_ids", s.getFrameIdsCount(), frameCap);
    if (v != null) {
      return v;
    }
    for (int i = 0; i < s.getFrameIdsCount(); i++) {
      if (s.getFrameIds(i) == 0) {
        return hashZero("steps.frame_ids");
      }
    }
    return null;
  }

  private static Violation items(String field, long count, int cap) {
    return Violation.exhausted(
        Violation.BATCH_ITEMS, field + ": " + count + " items, at most " + cap + " allowed");
  }

  private static Violation string(String field, ByteString value, int capBytes) {
    if (value.size() <= capBytes) {
      return null;
    }
    return Violation.exhausted(
        Violation.STRING_LENGTH,
        field + ": " + value.size() + " bytes, at most " + capBytes + " allowed");
  }

  private static Violation repeated(String field, int count, int cap) {
    if (count <= cap) {
      return null;
    }
    return Violation.exhausted(
        Violation.REPEATED_LENGTH, field + ": " + count + " entries, at most " + cap + " allowed");
  }

  private static Violation ip(String field, ByteString address) {
    int n = address.size();
    if (n == 0 || n == 4 || n == 16) {
      return null;
    }
    return Violation.format(field + " must be 0, 4 or 16 bytes, got " + n);
  }

  private static Violation hashZero(String field) {
    return Violation.format(field + ": dictionary hash 0 is invalid");
  }
}
