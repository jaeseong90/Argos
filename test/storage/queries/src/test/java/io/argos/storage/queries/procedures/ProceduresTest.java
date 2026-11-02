/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.storage.queries.procedures;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.argos.storage.queries.Parameters;
import io.argos.storage.schema.SchemaParameters;
import io.argos.storage.schema.SchemaRenderer;
import io.argos.storage.schema.SqlScript;
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
      "Q20 variants: base = V001 (C-54), lz4 = the pre-ADR-0007 V001 (LZ4 stages, CODEC(NONE),"
          + " default LZ4), proposed = base + profile_step.tx_id Delta")
  void q20Variants() {
    SchemaRenderer renderer = SchemaRenderer.fromRepository();
    SchemaParameters schema = SchemaParameters.defaults().withTablePrefix("q20_v_");
    String base = CompressionComparison.variantStatement(renderer, schema, "xlog", "base");
    assertThat(base)
        .startsWith("CREATE TABLE IF NOT EXISTS argos.q20_v_xlog")
        .doesNotContain("LZ4")
        .doesNotContain("default_compression_codec")
        .contains("CODEC(DoubleDelta, ZSTD(1))")
        .contains("tx_id            UInt64 CODEC(Delta, ZSTD(1)),");

    String lz4 = CompressionComparison.variantStatement(renderer, schema, "xlog", "lz4");
    assertThat(lz4)
        .doesNotContain("ZSTD(1)")
        .doesNotContain("--")
        .containsPattern("\\n\\s*ts\\s+DateTime64\\(3, 'UTC'\\) CODEC\\(DoubleDelta, LZ4\\),")
        .containsPattern("\\n\\s*tx_id\\s+UInt64,")
        .containsPattern("\\n\\s*trace_id\\s+FixedString\\(16\\) CODEC\\(NONE\\),")
        .containsPattern("\\n\\s*span_id\\s+UInt64 CODEC\\(NONE\\),")
        .containsPattern("\\n\\s*parent_span_id\\s+UInt64 CODEC\\(NONE\\),")
        .containsPattern("\\n\\s*elapsed_ms\\s+UInt32 CODEC\\(T64, LZ4\\),")
        .containsPattern("\\n\\s*user_id\\s+String CODEC\\(ZSTD\\(3\\)\\),")
        .contains("TYPE bloom_filter(0.01) GRANULARITY 4,\n    INDEX idx_request")
        .doesNotContain("GRANULARITY 1")
        .endsWith("default_compression_codec = 'LZ4'");
    assertThat(CompressionComparison.variantStatement(renderer, schema, "metric", "lz4"))
        .containsPattern("value\\s+Float64 CODEC\\(Gorilla, LZ4\\)")
        .endsWith("default_compression_codec = 'LZ4'");
    assertThat(CompressionComparison.variantStatement(renderer, schema, "profile_step", "lz4"))
        .contains("CODEC(ZSTD(3))")
        .doesNotContain("ZSTD(1)");
    assertThat(CompressionComparison.variantStatement(renderer, schema, "xlog", "lz4cols_zstd"))
        .contains("CODEC(T64, LZ4)")
        .endsWith("default_compression_codec = 'ZSTD(1)'");
    assertThat(CompressionComparison.variantStatement(renderer, schema, "xlog", "lz4_nocodec"))
        .doesNotContain("CODEC")
        .endsWith("default_compression_codec = 'LZ4'");
    assertThat(CompressionComparison.variantStatement(renderer, schema, "xlog", "nocodec"))
        .doesNotContain("CODEC")
        .endsWith("default_compression_codec = 'ZSTD(1)'");

    // The WO-03 proposal on the old definition: xlog columns as V001, profile_step.tx_id Delta.
    String proposed = CompressionComparison.variantStatement(renderer, schema, "xlog", "proposed");
    assertThat(proposed)
        .doesNotContain("LZ4")
        .containsPattern("\\n\\s*tx_id\\s+UInt64 CODEC\\(Delta, ZSTD\\(1\\)\\),")
        .containsPattern("\\n\\s*trace_id\\s+FixedString\\(16\\),")
        .containsPattern("\\n\\s*span_id\\s+UInt64 CODEC\\(NONE\\),")
        .containsPattern("\\n\\s*parent_span_id\\s+UInt64,")
        .endsWith("default_compression_codec = 'ZSTD(1)'");
    assertThat(stripSettingAndSpace(proposed))
        .isEqualTo(
            stripSettingAndSpace(SqlScript.withoutComments(base))
                .replace("GRANULARITY 1", "GRANULARITY 4"));
    assertThat(CompressionComparison.variantStatement(renderer, schema, "profile_step", "proposed"))
        .containsPattern("\\n\\s*tx_id\\s+UInt64 CODEC\\(Delta, ZSTD\\(1\\)\\),");
    assertThat(CompressionComparison.variantStatement(renderer, schema, "profile_step", "base"))
        .containsPattern("\\n\\s*tx_id\\s+UInt64,");
  }

  @Test
  @DisplayName(
      "Q05/bloom builds its own variant DDL from the V001 literals (C-55, C-56): all five indexes")
  void bloomVariantStatement() {
    SchemaRenderer renderer = SchemaRenderer.fromRepository();
    SchemaParameters schema = SchemaParameters.defaults().withTablePrefix("bv_f0250_g1_");
    String sql = BloomVariants.variantStatement(renderer, schema, 0.025, 1);
    assertThat(sql)
        .startsWith("CREATE TABLE IF NOT EXISTS argos.bv_f0250_g1_xlog")
        .contains("INDEX idx_tx      tx_id      TYPE bloom_filter(0.025) GRANULARITY 1,")
        .contains("INDEX idx_user    user_id    TYPE bloom_filter(0.025) GRANULARITY 1,")
        .contains("INDEX idx_sql     sql_ids    TYPE bloom_filter(0.025) GRANULARITY 1\n")
        .doesNotContain("bloom_filter(0.01)")
        .doesNotContain("{");
    assertThat(BloomVariants.variantStatement(renderer, schema, 0.01, 4))
        .contains("INDEX idx_user    user_id    TYPE bloom_filter(0.01) GRANULARITY 4,")
        .doesNotContain("GRANULARITY 1");
    assertThatThrownBy(() -> BloomVariants.variantStatement(renderer, schema, 1.5, 1))
        .isInstanceOf(IllegalArgumentException.class);
  }

  private static String stripSettingAndSpace(String create) {
    int settings = create.indexOf("SETTINGS");
    return create.substring(0, settings).replaceAll("\\s+", " ").strip();
  }
}
