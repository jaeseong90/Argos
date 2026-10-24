/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.storage.queries.procedures;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.argos.storage.queries.Parameters;
import io.argos.storage.schema.SchemaParameters;
import io.argos.storage.schema.SchemaRenderer;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Procedure parts that need no server: the Q17 live window and the Q20 proposed codecs. */
class ProceduresTest {

  @Test
  @DisplayName(
      "Q17 live window: consecutive iterations after the server's minute moved use different"
          + " end_minute values; one server request per refresh interval")
  void liveWindowFollowsTheServerMinute() {
    AtomicLong clock = new AtomicLong();
    List<String> minutes = List.of("2026-10-24 10:00:00", "2026-10-24 10:01:00");
    AtomicLong requests = new AtomicLong();
    Supplier<String> server = () -> minutes.get((int) Math.min(1, requests.getAndIncrement()));
    ConcurrentLoad.LiveWindow live =
        new ConcurrentLoad.LiveWindow(server, Duration.ofSeconds(1), clock::get);
    Parameters base = Parameters.of(Map.of("end_minute", "2026-10-23 08:00:00", "app", "3"));

    List<String> used = new ArrayList<>();
    used.add(live.apply(base).get("end_minute")); // iteration 1 at t = 0
    clock.addAndGet(Duration.ofMillis(300).toNanos());
    used.add(live.apply(base).get("end_minute")); // same second: reused, no request
    clock.addAndGet(Duration.ofSeconds(5).toNanos()); // the next iteration of a user (5 s later)
    used.add(live.apply(base).get("end_minute"));

    assertThat(used)
        .containsExactly("2026-10-24 10:00:00", "2026-10-24 10:00:00", "2026-10-24 10:01:00");
    assertThat(used.get(2)).as("consecutive iterations of a user").isNotEqualTo(used.get(0));
    assertThat(requests.get()).isEqualTo(2);
    assertThat(live.apply(base).get("app")).isEqualTo("3");
    assertThat(live.seen()).containsExactly("2026-10-24 10:00:00", "2026-10-24 10:01:00");
  }

  @Test
  @DisplayName(
      "Q17 live window: a failed refresh keeps the previous minute; the first must succeed;"
          + " fixed windows without ingestion")
  void liveWindowFailures() {
    AtomicLong clock = new AtomicLong();
    AtomicLong calls = new AtomicLong();
    Supplier<String> flaky =
        () -> {
          if (calls.getAndIncrement() == 1) {
            throw new IllegalStateException("server busy");
          }
          return "2026-10-24 10:0" + calls.get() + ":00";
        };
    ConcurrentLoad.LiveWindow live =
        new ConcurrentLoad.LiveWindow(flaky, Duration.ofSeconds(1), clock::get);
    assertThat(live.endMinute()).isEqualTo("2026-10-24 10:01:00");
    clock.addAndGet(Duration.ofSeconds(2).toNanos());
    assertThat(live.endMinute()).isEqualTo("2026-10-24 10:01:00");
    assertThat(live.failures()).isEqualTo(1);
    ConcurrentLoad.LiveWindow broken =
        new ConcurrentLoad.LiveWindow(
            () -> {
              throw new IllegalStateException("down");
            },
            Duration.ofSeconds(1),
            clock::get);
    assertThatThrownBy(broken::endMinute).hasMessageContaining("down");

    assertThat(ConcurrentLoad.Options.bench("replay 5000 TPS").window())
        .isEqualTo(ConcurrentLoad.Window.LIVE);
    assertThat(ConcurrentLoad.Options.bench("none").window())
        .isEqualTo(ConcurrentLoad.Window.FIXED);
    assertThat(ConcurrentLoad.Window.parse("live")).isEqualTo(ConcurrentLoad.Window.LIVE);
  }

  @Test
  @DisplayName(
      "Q20 proposed variant: LZ4 stages -> ZSTD(1), xlog parent_span_id and trace_id without"
          + " CODEC(NONE), span_id kept, tx_id Delta + ZSTD(1), default ZSTD(1)")
  void proposedCodecs() {
    SchemaRenderer renderer = SchemaRenderer.fromRepository();
    SchemaParameters schema = SchemaParameters.defaults().withTablePrefix("q20_proposed_");
    String xlog = CompressionComparison.variantStatement(renderer, schema, "xlog", "proposed");
    assertThat(xlog)
        .startsWith("CREATE TABLE IF NOT EXISTS argos.q20_proposed_xlog")
        .doesNotContain("LZ4")
        .doesNotContain("--")
        .containsPattern(
            "\\n\\s*ts\\s+DateTime64\\(3, 'UTC'\\) CODEC\\(DoubleDelta, ZSTD\\(1\\)\\),")
        .containsPattern("\\n\\s*tx_id\\s+UInt64 CODEC\\(Delta, ZSTD\\(1\\)\\),")
        .containsPattern("\\n\\s*trace_id\\s+FixedString\\(16\\),")
        .containsPattern("\\n\\s*span_id\\s+UInt64 CODEC\\(NONE\\),")
        .containsPattern("\\n\\s*parent_span_id\\s+UInt64,")
        .containsPattern("\\n\\s*elapsed_ms\\s+UInt32 CODEC\\(T64, ZSTD\\(1\\)\\),")
        .containsPattern("\\n\\s*user_id\\s+String CODEC\\(ZSTD\\(3\\)\\),")
        .containsPattern("\\n\\s*alloc_bytes\\s+Int64\\s+CODEC\\(T64, ZSTD\\(1\\)\\),")
        .contains("non_replicated_deduplication_window = 1000")
        .endsWith("default_compression_codec = 'ZSTD(1)'");
    String metric = CompressionComparison.variantStatement(renderer, schema, "metric", "proposed");
    assertThat(metric)
        .doesNotContain("LZ4")
        .containsPattern("value\\s+Float64 CODEC\\(Gorilla, ZSTD\\(1\\)\\)")
        .endsWith("default_compression_codec = 'ZSTD(1)'");
    String profile =
        CompressionComparison.variantStatement(renderer, schema, "profile_step", "proposed");
    assertThat(profile).containsPattern("\\n\\s*tx_id\\s+UInt64 CODEC\\(Delta, ZSTD\\(1\\)\\),");
    // The other variants are unchanged by the proposal.
    assertThat(CompressionComparison.variantStatement(renderer, schema, "xlog", "base"))
        .contains("CODEC(DoubleDelta, LZ4)")
        .contains("parent_span_id   UInt64 CODEC(NONE)");
  }
}
