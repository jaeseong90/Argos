/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.test.bench;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class JsonAndOptionsTest {

  @Test
  void jsonRoundTrip() {
    Map<String, Object> value = new LinkedHashMap<>();
    value.put("text", "a \"quoted\"\nline\t\u0001");
    value.put("long", 600000L);
    value.put("double", 0.125);
    value.put("integralDouble", 3.0);
    value.put("nan", Double.NaN);
    value.put("list", List.of(1, 2.5, "x"));
    value.put("array", new double[] {1.5, 2});
    value.put("nested", Map.of("ok", true));
    value.put("none", null);
    Object parsed = Json.parse(Json.write(value));
    assertThat(Json.at(parsed, "text")).isEqualTo("a \"quoted\"\nline\t\u0001");
    assertThat(Json.at(parsed, "long")).isEqualTo(600000L);
    assertThat(Json.number(parsed, "double")).isEqualTo(0.125);
    assertThat(Json.at(parsed, "integralDouble")).isEqualTo(3L);
    assertThat(Json.at(parsed, "nan")).isNull();
    assertThat(Json.array(Json.at(parsed, "list"))).containsExactly(1L, 2.5, "x");
    assertThat(Json.array(Json.at(parsed, "array"))).containsExactly(1.5, 2L);
    assertThat(Json.at(parsed, "nested", "ok")).isEqualTo(true);
    assertThat(Json.object(parsed)).containsKey("none");
    assertThat(Json.number(parsed, "missing", "path")).isNaN();
  }

  @Test
  void jsonParsesK6StyleNumbersAndEscapes() {
    Object parsed =
        Json.parse("{\"a\": -1.5e-3, \"b\": [true, false, null], \"c\": \"\\u00e9\\/\"}");
    assertThat(Json.number(parsed, "a")).isEqualTo(-0.0015);
    assertThat(Json.array(Json.at(parsed, "b"))).containsExactly(true, false, null);
    assertThat(Json.at(parsed, "c")).isEqualTo("é/");
    assertThatThrownBy(() -> Json.parse("{\"a\": 1} x"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> Json.parse("{\"a\": ")).isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void optionsParseValuesFlagsAndDurations() {
    Options o =
        Options.parse(
            List.of(
                "--out",
                "dir",
                "--vt",
                "--rounds=3",
                "--variants",
                "off, on,,otel",
                "--warmup",
                "1m30s"),
            Set.of("vt"));
    assertThat(o.required("out")).isEqualTo("dir");
    assertThat(o.flag("vt")).isTrue();
    assertThat(o.flag("keep-app-log")).isFalse();
    assertThat(o.integer("rounds", 1, 1, 20)).isEqualTo(3);
    assertThat(o.list("variants", "off,on")).containsExactly("off", "on", "otel");
    assertThat(o.duration("warmup", "3m")).isEqualTo(Duration.ofSeconds(90));
    assertThat(o.duration("duration", "10m")).isEqualTo(Duration.ofMinutes(10));
    o.rejectUnknown();
  }

  @Test
  void optionsRejectTyposAndBadValues() {
    Options o = Options.parse(List.of("--ruonds", "3"), Set.of());
    assertThat(o.integer("rounds", 3, 1, 20)).isEqualTo(3);
    assertThatThrownBy(o::rejectUnknown).hasMessageContaining("--ruonds");
    assertThatThrownBy(() -> Options.parse(List.of("--out"), Set.of()))
        .hasMessageContaining("needs a value");
    assertThatThrownBy(() -> Options.parse(List.of("x"), Set.of()))
        .isInstanceOf(UsageException.class);
    assertThatThrownBy(() -> Options.parse(List.of("--a", "1", "--a", "2"), Set.of()))
        .hasMessageContaining("twice");
    Options bad = Options.parse(List.of("--rounds", "0"), Set.of());
    assertThatThrownBy(() -> bad.integer("rounds", 3, 1, 20)).hasMessageContaining("1..20");
  }

  @Test
  void k6Durations() {
    assertThat(Options.parseDuration("500ms")).isEqualTo(Duration.ofMillis(500));
    assertThat(Options.parseDuration("45")).isEqualTo(Duration.ofSeconds(45));
    assertThat(Options.parseDuration("2h")).isEqualTo(Duration.ofHours(2));
    assertThatThrownBy(() -> Options.parseDuration("3 minutes"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> Options.parseDuration("0s"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThat(Options.k6Duration(Duration.ofMinutes(10))).isEqualTo("600s");
    assertThat(Options.k6Duration(Duration.ofMillis(1500))).isEqualTo("1500ms");
  }

  @Test
  void diagnosticVariantDefinitions() {
    Map<String, Map<String, String>> defs =
        Platform.parseVariantDefs(
            "nomod:instrumentation.disabled=servlet,jdbc | skip:enabled=false;log.level=info");
    assertThat(defs).containsOnlyKeys("nomod", "skip");
    assertThat(defs.get("nomod")).containsEntry("instrumentation.disabled", "servlet,jdbc");
    assertThat(defs.get("skip"))
        .containsEntry("enabled", "false")
        .containsEntry("log.level", "info");
    assertThat(Platform.parseVariantDefs("")).isEmpty();
    assertThatThrownBy(() -> Platform.parseVariantDefs("on:enabled=false"))
        .hasMessageContaining("built-in");
    assertThatThrownBy(() -> Platform.parseVariantDefs("x:enabled"))
        .hasMessageContaining("key=value");
  }
}
