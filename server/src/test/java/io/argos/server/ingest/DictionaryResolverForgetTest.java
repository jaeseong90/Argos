/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.ingest;

import static org.assertj.core.api.Assertions.assertThat;

import io.argos.proto.v1.DictionaryEntry;
import io.argos.proto.v1.DictionaryKind;
import io.argos.proto.v1.DictionaryRef;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Cache coherence of the nightly dictionary clean-up (SEC-13, 17 §9.3, ADR-0010 C-238 (14)): a key
 * whose row was deleted is forgotten by {@link DictionaryResolver#forgetStored}, so the next row
 * that names it looks it up, asks the agent and stores the answer again; the per-app total of the
 * cap is reloaded after a clean-up.
 */
@Tag("req:SEC-13")
class DictionaryResolverForgetTest {

  private final IngestTestDoubles.MemoryStore store = new IngestTestDoubles.MemoryStore();
  private final IngestTestDoubles.RecordingMessenger messenger =
      new IngestTestDoubles.RecordingMessenger();

  private DictionaryResolver resolver(long appCap) {
    return new DictionaryResolver(
        store,
        IngestTestDoubles.provider(messenger),
        new IngestMetrics(new SimpleMeterRegistry()),
        10_000,
        appCap,
        Clock.fixed(Instant.parse("2026-11-25T03:30:00Z"), ZoneOffset.UTC));
  }

  private static DictionaryEntry service(long hash) {
    return DictionaryEntry.newBuilder()
        .setKind(DictionaryKind.SERVICE)
        .setHash(hash)
        .setText("GET /svc/" + hash)
        .build();
  }

  @Test
  @DisplayName(
      "T-SV-014e a forgotten key (row deleted by the clean-up) is looked up, requested from the"
          + " agent and stored again when it answers; a key that was never cached reports false")
  void forgottenKeyIsRequestedAndStoredAgain() {
    DictionaryResolver r = resolver(1_000_000);
    r.onDictionaryEntries(7, 3, List.of(service(42L)));
    r.drainNow();
    assertThat(r.isKnown(7, DictionaryKind.SERVICE, 42L)).isTrue();

    // The clean-up deleted the row; without forgetStored the resolver would keep it as known.
    store.dictionary.remove(IngestTestDoubles.MemoryStore.key(7, "service", 42L));
    assertThat(r.forgetStored(7, "service", 42L)).isTrue();
    assertThat(r.isKnown(7, DictionaryKind.SERVICE, 42L)).isFalse();
    assertThat(r.forgetStored(7, "service", 43L)).isFalse();
    assertThat(r.forgetStored(7, "no_such_kind", 42L)).isFalse();

    // A long-lived agent keeps naming the id in rows without sending its text again.
    assertThat(r.resolve(7, 3, DictionaryKind.SERVICE, 42L)).isEqualTo(42L);
    r.drainNow();
    List<DictionaryRef> refs = new ArrayList<>();
    messenger.requests.forEach(req -> refs.addAll(req.getRefsList()));
    assertThat(refs)
        .containsExactly(
            DictionaryRef.newBuilder().setKind(DictionaryKind.SERVICE).setHash(42L).build());

    // The agent answers: stored again and known.
    r.onDictionaryEntries(7, 3, List.of(service(42L)));
    r.drainNow();
    assertThat(store.dictionary).containsKey(IngestTestDoubles.MemoryStore.key(7, "service", 42L));
    assertThat(r.isKnown(7, DictionaryKind.SERVICE, 42L)).isTrue();
  }

  @Test
  @DisplayName(
      "T-SV-014e after a clean-up the per-app total of the cap is reloaded: deleted entries free"
          + " their place for new ones")
  void appTotalReloadedAfterCleanup() {
    DictionaryResolver r = resolver(3);
    r.onDictionaryEntries(7, 3, List.of(service(1L), service(2L), service(3L)));
    r.drainNow();
    assertThat(store.dictionaryCount(7)).isEqualTo(3);
    // The clean-up deletes two entries.
    for (long h : new long[] {1L, 2L}) {
      store.dictionary.remove(IngestTestDoubles.MemoryStore.key(7, "service", h));
      r.forgetStored(7, "service", h);
    }
    r.onDictionaryEntries(7, 3, List.of(service(4L), service(5L)));
    r.drainNow();
    assertThat(r.isOverflowed(7, DictionaryKind.SERVICE, 4L)).isFalse();
    assertThat(r.isOverflowed(7, DictionaryKind.SERVICE, 5L)).isFalse();
    assertThat(r.isKnown(7, DictionaryKind.SERVICE, 5L)).isTrue();
  }
}
