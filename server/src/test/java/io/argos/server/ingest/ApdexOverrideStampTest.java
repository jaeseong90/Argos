/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.ingest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.argos.proto.v1.TransactionBatch;
import io.argos.proto.v1.TransactionKind;
import io.argos.proto.v1.TransactionRecord;
import io.argos.server.ingest.IngestStore.ApdexChange;
import io.argos.server.ingest.IngestStore.OverrideLimitException;
import io.argos.server.session.api.ApdexOverrides;
import io.argos.server.session.api.IpMode;
import io.argos.server.session.api.SessionContext;
import io.argos.server.session.api.UserIdMode;
import io.argos.server.storage.api.Table;
import io.argos.server.wal.WalRecord;
import io.argos.server.wal.api.WalDataPayload;
import io.argos.server.wal.api.WalRecordType;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.nio.ByteBuffer;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The service Apdex overrides of WO-27 (D-17, 12 §3.5 (5)): the snapshot type, the WAL payload
 * format 2 next to format 1 (C-129 (3)), the per-row choice of the writer, the stamper's reload and
 * limit, and the change service (event, reload, limit).
 */
@Tag("req:D-17")
@Tag("req:F-S16")
class ApdexOverrideStampTest {

  private static final long RECEIVED = 1_767_225_000_000L;
  private static final long SERVICE_A = 1001;
  private static final long SERVICE_B = 0x8000_0000_0000_0007L; // negative as a Java long

  private static SessionContext session() {
    return IngestTestDoubles.session(IpMode.MASKED, UserIdMode.HASHED, RECEIVED, 0, "1.0");
  }

  private static ApdexOverrides overrides(long... pairs) {
    Map<Long, Integer> m = new LinkedHashMap<>();
    for (int i = 0; i < pairs.length; i += 2) {
      m.put(pairs[i], (int) pairs[i + 1]);
    }
    return ApdexOverrides.of(m);
  }

  // --- ApdexOverrides -------------------------------------------------------------------------

  @Test
  @DisplayName("T-SV-017b an override wins for its service only; ids compare unsigned")
  void lookup() {
    ApdexOverrides o = overrides(SERVICE_B, 2_000, SERVICE_A, 200, 5, 65_535);
    assertThat(o.size()).isEqualTo(3);
    assertThat(o.serviceId(0)).isEqualTo(5);
    assertThat(o.serviceId(1)).isEqualTo(SERVICE_A);
    assertThat(o.serviceId(2)).isEqualTo(SERVICE_B);
    assertThat(o.tFor(SERVICE_A, 500)).isEqualTo(200);
    assertThat(o.tFor(SERVICE_B, 500)).isEqualTo(2_000);
    assertThat(o.tFor(5, 500)).isEqualTo(65_535);
    assertThat(o.tFor(6, 500)).isEqualTo(500);
    assertThat(ApdexOverrides.NONE.tFor(SERVICE_A, 750)).isEqualTo(750);
    assertThat(o).isEqualTo(overrides(SERVICE_A, 200, 5, 65_535, SERVICE_B, 2_000));
    assertThat(o.asMap()).containsEntry(SERVICE_B, 2_000).hasSize(3);
  }

  @Test
  @DisplayName("T-SV-017b at most 1,000 overrides per app, each T in [1, 65535]")
  void limits() {
    Map<Long, Integer> thousand = new HashMap<>();
    for (long i = 0; i < ApdexOverrides.MAX_PER_APP; i++) {
      thousand.put(i, 100);
    }
    assertThat(ApdexOverrides.of(thousand).size()).isEqualTo(1_000);
    thousand.put(5_000L, 100);
    assertThatThrownBy(() -> ApdexOverrides.of(thousand))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("1001");
    assertThatThrownBy(() -> overrides(1, 0)).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> overrides(1, 65_536)).isInstanceOf(IllegalArgumentException.class);
  }

  // --- WAL payload format 1 and 2 ------------------------------------------------------------

  @Test
  @Tag("req:C-129")
  @DisplayName(
      "T-SV-017c WAL payload: without overrides format 1 byte for byte as before, with overrides"
          + " format 2; both round-trip to the identical context")
  void walFormats() throws Exception {
    TransactionBatch batch =
        TransactionBatch.newBuilder().addRecords(TransactionRecord.newBuilder().setTxId(9)).build();
    SessionContext plain = session().atReceipt(RECEIVED + 5, 0, 750);
    byte[] one = WalDataPayload.of(plain, batch.toByteString()).encode();
    assertThat(one[0]).isEqualTo((byte) WalDataPayload.FORMAT);
    // The previous version's layout (8-argument payload, no override field): identical bytes.
    byte[] previous =
        new WalDataPayload(
                plain.appId(),
                plain.env(),
                plain.sessionId(),
                plain.ipMode(),
                plain.userIdMode(),
                plain.apdexTMs(),
                plain.appVersion(),
                batch.toByteString())
            .encode();
    assertThat(one).isEqualTo(previous);
    WalDataPayload decodedOne = WalDataPayload.decode(one);
    assertThat(decodedOne.apdexOverrides()).isSameAs(ApdexOverrides.NONE);
    assertThat(decodedOne.context(3, 99L, RECEIVED + 5, 0)).isEqualTo(plain);

    ApdexOverrides o = overrides(SERVICE_A, 200, SERVICE_B, 3_000);
    SessionContext withOverrides = session().atReceipt(RECEIVED + 6, 10_000, 1_000, o);
    byte[] two = WalDataPayload.of(withOverrides, batch.toByteString()).encode();
    assertThat(two[0]).isEqualTo((byte) WalDataPayload.FORMAT_WITH_OVERRIDES);
    assertThat(two.length).isEqualTo(one.length + 2 + 2 * 12);
    WalDataPayload decodedTwo = WalDataPayload.decode(two);
    assertThat(decodedTwo.apdexOverrides()).isEqualTo(o);
    assertThat(TransactionBatch.parseFrom(decodedTwo.batch())).isEqualTo(batch);
    SessionContext rebuilt = decodedTwo.context(3, 99L, RECEIVED + 6, 10_000);
    assertThat(rebuilt).isEqualTo(withOverrides);
    assertThat(rebuilt.apdexTMsFor(SERVICE_A)).isEqualTo(200);
    assertThat(rebuilt.apdexTMsFor(SERVICE_B)).isEqualTo(3_000);
    assertThat(rebuilt.apdexTMsFor(42)).isEqualTo(1_000);
  }

  @Test
  @Tag("req:C-129")
  @DisplayName(
      "T-SV-017c format 2 with no entry, more than 1,000 entries, a repeated id or a cut list is"
          + " refused")
  void walFormatTwoRefused() {
    byte[] two =
        WalDataPayload.of(
                session().atReceipt(RECEIVED, 0, 500, overrides(SERVICE_A, 200)),
                TransactionBatch.getDefaultInstance().toByteString())
            .encode();
    int countAt = two.length - 2 - 12; // u16 n, one entry, empty batch
    byte[] zero = Arrays.copyOf(two, countAt + 2);
    ByteBuffer.wrap(zero).putShort(countAt, (short) 0);
    assertThatThrownBy(() -> WalDataPayload.decode(zero))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("0 service Apdex overrides");
    byte[] tooMany = two.clone();
    ByteBuffer.wrap(tooMany).putShort(countAt, (short) 1_001);
    assertThatThrownBy(() -> WalDataPayload.decode(tooMany))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("1001 service Apdex overrides");
    ByteBuffer repeated = ByteBuffer.allocate(two.length + 12).put(two, 0, countAt);
    repeated.putShort((short) 2).putLong(SERVICE_A).putInt(200).putLong(SERVICE_A).putInt(300);
    assertThatThrownBy(() -> WalDataPayload.decode(repeated.array()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("repeated");
    assertThatThrownBy(() -> WalDataPayload.decode(Arrays.copyOf(two, two.length - 4)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Truncated");
  }

  // --- per-row choice of the writer ----------------------------------------------------------

  @Test
  @DisplayName(
      "T-SV-017b xlog.apdex_t_ms: the service's override of the receipt snapshot, else the app's"
          + " T; a replay of the WAL record writes the same values")
  void perRowStamp() {
    RecordDecoder decoder =
        IngestTestDoubles.decoder(
            new IngestTestDoubles.MemoryStore(), new IngestMetrics(new SimpleMeterRegistry()));
    SessionContext s = session().atReceipt(RECEIVED, 0, 1_000, overrides(SERVICE_A, 200));
    TransactionBatch batch =
        TransactionBatch.newBuilder()
            .addRecords(tx(1, SERVICE_A))
            .addRecords(tx(2, SERVICE_B))
            .addRecords(tx(3, SERVICE_A))
            .build();
    WalRecord record = IngestTestDoubles.record(WalRecordType.TX, s, batch, 1);
    var live = decoder.decode(record, EnumSet.allOf(Table.class), true);
    List<Map<String, Object>> rows =
        RowBinaryReader.rows(Table.XLOG, live.rows().get(Table.XLOG).rowBinary().toByteArray());
    assertThat(rows).extracting(r -> r.get("apdex_t_ms")).containsExactly(200, 1_000, 200);
    var replay = decoder.decode(record, EnumSet.of(Table.XLOG), false);
    assertThat(replay.rows().get(Table.XLOG).rowBinary())
        .isEqualTo(live.rows().get(Table.XLOG).rowBinary());
  }

  private static TransactionRecord tx(long n, long serviceId) {
    return TransactionRecord.newBuilder()
        .setTxId(((RECEIVED - 1_000) << 23) | n)
        .setKind(TransactionKind.HTTP)
        .setServiceId(serviceId)
        .setStartTsMs(RECEIVED - 1_000)
        .setElapsedUs(300_000)
        .setHttpMethod("GET")
        .setHttpStatus(200)
        .build();
  }

  // --- stamper and change service -------------------------------------------------------------

  @Test
  @DisplayName(
      "T-SV-017b the stamper serves the app's T and its overrides; a set above 1,000 is refused"
          + " and counted")
  void stamperLoadsAndRefuses() {
    IngestTestDoubles.MemoryStore store = new IngestTestDoubles.MemoryStore();
    store.apdex.put(7, 800);
    store.apdex.put(8, 500);
    store.overrides.put(7, new HashMap<>(Map.of(SERVICE_A, 150)));
    Map<Long, Integer> many = new HashMap<>();
    for (long i = 0; i <= ApdexOverrides.MAX_PER_APP; i++) {
      many.put(i, 100);
    }
    store.overrides.put(8, many);
    SimpleMeterRegistry registry = new SimpleMeterRegistry();
    ApdexStamper stamper = new ApdexStamper(store, new IngestMetrics(registry));
    assertThat(stamper.refresh()).isTrue();
    assertThat(stamper.apdexTMs(7)).isEqualTo(800);
    assertThat(stamper.overrides(7).tFor(SERVICE_A, 800)).isEqualTo(150);
    assertThat(stamper.overrides(8)).isSameAs(ApdexOverrides.NONE);
    assertThat(stamper.overrides(9)).isSameAs(ApdexOverrides.NONE);
    assertThat(
            registry.get("argos.ingest.errors").tag("stage", "apdex_overrides").counter().count())
        .isEqualTo(1.0);
    store.overrides.remove(7);
    assertThat(stamper.refresh()).isTrue();
    assertThat(stamper.overrides(7)).isSameAs(ApdexOverrides.NONE);
  }

  @Test
  @DisplayName(
      "T-ACC-17b a change writes one apdex_t_change event (old, new, service) and reloads the"
          + " stamp at once; no change, no event; the 1,001st override is refused")
  void changeService() throws Exception {
    IngestTestDoubles.MemoryStore store = new IngestTestDoubles.MemoryStore();
    store.apdex.put(7, 500);
    ApdexStamper stamper = new ApdexStamper(store);
    assertThat(stamper.refresh()).isTrue();
    Instant now = Instant.parse("2026-11-23T10:00:00Z");
    ApdexChangeService changes =
        new ApdexChangeService(store, stamper, Clock.fixed(now, ZoneOffset.UTC));

    ApdexChange app = changes.setAppT(7, 1_000, null).orElseThrow();
    assertThat(app.oldTMs()).isEqualTo(500);
    assertThat(app.newTMs()).isEqualTo(1_000);
    assertThat(app.ts()).isEqualTo(now);
    assertThat(app.detailJson())
        .isEqualTo("{\"old_t_ms\":500,\"new_t_ms\":1000,\"service_id\":null}");
    assertThat(app.title()).isEqualTo("Apdex T changed from 500 ms to 1000 ms");
    assertThat(stamper.apdexTMs(7)).as("reloaded without waiting").isEqualTo(1_000);

    ApdexChange service = changes.setServiceT(7, SERVICE_B, 200, null).orElseThrow();
    assertThat(service.oldTMs()).isEqualTo(1_000);
    assertThat(service.newTMs()).isEqualTo(200);
    assertThat(service.detailJson())
        .isEqualTo(
            "{\"old_t_ms\":1000,\"new_t_ms\":200,\"service_id\":\""
                + Long.toUnsignedString(SERVICE_B)
                + "\"}");
    assertThat(stamper.overrides(7).tFor(SERVICE_B, 1_000)).isEqualTo(200);

    assertThat(changes.setServiceT(7, SERVICE_B, 200, null).orElseThrow().changed()).isFalse();
    assertThat(changes.setAppT(7, 1_000, null).orElseThrow().changed()).isFalse();
    ApdexChange cleared = changes.clearServiceT(7, SERVICE_B, null).orElseThrow();
    assertThat(cleared.oldTMs()).isEqualTo(200);
    assertThat(cleared.newTMs()).isEqualTo(1_000);
    assertThat(stamper.overrides(7)).isSameAs(ApdexOverrides.NONE);
    assertThat(store.events).hasSize(3);
    assertThat(changes.setAppT(99, 700, null)).isEmpty();
    assertThatThrownBy(() -> changes.setAppT(7, 0, null))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> changes.setServiceT(7, 1, 65_536, null))
        .isInstanceOf(IllegalArgumentException.class);

    for (long i = 0; i < ApdexOverrides.MAX_PER_APP; i++) {
      changes.setServiceT(7, i, 300, null);
    }
    assertThatThrownBy(() -> changes.setServiceT(7, 5_000, 300, null))
        .isInstanceOf(OverrideLimitException.class);
    assertThat(changes.setServiceT(7, 3, 400, null).orElseThrow().changed())
        .as("changing an existing override at the limit")
        .isTrue();
    assertThat(stamper.overrides(7).size()).isEqualTo(1_000);
  }
}
