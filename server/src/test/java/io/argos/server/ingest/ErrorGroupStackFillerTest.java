/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.ingest;

import static org.assertj.core.api.Assertions.assertThat;

import io.argos.server.ingest.IngestStore.DictionaryRow;
import io.argos.server.ingest.IngestStore.StackCandidate;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The representative stack of the error groups (12 §3.5 (4), WO-27 {@link ErrorGroupStackFiller}):
 * masked frames joined with newlines, written once, a group whose FRAME texts are missing waits for
 * a later run, at most 100 groups per run, failures counted in {@code
 * argos_ingest_errors_total{stage="error_stack"}}.
 */
@Tag("req:F-S19")
@Tag("req:F-U07")
@Tag("req:SEC-36")
class ErrorGroupStackFillerTest {

  private static final int APP = 7;
  private static final Instant NOW = Instant.parse("2026-11-23T10:00:00Z");

  /** A clock that the test moves. */
  private static final class MovingClock extends Clock {
    Instant now = NOW;

    @Override
    public ZoneId getZone() {
      return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
      return this;
    }

    @Override
    public Instant instant() {
      return now;
    }
  }

  /** Frame keys per group, and how often it was asked. */
  private static final class Frames implements ErrorGroupStackFiller.FrameSource {
    final Map<Long, List<Long>> byGroup = new HashMap<>();
    final AtomicInteger calls = new AtomicInteger();
    final List<Collection<Long>> asked = new ArrayList<>();
    volatile boolean failing;

    @Override
    public Map<Long, List<Long>> frames(
        int appId, Collection<Long> groups, Instant from, Instant to) throws Exception {
      calls.incrementAndGet();
      asked.add(List.copyOf(groups));
      if (failing) {
        throw new Exception("ClickHouse down");
      }
      assertThat(Duration.between(from, to)).isGreaterThanOrEqualTo(Duration.ofDays(1));
      Map<Long, List<Long>> out = new HashMap<>();
      for (long g : groups) {
        if (byGroup.containsKey(g)) {
          out.put(g, byGroup.get(g));
        }
      }
      return out;
    }
  }

  private static void frame(IngestTestDoubles.MemoryStore store, long hash, String text) {
    store.dictionary.put(
        IngestTestDoubles.MemoryStore.key(APP, "frame", hash),
        new DictionaryRow(APP, "frame", hash, text, false, null));
  }

  private static double errors(SimpleMeterRegistry registry) {
    var counter = registry.find("argos.ingest.errors").tag("stage", "error_stack").counter();
    return counter == null ? 0 : counter.count();
  }

  @Test
  @DisplayName(
      "T-SV-016e frames masked again (email, IPv4), line breaks removed, joined top frame first;"
          + " overflow key as <overflow>; a missing text gives no stack; long frames cut")
  void stackText() {
    Map<Long, String> texts = new HashMap<>();
    texts.put(1L, "com.shop.Mailer.send(kim@example.com)");
    texts.put(2L, "com.shop.Gateway.call(10.1.2.3)\r\nnext");
    texts.put(3L, "x".repeat(2_000));
    String stack =
        ErrorGroupStackFiller.stack(List.of(1L, 2L, DictionaryResolver.OVERFLOW_ID, 3L), texts);
    String[] lines = stack.split("\n", -1);
    assertThat(lines).hasSize(4);
    assertThat(lines[0]).isEqualTo("com.shop.Mailer.send(<email>)");
    assertThat(lines[1]).isEqualTo("com.shop.Gateway.call(<ip>)  next");
    assertThat(lines[2]).isEqualTo("<overflow>");
    assertThat(lines[3]).hasSize(ErrorGroupStackFiller.MAX_FRAME_CHARS);
    assertThat(ErrorGroupStackFiller.stack(List.of(1L, 9L), texts)).isNull();
    assertThat(ErrorGroupStackFiller.stack(List.of(), texts)).isNull();
    assertThat(ErrorGroupStackFiller.stack(null, texts)).isNull();
    List<Long> many = new ArrayList<>();
    for (int i = 0; i < 100; i++) {
      many.add(1L);
    }
    assertThat(ErrorGroupStackFiller.stack(many, texts).split("\n", -1))
        .hasSize(ErrorGroupStackFiller.MAX_FRAMES);
  }

  @Test
  @DisplayName(
      "T-SV-016e a group is filled once; a group whose frame text is missing waits, and is filled"
          + " after the text arrives and the retry wait has passed")
  void fillsOnceAndWaitsForLateDictionary() {
    IngestTestDoubles.MemoryStore store = new IngestTestDoubles.MemoryStore();
    SimpleMeterRegistry registry = new SimpleMeterRegistry();
    Frames frames = new Frames();
    MovingClock clock = new MovingClock();
    ErrorGroupStackFiller filler =
        new ErrorGroupStackFiller(store, frames, new IngestMetrics(registry), clock);
    store.withoutStack.add(new StackCandidate(APP, 100));
    store.withoutStack.add(new StackCandidate(APP, 200));
    frames.byGroup.put(100L, List.of(11L, 12L));
    frames.byGroup.put(200L, List.of(21L, 22L));
    frame(store, 11, "a.B.c(B.java)");
    frame(store, 12, "a.B.main(B.java)");
    frame(store, 21, "x.Y.z(Y.java)"); // 22 arrives late

    ErrorGroupStackFiller.Run first = filler.runOnce();
    assertThat(first).isEqualTo(new ErrorGroupStackFiller.Run(2, 1, 1, 0));
    assertThat(store.stacks).containsEntry(APP + "/100", "a.B.c(B.java)\na.B.main(B.java)");
    assertThat(store.stacks).doesNotContainKey(APP + "/200");

    frame(store, 22, "x.Y.main(Y.java)");
    ErrorGroupStackFiller.Run waiting = filler.runOnce();
    assertThat(waiting.candidates()).as("group 200 waits for the retry").isZero();
    clock.now = NOW.plus(Duration.ofMinutes(ErrorGroupStackFiller.RETRY_MINUTES));
    ErrorGroupStackFiller.Run later = filler.runOnce();
    assertThat(later).isEqualTo(new ErrorGroupStackFiller.Run(1, 1, 0, 0));
    assertThat(store.stacks).containsEntry(APP + "/200", "x.Y.z(Y.java)\nx.Y.main(Y.java)");

    // Written once: a later stack of the same group never replaces the first.
    assertThat(store.fillSampleStack(APP, 100, "other")).isFalse();
    assertThat(store.stacks).containsEntry(APP + "/100", "a.B.c(B.java)\na.B.main(B.java)");
    assertThat(filler.runOnce().candidates()).isZero();
    assertThat(errors(registry)).isZero();
  }

  @Test
  @DisplayName("T-SV-016e at most 100 groups per run; the rest wait for the next run")
  void boundedPerRun() {
    IngestTestDoubles.MemoryStore store = new IngestTestDoubles.MemoryStore();
    Frames frames = new Frames();
    frame(store, 1, "a.B.c(B.java)");
    for (long g = 1; g <= 250; g++) {
      store.withoutStack.add(new StackCandidate(APP, g));
      frames.byGroup.put(g, List.of(1L));
    }
    ErrorGroupStackFiller filler =
        new ErrorGroupStackFiller(
            store,
            frames,
            new IngestMetrics(new SimpleMeterRegistry()),
            Clock.fixed(NOW, ZoneOffset.UTC));
    assertThat(filler.runOnce().filled()).isEqualTo(ErrorGroupStackFiller.MAX_GROUPS_PER_RUN);
    assertThat(frames.asked.get(0)).hasSize(ErrorGroupStackFiller.MAX_GROUPS_PER_RUN);
    assertThat(filler.runOnce().filled()).isEqualTo(100);
    assertThat(filler.runOnce().filled()).isEqualTo(50);
    assertThat(store.stacks).hasSize(250);
  }

  @Test
  @DisplayName(
      "T-SV-016e a failed ClickHouse read or PostgreSQL read is counted in"
          + " argos_ingest_errors_total{stage=error_stack} and retried at the next run")
  void failuresCounted() {
    IngestTestDoubles.MemoryStore store = new IngestTestDoubles.MemoryStore();
    SimpleMeterRegistry registry = new SimpleMeterRegistry();
    Frames frames = new Frames();
    ErrorGroupStackFiller filler =
        new ErrorGroupStackFiller(
            store, frames, new IngestMetrics(registry), Clock.fixed(NOW, ZoneOffset.UTC));
    store.withoutStack.add(new StackCandidate(APP, 5));
    frames.byGroup.put(5L, List.of(1L));
    frame(store, 1, "a.B.c(B.java)");

    frames.failing = true;
    assertThat(filler.runOnce()).isEqualTo(new ErrorGroupStackFiller.Run(1, 0, 0, 1));
    assertThat(errors(registry)).isEqualTo(1.0);
    store.failing = true;
    assertThat(filler.runOnce().failures()).isEqualTo(1);
    assertThat(errors(registry)).isEqualTo(2.0);

    frames.failing = false;
    store.failing = false;
    assertThat(filler.runOnce()).isEqualTo(new ErrorGroupStackFiller.Run(1, 1, 0, 0));
    assertThat(store.stacks).containsEntry(APP + "/5", "a.B.c(B.java)");
  }
}
