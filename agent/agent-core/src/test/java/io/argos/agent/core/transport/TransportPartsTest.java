/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.core.transport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.argos.agent.bootstrap.DictKind;
import io.argos.agent.core.config.AgentConfig;
import io.argos.agent.core.config.TokenHolder;
import io.argos.agent.core.trace.Dictionary;
import io.argos.agent.core.transport.TransportFakes.ManualClock;
import io.argos.proto.ProtocolLimits;
import io.argos.proto.ProtocolVersion;
import io.argos.proto.v1.ActiveTransactionSnapshot;
import io.argos.proto.v1.AgentHello;
import io.argos.proto.v1.BatchHeader;
import io.argos.proto.v1.Command;
import io.argos.proto.v1.CommandResult;
import io.argos.proto.v1.CommandStatus;
import io.argos.proto.v1.CommandType;
import io.argos.proto.v1.ConfigApplyResult;
import io.argos.proto.v1.ConfigPush;
import io.argos.proto.v1.DictionaryEntries;
import io.argos.proto.v1.DictionaryEntry;
import io.argos.proto.v1.DictionaryKind;
import io.argos.proto.v1.DictionaryRef;
import io.argos.proto.v1.DictionaryRequest;
import io.argos.proto.v1.Heartbeat;
import io.argos.proto.v1.HeartbeatAck;
import io.argos.proto.v1.IpMode;
import io.argos.proto.v1.MetricBatch;
import io.argos.proto.v1.ProfileBatch;
import io.argos.proto.v1.QueryStringMode;
import io.argos.proto.v1.RejectCode;
import io.argos.proto.v1.SqlParamMode;
import io.argos.proto.v1.StatBatch;
import io.argos.proto.v1.TransactionBatch;
import io.argos.proto.v1.UserIdMode;
import io.grpc.CallCredentials;
import io.grpc.Metadata;
import java.io.File;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The small parts of the transport: back-off, clock, settings, dictionary, identity, counters. */
@Tag("req:DSN-02-11")
class TransportPartsTest {
  @TempDir File dir;

  private static String repeat(char c, int count) {
    char[] chars = new char[count];
    Arrays.fill(chars, c);
    return new String(chars);
  }

  // --- Backoff -----------------------------------------------------------------------------------

  private static Backoff fixedBackoff(final double value) {
    return new Backoff(
        new Backoff.Jitter() {
          @Override
          public double next() {
            return value;
          }
        });
  }

  @Test
  @DisplayName("10 §10.2: 1 s doubling to 30 s without jitter at the centre; reset starts over")
  void backoff() {
    Backoff backoff = fixedBackoff(0.5);
    long[] expected = {1_000, 2_000, 4_000, 8_000, 16_000, 30_000, 30_000};
    for (long value : expected) {
      assertThat(backoff.next()).isEqualTo(value);
    }
    backoff.reset();
    assertThat(backoff.next()).isEqualTo(1_000);
  }

  @Test
  @DisplayName("10 §10.2: jitter is ±20 % and the real random source stays inside it")
  void jitter() {
    assertThat(fixedBackoff(0).next()).isEqualTo(800);
    assertThat(fixedBackoff(0.999999).next()).isBetween(1_199L, 1_200L);
    Backoff backoff = new Backoff();
    for (int i = 0; i < 100; i++) {
      backoff.reset();
      assertThat(backoff.next()).isBetween(800L, 1_200L);
    }
  }

  @Test
  @Tag("req:SEC-01")
  @DisplayName("ADR-0010 C-102: the authentication back-off is a fixed 5 minutes without jitter")
  void unauthenticatedBackoff() {
    Backoff backoff = fixedBackoff(0);
    assertThat(backoff.unauthenticatedMillis()).isEqualTo(300_000);
    assertThat(backoff.unauthenticatedMillis()).isEqualTo(300_000);
    assertThat(backoff.next()).as("the failure back-off is untouched").isEqualTo(800);
  }

  // --- ClockSync ---------------------------------------------------------------------------------

  @Test
  @Tag("req:D-02")
  @DisplayName(
      "11 §9: stamp and ack record one round; a late ack does not mix rounds; a new session resets")
  void clockSync() {
    ManualClock clock = new ManualClock();
    ClockSync sync = new ClockSync(clock);
    Heartbeat first = sync.stamp(Heartbeat.newBuilder()).build();
    assertThat(first.getT1Ms()).isEqualTo(clock.millis);
    assertThat(first.getPrevT1Ms()).isZero();
    assertThat(first.getPrevT4Ms()).isZero();

    clock.advanceMillis(30);
    sync.acknowledged(HeartbeatAck.newBuilder().setT1Ms(first.getT1Ms()).build());
    long t4 = clock.millis;
    assertThat(sync.prevT1()).isEqualTo(first.getT1Ms());
    assertThat(sync.prevT4()).isEqualTo(t4);

    clock.advanceMillis(1_000);
    Heartbeat second = sync.stamp(Heartbeat.newBuilder()).build();
    assertThat(second.getPrevT1Ms()).isEqualTo(first.getT1Ms());
    assertThat(second.getPrevT4Ms()).isEqualTo(t4);

    // A duplicate of the old ack keeps the last completed round.
    clock.advanceMillis(5);
    sync.acknowledged(HeartbeatAck.newBuilder().setT1Ms(first.getT1Ms()).build());
    assertThat(sync.prevT4()).isEqualTo(t4);

    assertThat(sync.offsetKnown()).isTrue();
    sync.reset();
    assertThat(sync.prevT1()).isZero();
    assertThat(sync.prevT4()).isZero();
  }

  @Test
  @Tag("req:D-02")
  @DisplayName("11 §9: the server's clock_offset_ms is kept, never used to correct timestamps")
  void offsetKept() {
    ManualClock clock = new ManualClock();
    ClockSync sync = new ClockSync(clock);
    assertThat(sync.offsetKnown()).isFalse();
    sync.acknowledged(HeartbeatAck.newBuilder().setClockOffsetMs(1500).build());
    assertThat(sync.offsetMillis()).isEqualTo(1500);
    long before = clock.millis;
    assertThat(sync.stamp(Heartbeat.newBuilder()).build().getT1Ms()).isEqualTo(before);
  }

  // --- TransportSettings -------------------------------------------------------------------------

  @Test
  @DisplayName("server.address: host:port as given, default port 9100, IPv6 brackets, blank null")
  void target() {
    assertThat(TransportSettings.target("collector:7777")).isEqualTo("collector:7777");
    assertThat(TransportSettings.target(" collector ")).isEqualTo("collector:9100");
    assertThat(TransportSettings.target("[::1]")).isEqualTo("[::1]:9100");
    assertThat(TransportSettings.target("[::1]:7777")).isEqualTo("[::1]:7777");
    assertThat(TransportSettings.target("::1")).isEqualTo("[::1]:9100");
    assertThat(TransportSettings.target(null)).isNull();
    assertThat(TransportSettings.target("  ")).isNull();
  }

  @Test
  @DisplayName("10 §15: the sender and server keys reach the settings; the defaults are 10 §10.2")
  void settings() {
    TransportSettings defaults =
        TransportSettings.of(
            AgentConfig.of(Collections.<String, String>emptyMap()), TokenHolder.NONE);
    assertThat(defaults.target).isNull();
    assertThat(defaults.tls).isTrue();
    assertThat(defaults.caFile).isNull();
    assertThat(defaults.insecureSkipVerify).isFalse();
    assertThat(defaults.batchSize).isEqualTo(500);
    assertThat(defaults.flushIntervalMillis).isEqualTo(100);
    assertThat(defaults.maxInFlight).isEqualTo(4);
    assertThat(defaults.deadlineMillis).isEqualTo(5_000);
    assertThat(defaults.gzip).isTrue();

    Map<String, String> values = new HashMap<String, String>();
    values.put("server.address", "collector");
    values.put("server.tls", "false");
    values.put("server.tls.ca-file", "/etc/ca.pem");
    values.put("server.tls.insecure-skip-verify", "true");
    values.put("sender.batch-size", "7");
    values.put("sender.compression", "none");
    TransportSettings custom = TransportSettings.of(AgentConfig.of(values), TokenHolder.NONE);
    assertThat(custom.target).isEqualTo("collector:9100");
    assertThat(custom.tls).isFalse();
    assertThat(custom.caFile).isEqualTo(new File("/etc/ca.pem"));
    assertThat(custom.insecureSkipVerify).isTrue();
    assertThat(custom.batchSize).isEqualTo(7);
    assertThat(custom.gzip).isFalse();
  }

  @Test
  @DisplayName("ADR-0005 decision 7: the relocated names only, an operator's value wins")
  void nettyProps() {
    Map<String, String> before = new HashMap<String, String>();
    for (String[] entry : NettyProps.VALUES) {
      String old = System.getProperty(entry[0]);
      if (old != null) {
        before.put(entry[0], old);
      }
      System.clearProperty(entry[0]);
      assertThat(entry[0]).startsWith("io.argos.agent.shaded.io.netty.");
    }
    try {
      System.setProperty(NettyProps.ALLOCATOR_TYPE, "operator");
      int set = NettyProps.apply();
      assertThat(set).isEqualTo(NettyProps.VALUES.length - 1);
      assertThat(System.getProperty(NettyProps.ALLOCATOR_TYPE)).isEqualTo("operator");
      assertThat(System.getProperty(NettyProps.NO_UNSAFE)).isEqualTo("true");
      assertThat(System.getProperty(NettyProps.MAX_DIRECT_MEMORY)).isEqualTo("8388608");
      assertThat(System.getProperty(NettyProps.EVENT_LOOP_THREADS)).isEqualTo("1");
      assertThat(System.getProperty(NettyProps.NO_PREFER_DIRECT)).isEqualTo("true");
      assertThat(System.getProperty(NettyProps.TRY_REFLECTION_SET_ACCESSIBLE)).isEqualTo("false");
      assertThat(System.getProperty(NettyProps.NUM_DIRECT_ARENAS)).isEqualTo("0");
      assertThat(System.getProperty(NettyProps.LEAK_DETECTION_LEVEL)).isEqualTo("disabled");
      assertThat(System.getProperty(NettyProps.RECYCLER_MAX_CAPACITY)).isEqualTo("0");
      assertThat(System.getProperty(NettyProps.NO_KEY_SET_OPTIMIZATION)).isEqualTo("true");
      assertThat(System.getProperty("io.netty.noUnsafe")).as("never the original name").isNull();
    } finally {
      for (String[] entry : NettyProps.VALUES) {
        System.clearProperty(entry[0]);
        if (before.containsKey(entry[0])) {
          System.setProperty(entry[0], before.get(entry[0]));
        }
      }
    }
  }

  // --- TLS context (no network) ------------------------------------------------------------------

  @Test
  @Tag("req:SEC-08")
  @DisplayName("T-SEC-08b server.tls.ca-file: a file without a certificate or a missing one fails")
  void caFile() throws Exception {
    File empty = new File(dir, "empty.pem");
    Files.write(empty.toPath(), "no certificate".getBytes(StandardCharsets.UTF_8));
    assertThatThrownBy(() -> GrpcTransport.trustManagers(empty)).isInstanceOf(Exception.class);
    assertThatThrownBy(() -> GrpcTransport.trustManagers(new File(dir, "missing.pem")))
        .isInstanceOf(java.io.IOException.class);
  }

  // --- Token
  // --------------------------------------------------------------------------------------

  @Test
  @Tag("req:SEC-06")
  @DisplayName(
      "T-SEC-06c the token travels only as authorization: Bearer metadata, and toString is masked")
  void tokenCredentials() {
    String token = "argos_" + repeat('a', 43);
    TokenCredentials credentials = new TokenCredentials(token);
    assertThat(credentials.toString()).isEqualTo("TokenCredentials[argos_****]");
    assertThat(credentials.toString()).doesNotContain(token);
    final AtomicReference<Metadata> applied = new AtomicReference<Metadata>();
    credentials.applyRequestMetadata(
        null,
        new Executor() {
          @Override
          public void execute(Runnable command) {
            command.run();
          }
        },
        new CallCredentials.MetadataApplier() {
          @Override
          public void apply(Metadata headers) {
            applied.set(headers);
          }

          @Override
          public void fail(io.grpc.Status status) {
            throw new AssertionError(status.toString());
          }
        });
    assertThat(applied.get().get(GrpcTransport.AUTHORIZATION)).isEqualTo("Bearer " + token);
  }

  // --- Dictionary cache
  // ----------------------------------------------------------------------------

  @Test
  @DisplayName(
      "11 §7: a new id becomes one entry; a sent id is not sent again; kinds keep the C-16 numbers")
  void dictionaryPreSend() {
    Dictionary dictionary = new Dictionary();
    DictionaryCache cache = new DictionaryCache(dictionary);
    long sql = dictionary.id(DictKind.SQL, "select 1");
    long service = dictionary.id(DictKind.SERVICE, "GET /a");
    cache.reference(DictKind.SQL, sql);
    cache.reference(DictKind.SQL, sql);
    cache.reference(DictKind.SERVICE, service);
    cache.reference(DictKind.SERVICE, 0);
    assertThat(cache.hasPending()).isTrue();

    List<DictionaryEntries> messages = cache.takePending();
    assertThat(messages).hasSize(1);
    List<DictionaryEntry> entries = messages.get(0).getEntriesList();
    assertThat(entries).hasSize(2);
    assertThat(entries.get(0).getKind()).isEqualTo(DictionaryKind.SQL_TEXT);
    assertThat(entries.get(0).getHash()).isEqualTo(sql);
    assertThat(entries.get(0).getText()).isEqualTo("select 1");
    assertThat(entries.get(0).getNormVersion()).isEqualTo(DictionaryCache.NORM_VERSION);
    assertThat(entries.get(1).getKind()).isEqualTo(DictionaryKind.SERVICE);
    assertThat(entries.get(1).getNormVersion()).isZero();

    assertThat(cache.hasPending()).isFalse();
    cache.reference(DictKind.SQL, sql);
    assertThat(cache.hasPending()).as("cache hit").isFalse();
    assertThat(cache.takePending()).isEmpty();
  }

  @Test
  @DisplayName("11 §7: at most 1,000 entries per DictionaryEntries message")
  void dictionaryMessageCap() {
    Dictionary dictionary = new Dictionary();
    DictionaryCache cache = new DictionaryCache(dictionary);
    int count = ProtocolLimits.MAX_DICTIONARY_ENTRIES_PER_MESSAGE + 5;
    for (int i = 0; i < count; i++) {
      long id = dictionary.id(DictKind.FRAME, "Frame.m" + i + "(F.java)");
      cache.reference(DictKind.FRAME, id);
    }
    List<DictionaryEntries> messages = cache.takePending();
    assertThat(messages).hasSize(2);
    assertThat(messages.get(0).getEntriesCount())
        .isEqualTo(ProtocolLimits.MAX_DICTIONARY_ENTRIES_PER_MESSAGE);
    assertThat(messages.get(1).getEntriesCount()).isEqualTo(5);
  }

  @Test
  @DisplayName("11 §7: entries that could not be written are sent again")
  void dictionaryUnsent() {
    Dictionary dictionary = new Dictionary();
    DictionaryCache cache = new DictionaryCache(dictionary);
    long id = dictionary.id(DictKind.HOST, "a:1");
    cache.reference(DictKind.HOST, id);
    cache.unsent(cache.takePending());
    cache.reference(DictKind.HOST, id);
    assertThat(cache.hasPending()).isTrue();
  }

  @Test
  @DisplayName("11 §7: DictionaryRequest answers known ids, skips the others, null when none")
  void dictionaryAnswer() {
    Dictionary dictionary = new Dictionary();
    DictionaryCache cache = new DictionaryCache(dictionary);
    long id = dictionary.id(DictKind.CLASS, "java.lang.Error");
    DictionaryEntries answer =
        cache.answer(
            DictionaryRequest.newBuilder()
                .addRefs(DictionaryRef.newBuilder().setKind(DictionaryKind.CLASS).setHash(id))
                .addRefs(DictionaryRef.newBuilder().setKind(DictionaryKind.CLASS).setHash(5))
                .build());
    assertThat(answer.getEntriesCount()).isEqualTo(1);
    assertThat(answer.getEntries(0).getText()).isEqualTo("java.lang.Error");
    assertThat(
            cache.answer(
                DictionaryRequest.newBuilder()
                    .addRefs(DictionaryRef.newBuilder().setKind(DictionaryKind.CLASS).setHash(5))
                    .build()))
        .isNull();
  }

  @Test
  @Tag("req:SEC-11")
  @DisplayName("11 §4.2: texts are cut to 8 KiB (SQL 64 KiB) with truncated set")
  void dictionaryTextCap() {
    Dictionary dictionary = new Dictionary();
    DictionaryCache cache = new DictionaryCache(dictionary);
    long method = dictionary.id(DictKind.METHOD, repeat('m', 20_000));
    long sql = dictionary.id(DictKind.SQL, repeat('s', 20_000));
    cache.reference(DictKind.METHOD, method);
    cache.reference(DictKind.SQL, sql);
    List<DictionaryEntry> entries = cache.takePending().get(0).getEntriesList();
    assertThat(entries.get(0).getText().length())
        .isEqualTo(ProtocolLimits.MAX_DICTIONARY_TEXT_BYTES);
    assertThat(entries.get(0).getTruncated()).isTrue();
    assertThat(entries.get(1).getText()).hasSize(20_000);
    assertThat(entries.get(1).getTruncated()).isFalse();
  }

  @Test
  @DisplayName("10 §10.4: the sent-ids memory is a bounded FIFO; a forgotten id is sent again")
  void fifo() {
    LongFifoSet set = new LongFifoSet(3);
    assertThat(set.add(1)).isTrue();
    assertThat(set.add(1)).isFalse();
    set.add(2);
    set.add(3);
    set.add(4);
    assertThat(set.size()).isEqualTo(3);
    assertThat(set.contains(1)).isFalse();
    assertThat(set.contains(4)).isTrue();
    set.remove(3);
    assertThat(set.contains(3)).isFalse();
    assertThat(set.contains(2)).isTrue();
    assertThat(set.add(0)).as("0 is not an id").isFalse();
    assertThat(set.contains(0)).isFalse();
    set.clear();
    assertThat(set.size()).isZero();
    LongFifoSet many = new LongFifoSet(1_000);
    long mix = 0x9E3779B97F4A7C15L;
    long value = 0;
    for (int i = 1; i <= 100_000; i++) {
      value += mix;
      many.add(value);
    }
    assertThat(many.size()).isEqualTo(1_000);
    assertThat(many.contains(value)).isTrue();
    assertThat(many.contains(mix)).as("the oldest was forgotten").isFalse();
  }

  // --- Identity ----------------------------------------------------------------------------------

  @Test
  @DisplayName("10 §10.4: AgentHello from the configuration and the runtime facts, no token")
  void hello() {
    Map<String, String> values = new HashMap<String, String>();
    values.put("app.name", "orders");
    values.put("app.env", "prod");
    values.put("app.version", "1.2.3");
    values.put("server.token", "argos_" + repeat('z', 43));
    values.put("capture.remote-ip", "full");
    values.put("capture.user-id.mode", "hashed");
    values.put("capture.query-string", "whitelist");
    values.put("capture.sql-parameters", "partial");
    AgentIdentity identity =
        new AgentIdentity(
            AgentConfig.of(values), "1.0.0", "hash-1", Arrays.asList("servlet", "jdbc"), 42L, true);
    AgentHello hello = identity.hello();
    assertThat(hello.getProtocolVersion()).isEqualTo(ProtocolVersion.CURRENT);
    assertThat(hello.getAgentVersion()).isEqualTo("1.0.0");
    assertThat(hello.getAppName()).isEqualTo("orders");
    assertThat(hello.getEnv()).isEqualTo("prod");
    assertThat(hello.getAppVersion()).isEqualTo("1.2.3");
    assertThat(hello.getConfigHash()).isEqualTo("hash-1");
    assertThat(hello.getAgentEpoch()).isEqualTo(42L);
    assertThat(hello.getPlaintext()).isTrue();
    AgentHello tlsHello =
        new AgentIdentity(
                AgentConfig.of(values), "1.0.0", "hash-1", Arrays.asList("servlet"), 42L, false)
            .hello();
    assertThat(tlsHello.hasPlaintext())
        .as("TLS reports plaintext=false explicitly (optional field, C-101)")
        .isTrue();
    assertThat(tlsHello.getPlaintext()).isFalse();
    assertThat(hello.getCgroup())
        .as("the cgroup mode of this machine (WO-16, 10 §11)")
        .isEqualTo(io.argos.agent.core.metric.CgroupReader.systemMode());
    assertThat(hello.getInstanceName()).isEqualTo(identity.host() + ":" + identity.pid());
    assertThat(hello.getPid()).isEqualTo((int) identity.pid());
    assertThat(hello.getIpMode()).isEqualTo(IpMode.IP_FULL);
    assertThat(hello.getUserIdMode()).isEqualTo(UserIdMode.USER_ID_HASHED);
    assertThat(hello.getQueryStringMode()).isEqualTo(QueryStringMode.QS_WHITELIST);
    assertThat(hello.getSqlParamMode()).isEqualTo(SqlParamMode.SQL_PARAM_PARTIAL);
    assertThat(hello.getRemoteConfigAllowList())
        .contains("sampling.**", "instrumentation.disabled", "enabled");
    assertThat(hello.getInstrumentationModulesList()).containsExactly("servlet", "jdbc");
    assertThat(hello.getJvmVersion()).isEqualTo(System.getProperty("java.version"));
    assertThat(hello.getProcessStartMs())
        .isEqualTo(ManagementFactory.getRuntimeMXBean().getStartTime());
    assertThat(hello.toString()).doesNotContain(repeat('z', 43));
  }

  @Test
  @DisplayName("a new session's hello carries the instance values updated since")
  void helloUpdates() {
    Map<String, String> values = new HashMap<String, String>();
    values.put("app.name", "orders");
    values.put("app.instance", "node-1");
    AgentIdentity identity =
        new AgentIdentity(
            AgentConfig.of(values), "1.0.0", "h", Collections.<String>emptyList(), 1L, false);
    assertThat(identity.hello().getInstanceName()).isEqualTo("node-1");
    assertThat(identity.hello().getVirtualThreads()).isFalse();
    identity.instanceName("host:8080");
    identity.virtualThreads(true);
    identity.appVersion("2.0");
    identity.configHash("h2");
    AgentHello hello = identity.hello();
    assertThat(hello.getInstanceName()).isEqualTo("host:8080");
    assertThat(hello.getVirtualThreads()).isTrue();
    assertThat(hello.getAppVersion()).isEqualTo("2.0");
    assertThat(hello.getConfigHash()).isEqualTo("h2");
  }

  @Test
  @DisplayName("capture modes: names to the protocol's enums (their numbers differ from bootstrap)")
  void modes() {
    assertThat(AgentIdentity.ipMode("off")).isEqualTo(IpMode.IP_OFF);
    assertThat(AgentIdentity.ipMode("MASKED")).isEqualTo(IpMode.IP_MASKED);
    assertThat(AgentIdentity.ipMode(null)).isEqualTo(IpMode.IP_MASKED);
    assertThat(AgentIdentity.userIdMode("plain")).isEqualTo(UserIdMode.USER_ID_PLAIN);
    assertThat(AgentIdentity.userIdMode("off")).isEqualTo(UserIdMode.USER_ID_OFF);
    assertThat(AgentIdentity.queryStringMode("full")).isEqualTo(QueryStringMode.QS_FULL);
    assertThat(AgentIdentity.queryStringMode("off")).isEqualTo(QueryStringMode.QS_OFF);
    assertThat(AgentIdentity.sqlParamMode("full")).isEqualTo(SqlParamMode.SQL_PARAM_FULL);
    assertThat(AgentIdentity.sqlParamMode("masked")).isEqualTo(SqlParamMode.SQL_PARAM_MASKED);
    assertThat(AgentIdentity.hostName()).isNotEmpty();
    assertThat(AgentIdentity.pid(null)).isZero();
  }

  // --- Rejecting dispatcher
  // ------------------------------------------------------------------------

  @Test
  @Tag("req:SEC-23")
  @DisplayName(
      "C-118 (WO-11 dispatcher): an allowed key is 'not applied', a forbidden one names the gate's"
          + " reason; never the pushed value; bounded")
  void rejectingConfig() {
    Map<String, String> values = new HashMap<String, String>();
    values.put("app.name", "orders");
    RejectingDispatcher dispatcher = new RejectingDispatcher(AgentConfig.of(values), "hash");
    ConfigApplyResult result =
        dispatcher.configPush(
            ConfigPush.newBuilder()
                .setChangeId("c")
                .putValues("sampling.profile.normal-rate", "0.2")
                .putValues("capture.user-id.mode", "plain")
                .putValues("no.such.key", "secret-value")
                .build());
    assertThat(result.getAppliedKeysList()).isEmpty();
    assertThat(result.getEffectiveConfigHash()).isEqualTo("hash");
    assertThat(result.getRejectedCount()).isEqualTo(3);
    Map<String, String> messages = new HashMap<String, String>();
    for (io.argos.proto.v1.RejectedKey key : result.getRejectedList()) {
      assertThat(key.getCode()).isEqualTo(RejectCode.NOT_ALLOWED);
      messages.put(key.getKey(), key.getMessage());
    }
    assertThat(messages.get("sampling.profile.normal-rate"))
        .isEqualTo(RejectingDispatcher.NOT_APPLIED);
    assertThat(messages.get("capture.user-id.mode")).startsWith("rejected by the agent's hard cap");
    assertThat(result.toString()).doesNotContain("secret-value");

    ConfigPush.Builder many = ConfigPush.newBuilder().setChangeId("m");
    for (int i = 0; i < RejectingDispatcher.MAX_REJECTED_KEYS + 10; i++) {
      many.putValues("k" + i, "v");
    }
    assertThat(dispatcher.configPush(many.build()).getRejectedCount())
        .isEqualTo(RejectingDispatcher.MAX_REJECTED_KEYS);
  }

  @Test
  @Tag("req:SEC-24")
  @DisplayName("11 §8.2: every command type, known or not, is REJECTED with one chunk, no payload")
  void rejectingCommand() {
    RejectingDispatcher dispatcher =
        new RejectingDispatcher(AgentConfig.of(Collections.<String, String>emptyMap()), null);
    for (int type : new int[] {CommandType.THREAD_DUMP_VALUE, CommandType.ENV_DUMP_VALUE, 42}) {
      List<CommandResult> results =
          dispatcher.command(Command.newBuilder().setCommandId("c").setTypeValue(type).build());
      assertThat(results).hasSize(1);
      assertThat(results.get(0).getStatus()).isEqualTo(CommandStatus.REJECTED);
      assertThat(results.get(0).getChunkCount()).isEqualTo(1);
      assertThat(results.get(0).getPayload().isEmpty()).isTrue();
    }
  }

  @Test
  @DisplayName(
      "18 §5.4 test.exporter=noop encodes like the sender: dictionary entries once, then the record")
  void batchEncoder() {
    Dictionary dictionary = new Dictionary();
    io.argos.agent.core.trace.TraceRuntime runtime =
        io.argos.agent.core.trace.TraceRuntime.create(
            AgentConfig.of(Collections.singletonMap("sampling.profile.normal-rate", "1.0")),
            new io.argos.agent.core.guard.AgentCounters());
    io.argos.agent.core.trace.TracerImpl tracer = runtime.tracerImpl();
    io.argos.agent.bootstrap.TraceContext ctx =
        tracer.begin(io.argos.agent.bootstrap.Kind.HTTP, "GET /a", 0);
    int seq =
        tracer.stepStart(
            ctx,
            io.argos.agent.bootstrap.StepType.METHOD,
            tracer.dict(DictKind.METHOD, "A#b"),
            0,
            1);
    tracer.stepEnd(ctx, seq, 0, false, 0, 2);
    tracer.end(ctx, 200, null, null, null, 3);
    List<QueueItem> items = new java.util.ArrayList<QueueItem>();
    runtime.queues().transaction().drain(10, items::add);
    runtime.queues().profile().drain(10, items::add);
    assertThat(items).hasSize(2);

    BatchEncoder encoder = new BatchEncoder(runtime.dictionary());
    io.argos.agent.core.trace.Transaction tx = (io.argos.agent.core.trace.Transaction) items.get(0);
    int first = encoder.encode(tx);
    int second = encoder.encode(tx);
    assertThat(first).isGreaterThan(second);
    assertThat(second).isPositive();
    assertThat(encoder.encode((io.argos.agent.core.trace.ProfileRecord) items.get(1))).isPositive();
    assertThat(dictionary.size(DictKind.SERVICE)).isZero();
  }

  @Test
  @DisplayName(
      "the grpc slot: no server.address or an unusable ca-file keeps recording and drops (noop)")
  void factoryFallsBack() throws Exception {
    Map<String, String> values = new HashMap<String, String>();
    values.put("app.name", "orders");
    AgentConfig noAddress = AgentConfig.of(values);
    assertThat(new GrpcExporterFactory().create(context(noAddress)))
        .isInstanceOf(io.argos.agent.core.export.NoopExporter.class);

    values.put("server.address", "127.0.0.1:1");
    values.put("server.tls.ca-file", new File(dir, "missing.pem").getPath());
    AgentConfig badCa = AgentConfig.of(values);
    io.argos.agent.core.export.Exporter exporter = new GrpcExporterFactory().create(context(badCa));
    assertThat(exporter).isInstanceOf(io.argos.agent.core.export.NoopExporter.class);
    assertThat(exporter.describe()).isEqualTo("drop");
  }

  private static io.argos.agent.core.export.ExporterContext context(AgentConfig config) {
    io.argos.agent.core.guard.AgentCounters counters =
        new io.argos.agent.core.guard.AgentCounters();
    io.argos.agent.core.trace.TraceRuntime runtime =
        io.argos.agent.core.trace.TraceRuntime.create(config, counters);
    return new io.argos.agent.core.export.ExporterContext(
        config,
        runtime.dictionary(),
        new io.argos.agent.core.export.AgentInfo("1.0", "abc", "1"),
        null,
        counters,
        runtime,
        Collections.<String>emptyList(),
        null);
  }

  // --- Prepared batches, headers, counters
  // ------------------------------------------------------------

  @Test
  @DisplayName("every batch kind takes the header; an estimate covers the body")
  void withHeader() {
    BatchHeader header = BatchHeader.newBuilder().setSeq(9).build();
    assertThat(
            ((TransactionBatch)
                    Sender.withHeader(
                        BatchKind.TRANSACTIONS, TransactionBatch.getDefaultInstance(), header))
                .getHeader()
                .getSeq())
        .isEqualTo(9);
    assertThat(
            ((ProfileBatch)
                    Sender.withHeader(
                        BatchKind.PROFILES, ProfileBatch.getDefaultInstance(), header))
                .getHeader()
                .getSeq())
        .isEqualTo(9);
    assertThat(
            ((MetricBatch)
                    Sender.withHeader(BatchKind.METRICS, MetricBatch.getDefaultInstance(), header))
                .getHeader()
                .getSeq())
        .isEqualTo(9);
    assertThat(
            ((StatBatch) Sender.withHeader(BatchKind.STATS, StatBatch.getDefaultInstance(), header))
                .getHeader()
                .getSeq())
        .isEqualTo(9);
    assertThat(
            ((ActiveTransactionSnapshot)
                    Sender.withHeader(
                        BatchKind.ACTIVE, ActiveTransactionSnapshot.getDefaultInstance(), header))
                .getHeader()
                .getSeq())
        .isEqualTo(9);
    PreparedBatch prepared = PreparedBatch.metrics(MetricBatch.getDefaultInstance());
    assertThat(prepared.kind()).isEqualTo(BatchKind.METRICS);
    assertThat(prepared.estimatedBytes()).isPositive();
  }

  @Test
  @DisplayName("11 §3: deadlines by kind: the configured value, the active snapshot at most 3 s")
  void deadlines() {
    assertThat(BatchKind.TRANSACTIONS.deadlineMillis(5_000)).isEqualTo(5_000);
    assertThat(BatchKind.ACTIVE.deadlineMillis(5_000)).isEqualTo(3_000);
    assertThat(BatchKind.ACTIVE.deadlineMillis(1_000)).isEqualTo(1_000);
    assertThat(BatchKind.STATS.deadlineMillis(0)).isEqualTo(5_000);
    for (BatchKind kind : BatchKind.values()) {
      assertThat(kind.queue()).isNotEmpty();
      assertThat(kind.label()).isEqualTo(kind.name().toLowerCase(java.util.Locale.ROOT));
    }
  }

  @Test
  @DisplayName("the self metrics have fixed names; the clock offset appears only once known")
  void counters() {
    TransportCounters counters = new TransportCounters();
    assertThat(counters.snapshot())
        .doesNotContainKey(TransportCounters.CLOCK_OFFSET)
        .containsEntry(TransportCounters.TRANSPORT_UP, 0L)
        .containsEntry("argos_agent_batches_sent_total{kind=\"transactions\"}", 0L);
    counters.incSent(BatchKind.PROFILES);
    counters.incRetry(BatchKind.METRICS);
    counters.incDropped(BatchKind.STATS);
    counters.addServerDropped(BatchKind.ACTIVE, 3);
    counters.addServerDropped(BatchKind.ACTIVE, -1);
    counters.up(true);
    counters.clockOffset(-5);
    counters.addDictionaryEntriesSent(2);
    assertThat(counters.snapshot())
        .containsEntry("argos_agent_batches_sent_total{kind=\"profiles\"}", 1L)
        .containsEntry("argos_agent_batch_retries_total{kind=\"metrics\"}", 1L)
        .containsEntry("argos_agent_batches_dropped_total{kind=\"stats\"}", 1L)
        .containsEntry("argos_agent_server_dropped_items_total{kind=\"active\"}", 3L)
        .containsEntry(TransportCounters.TRANSPORT_UP, 1L)
        .containsEntry(TransportCounters.CLOCK_OFFSET, -5L)
        .containsEntry(TransportCounters.DICTIONARY_ENTRIES_SENT, 2L);
    assertThat(counters.snapshot().size()).isLessThanOrEqualTo(26);
  }

  // --- UTF-8 caps
  // -----------------------------------------------------------------------------------

  @Test
  @Tag("req:SEC-11")
  @DisplayName("11 §4.4: cuts keep whole characters, never half a surrogate pair")
  void utf8() {
    assertThat(Utf8.cut(null, 5)).isEmpty();
    assertThat(Utf8.cut("abc", 5)).isEqualTo("abc");
    assertThat(Utf8.cut("abcdef", 3)).isEqualTo("abc");
    assertThat(Utf8.cut("한글", 5)).isEqualTo("한");
    assertThat(Utf8.cut("é", 1)).isEmpty();
    assertThat(Utf8.cut("😀b", 3)).isEmpty();
    assertThat(Utf8.cut("😀b", 4)).isEqualTo("😀");
    assertThat(Utf8.cut("😀b", 5)).isEqualTo("😀b");
    assertThat(Utf8.exceeds("abcdef", 3)).isTrue();
    assertThat(Utf8.exceeds("abc", 3)).isFalse();
    assertThat(Utf8.exceeds(null, 0)).isFalse();
  }
}
