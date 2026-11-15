/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.ingest;

import static org.assertj.core.api.Assertions.assertThat;

import io.argos.proto.v1.DictionaryEntry;
import io.argos.proto.v1.DictionaryKind;
import io.argos.server.testing.auth.MutableClock;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** Dictionary caps, overflow mapping and requests (11 §7, 12 §3.5 (2), 16 §4.4, SEC-13). */
@Tag("req:SEC-13")
@Tag("req:DSN-12-05")
class DictionaryResolverTest {

  private IngestTestDoubles.MemoryStore store;
  private IngestTestDoubles.RecordingMessenger messenger;
  private SimpleMeterRegistry registry;
  private MutableClock clock;

  @BeforeEach
  void setUp() {
    store = new IngestTestDoubles.MemoryStore();
    messenger = new IngestTestDoubles.RecordingMessenger();
    registry = new SimpleMeterRegistry();
    clock = new MutableClock();
  }

  private DictionaryResolver resolver(int perHour, long perApp) {
    return new DictionaryResolver(
        store,
        IngestTestDoubles.provider(messenger),
        new IngestMetrics(registry),
        perHour,
        perApp,
        clock);
  }

  private static List<DictionaryEntry> services(int from, int count) {
    List<DictionaryEntry> entries = new ArrayList<>();
    for (int i = from; i < from + count; i++) {
      entries.add(
          DictionaryEntry.newBuilder()
              .setKind(DictionaryKind.SERVICE)
              .setHash(1_000_000L + i)
              .setText("GET /svc/" + i)
              .build());
    }
    return entries;
  }

  @Test
  @DisplayName("T-SV-014a beyond 10 new entries per instance-hour (scaled cap) rows get <overflow>")
  void instanceHourCap() {
    DictionaryResolver r = resolver(10, 1_000_000);
    r.onDictionaryEntries(7, 3, services(0, 25));
    r.drainNow();
    long stored =
        store.dictionary.values().stream().filter(d -> d.kind().equals("service")).count();
    assertThat(stored).isEqualTo(11); // 10 entries + the <overflow> row
    assertThat(store.dictionary)
        .containsKey(
            IngestTestDoubles.MemoryStore.key(7, "service", DictionaryResolver.OVERFLOW_ID));
    assertThat(r.resolve(7, 3, DictionaryKind.SERVICE, 1_000_000L)).isEqualTo(1_000_000L);
    assertThat(r.resolve(7, 3, DictionaryKind.SERVICE, 1_000_020L))
        .isEqualTo(DictionaryResolver.OVERFLOW_ID);
    assertThat(registry.get("argos.dictionary.overflow").counter().count()).isEqualTo(15.0);

    // Another instance has its own hourly budget; the next hour resets this one.
    r.onDictionaryEntries(7, 4, services(100, 5));
    r.drainNow();
    assertThat(r.isKnown(7, DictionaryKind.SERVICE, 1_000_104L)).isTrue();
    clock.advance(Duration.ofHours(1));
    r.onDictionaryEntries(7, 3, services(200, 3));
    r.drainNow();
    assertThat(r.isKnown(7, DictionaryKind.SERVICE, 1_000_202L)).isTrue();
  }

  @Test
  @DisplayName(
      "T-SV-014b the per-app total cap (scaled) maps new entries of every instance to <overflow>")
  void appCap() {
    DictionaryResolver r = resolver(1_000, 8);
    r.onDictionaryEntries(7, 1, services(0, 5));
    r.onDictionaryEntries(7, 2, services(5, 5));
    r.drainNow();
    assertThat(store.dictionaryCount(7)).isEqualTo(9); // 8 + <overflow>
    assertThat(r.isOverflowed(7, DictionaryKind.SERVICE, 1_000_009L)).isTrue();
  }

  @Test
  @DisplayName(
      "T-SV-014c entries already stored do not use up the cap when an agent sends them again")
  void resentEntriesAreNotNew() {
    DictionaryResolver first = resolver(10, 1_000_000);
    first.onDictionaryEntries(7, 3, services(0, 10));
    first.drainNow();
    // A restarted server (empty cache) and a restarted agent that sends the same 10 entries.
    DictionaryResolver second = resolver(10, 1_000_000);
    second.onDictionaryEntries(7, 3, services(0, 10));
    second.onDictionaryEntries(7, 3, services(10, 10));
    second.drainNow();
    assertThat(second.isOverflowed(7, DictionaryKind.SERVICE, 1_000_009L)).isFalse();
    assertThat(second.isKnown(7, DictionaryKind.SERVICE, 1_000_019L)).isTrue();
  }

  @Test
  @DisplayName(
      "11 §7 unknown ids are looked up, requested from the agent at most 3 times, then unresolved")
  void requestsAndUnresolved() {
    DictionaryResolver r = resolver(10_000, 1_000_000);
    store.dictionary.put(
        IngestTestDoubles.MemoryStore.key(7, "class", 55L),
        new IngestStore.DictionaryRow(
            7, "class", 55L, "java.lang.IllegalStateException", false, null));
    assertThat(r.resolve(7, 3, DictionaryKind.CLASS, 55L)).isEqualTo(55L);
    assertThat(r.resolve(7, 3, DictionaryKind.SERVICE, 66L)).isEqualTo(66L);
    r.drainNow();
    assertThat(r.isKnown(7, DictionaryKind.CLASS, 55L)).isTrue(); // found in PostgreSQL
    assertThat(messenger.requests).hasSize(1);
    assertThat(messenger.requests.get(0).getRefsList())
        .extracting(ref -> ref.getHash())
        .containsExactly(66L);
    for (int i = 0; i < 2; i++) {
      clock.advance(Duration.ofSeconds(6));
      r.drainNow();
    }
    assertThat(messenger.requests).hasSize(3);
    assertThat(r.unresolvedCount()).isEqualTo(1);
    assertThat(registry.get("argos.dictionary.unresolved").gauge().value()).isEqualTo(1.0);
    // The answer arrives late: the id is stored and no longer unresolved.
    r.onDictionaryEntries(
        7,
        3,
        List.of(
            DictionaryEntry.newBuilder()
                .setKind(DictionaryKind.SERVICE)
                .setHash(66L)
                .setText("GET /x")
                .build()));
    r.drainNow();
    assertThat(r.unresolvedCount()).isZero();
  }

  @Test
  @DisplayName("11 §7 without a live session an attempt does not count")
  void noSessionNoAttempt() {
    messenger.live = false;
    DictionaryResolver r = resolver(10_000, 1_000_000);
    r.resolve(7, 3, DictionaryKind.HOST, 77L);
    for (int i = 0; i < 5; i++) {
      r.drainNow();
      clock.advance(Duration.ofSeconds(6));
    }
    assertThat(r.unresolvedCount()).isZero();
    messenger.live = true;
    for (int i = 0; i < 3; i++) {
      r.drainNow();
      clock.advance(Duration.ofSeconds(6));
    }
    assertThat(messenger.requests).hasSize(3);
    assertThat(r.unresolvedCount()).isEqualTo(1);
  }

  @Test
  @DisplayName(
      "13 §12.3 texts are cut at 64 KiB of UTF-8 with truncated; NUL cannot reach PostgreSQL")
  void textRules() {
    DictionaryResolver r = resolver(10_000, 1_000_000);
    r.onDictionaryEntries(
        7,
        3,
        List.of(
            DictionaryEntry.newBuilder()
                .setKind(DictionaryKind.SQL_TEXT)
                .setHash(5L)
                .setText("é".repeat(40_000) + "\0")
                .setNormVersion(2)
                .build()));
    r.drainNow();
    IngestStore.DictionaryRow row =
        store.dictionary.get(IngestTestDoubles.MemoryStore.key(7, "sql", 5L));
    assertThat(row.truncated()).isTrue();
    assertThat(row.text().getBytes(java.nio.charset.StandardCharsets.UTF_8).length)
        .isLessThanOrEqualTo(64 * 1024);
    assertThat(row.text()).doesNotContain("\0");
    assertThat(row.normVersion()).isEqualTo(2);
  }
}
