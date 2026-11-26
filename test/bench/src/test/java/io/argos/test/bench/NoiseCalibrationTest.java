/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.test.bench;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** 18 open item 3: the noise numbers behind the off-run CV limit and the WARN step. */
@Tag("req:DSN-18-07")
@Tag("req:N-01")
@Tag("req:N-03")
class NoiseCalibrationTest {

  private static final double[] OFF = {600, 606, 594};
  private static final double[] OFF_P99 = {300.5, 300.6, 300.4};
  private static final double[] ON = {618, 624, 612};
  private static final double[] ON_P99 = {300.7, 300.9, 300.5};

  private static Map<String, Object> analyze(long seed) {
    return NoiseCalibration.analyze("main", OFF, OFF_P99, ON, ON_P99, List.of(), seed, 1000);
  }

  @Test
  @DisplayName(
      "T-BLD-015d CV, MDE and the WARN-step numbers of a known input; A-A intervals are"
          + " deterministic for seed 42")
  void knownInput() {
    Map<String, Object> a = analyze(42);
    // sd 6 around 600: CV exactly 1%
    assertThat(Json.number(a.get("offCpuCv"))).isCloseTo(0.01, within(1e-12));
    assertThat(a.get("offCpuCvOk")).isEqualTo(true);
    double factor = NoiseCalibration.Z_MDE * Math.sqrt(2.0 / 3);
    assertThat(Json.number(a.get("mdeRelativeCpu"))).isCloseTo(factor * 0.01, within(1e-12));
    assertThat(Json.number(a.get("mdeRelativeCpu"))).isCloseTo(0.022875, within(1e-6));
    assertThat(a.get("warnCpuDetectable")).isEqualTo(false);
    assertThat(Json.number(a.get("mdeP99Ms"))).isCloseTo(factor * 0.1, within(1e-9));
    assertThat(a.get("warnP99Detectable")).isEqualTo(true);
    assertThat(Json.number(a.get("cvForWarnStep"))).isCloseTo(0.015 / factor, within(1e-12));
    // 2 * (2.801585 * 0.01 / 0.015)^2 = 6.98 -> 7 rounds
    assertThat(Json.number(a.get("roundsForWarnStep"))).isEqualTo(7.0);
    // the A-A interval of the relative CPU lies around 0 within the range of the off values
    List<Object> ci = Json.array(a.get("aaRelativeCpuCi95"));
    assertThat(Json.number(ci.get(0))).isLessThan(0).isGreaterThanOrEqualTo(594.0 / 606 - 1);
    assertThat(Json.number(ci.get(1))).isGreaterThan(0).isLessThanOrEqualTo(606.0 / 594 - 1);
    // the agent comparison: on / off - 1 is +3.00%, +2.97%, +3.03% per round: a narrow interval
    double[] pairs = {618.0 / 600 - 1, 624.0 / 606 - 1, 612.0 / 594 - 1};
    Stats.Interval ci95 = Stats.meanInterval(pairs);
    assertThat(Json.number(a.get("pairRelativeCpuHalfWidth")))
        .isCloseTo((ci95.high() - ci95.low()) / 2, within(1e-15))
        .isLessThan(0.001);

    // deterministic: same seed, same numbers; another seed may differ
    assertThat(Json.write(analyze(42))).isEqualTo(Json.write(a));
    assertThat(NoiseCalibration.aa(OFF, true, 42, 1000))
        .containsExactly(NoiseCalibration.aa(OFF, true, 42, 1000));
    assertThat(a.get("reference")).isEqualTo(false);
  }

  @Test
  @DisplayName("T-BLD-015d fewer than two off runs: no numbers, never a recommendation basis")
  void tooFewRuns() {
    Map<String, Object> a =
        NoiseCalibration.analyze(
            "main-1",
            new double[] {268.7},
            new double[] {312.9},
            new double[0],
            new double[0],
            List.of("no bench-env.sh environment record (18 §7.1)"),
            42,
            1000);
    assertThat(Json.number(a.get("offCpuCv"))).isNaN();
    assertThat(a.get("offCpuCvOk")).isEqualTo(false);
    assertThat(Json.number(Json.array(a.get("aaRelativeCpuCi95")).get(0))).isNaN();
    assertThat(Json.number(a.get("mdeRelativeCpu"))).isNaN();
    assertThat(a.get("reference")).isEqualTo(true);
    assertThat(a).doesNotContainKey("pairRelativeCpuHalfWidth");
  }

  @Test
  @DisplayName(
      "T-BLD-015d the report marks INVALID inputs as reference only and keeps the 2% constant")
  void modelAndMarkdown() {
    Map<String, Object> invalid =
        NoiseCalibration.analyze(
            "smoke", OFF, OFF_P99, ON, ON_P99, List.of("environment: shared machine"), 42, 1000);
    Map<String, Object> model = NoiseCalibration.model(List.of(invalid), 42, 1000);
    assertThat(model.get("referenceOnly")).isEqualTo(true);
    assertThat(Json.number(model.get("cvLimit"))).isEqualTo(LoadReport.CV_LIMIT).isEqualTo(0.02);
    // with 3 rounds at CV 2% the MDE is about 4.6 pp, three times the WARN step
    assertThat(Json.number(model.get("mdeAtCvLimitThreeRounds"))).isCloseTo(0.04575, within(1e-4));
    assertThat(Json.number(model.get("roundsForWarnStepAtCvLimit"))).isEqualTo(28.0);
    String md = NoiseCalibration.markdown(model);
    assertThat(md)
        .startsWith("# Bench noise calibration (18 open item 3)")
        .contains("all numbers are reference values only")
        .contains("| smoke | 3 | 1.00% | yes |")
        .contains("INVALID, reference only: environment: shared machine")
        .contains("## Recommendation")
        .contains("95% CI half-width of the relative CPU");
    Map<String, Object> valid = NoiseCalibration.model(List.of(analyze(42)), 42, 1000);
    assertThat(valid.get("referenceOnly")).isEqualTo(false);
    assertThat(NoiseCalibration.markdown(valid)).doesNotContain("Every input is INVALID");
  }

  @Test
  @DisplayName(
      "T-BLD-015d noise reads the committed WO-02 sets: main-9 off-run CV 4.39% as its result.json"
          + " says, reference only")
  void committedWo02Sets(@TempDir Path dir) throws IOException {
    Path repo =
        Path.of(System.getProperty("argos.repositoryRoot", "../..")).toAbsolutePath().normalize();
    Path sets = repo.resolve("docs/bench/wo02-overhead-2026-10-24");
    Path out = dir.resolve("noise.md");
    int code =
        NoiseCalibration.run(
            Options.parse(
                List.of(
                    "--sets",
                    sets.resolve("main-3") + "," + sets.resolve("main-9"),
                    "--out",
                    out.toString()),
                Set.of()));
    assertThat(code).isZero();
    Object model = FileTools.readJson(dir.resolve("noise.json"));
    Object main9 = Json.array(Json.at(model, "sets")).get(1);
    assertThat(Json.at(main9, "set")).isEqualTo("main-9");
    double cv =
        Json.number(
            Json.at(
                FileTools.readJson(sets.resolve("main-9/result.json")), "validity", "offCpuCv"));
    assertThat(Json.number(main9, "offCpuCv")).isCloseTo(cv, within(1e-12));
    assertThat(String.format(Locale.ROOT, "%.2f", cv * 100)).isEqualTo("4.39");
    assertThat(Json.at(model, "referenceOnly")).isEqualTo(true);
    assertThat(Files.readString(out, StandardCharsets.UTF_8))
        .contains("| main-9 | 3 | 4.39% | no |");
  }
}
