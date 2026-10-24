/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.sample.load;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class LoadConfigTest {

  @Test
  @DisplayName(
      "Defaults: the demo compose settings of 17 §3.2 (50/s, MIX default), run until stopped")
  void defaults() {
    LoadConfig c = LoadConfig.from(Map.of());
    assertThat(c.target()).isEqualTo(URI.create("http://localhost:8080"));
    assertThat(c.rate()).isEqualTo(50.0);
    assertThat(c.mix().name()).isEqualTo("default");
    assertThat(c.duration()).isNull();
    assertThat(c.warmup()).isZero();
    assertThat(c.maxInFlight()).isEqualTo(64);
    assertThat(c.requestTimeout()).isEqualTo(Duration.ofSeconds(10));
    assertThat(c.connectTimeout()).isEqualTo(Duration.ofSeconds(3));
    assertThat(c.reportInterval()).isEqualTo(Duration.ofSeconds(10));
    assertThat(c.summaryPath()).isNull();
    assertThat(c.httpThreads()).isEqualTo(4);
    assertThat(c.maxUnexpectedRate()).isNaN();
  }

  @Test
  @DisplayName("Environment variables (TARGET_URL, RATE, MIX, ...) and options; the option wins")
  void environmentAndOptions() {
    Map<String, String> env =
        Map.of(
            "TARGET_URL", "http://sample-app-a:8080/shop-a",
            "RATE", "1000",
            "MIX", "bench",
            "DURATION", "1m",
            "SUMMARY_PATH", "out/summary.json",
            "MAX_UNEXPECTED_RATE", "0.01",
            "WARMUP", "30s");
    LoadConfig fromEnv = LoadConfig.from(env);
    assertThat(fromEnv.target().toString()).isEqualTo("http://sample-app-a:8080/shop-a");
    assertThat(fromEnv.rate()).isEqualTo(1000.0);
    assertThat(fromEnv.mix().name()).isEqualTo("bench");
    assertThat(fromEnv.duration()).isEqualTo(Duration.ofMinutes(1));
    assertThat(fromEnv.warmup()).isEqualTo(Duration.ofSeconds(30));
    assertThat(fromEnv.maxInFlight()).isEqualTo(1000);
    assertThat(fromEnv.summaryPath()).isEqualTo(Path.of("out/summary.json"));
    assertThat(fromEnv.maxUnexpectedRate()).isEqualTo(0.01);

    LoadConfig withOptions =
        LoadConfig.from(
            env, "--rate", "20", "--mix", "default", "--duration", "0", "--max-in-flight", "5");
    assertThat(withOptions.rate()).isEqualTo(20.0);
    assertThat(withOptions.mix().name()).isEqualTo("default");
    assertThat(withOptions.duration()).as("0 = until stopped").isNull();
    assertThat(withOptions.maxInFlight()).isEqualTo(5);
  }

  @Test
  @DisplayName("Blank variables count as unset")
  void blankVariables() {
    LoadConfig c = LoadConfig.from(Map.of("RATE", " ", "MIX", ""));
    assertThat(c.rate()).isEqualTo(50.0);
    assertThat(c.mix().name()).isEqualTo("default");
  }

  @Test
  @DisplayName("Default in-flight limit: max(64, rate), at most 4096")
  void inFlightDefault() {
    assertThat(LoadConfig.from(Map.of("RATE", "10")).maxInFlight()).isEqualTo(64);
    assertThat(LoadConfig.from(Map.of("RATE", "2500")).maxInFlight()).isEqualTo(2500);
    assertThat(LoadConfig.from(Map.of("RATE", "15000")).maxInFlight()).isEqualTo(4096);
  }

  @ParameterizedTest(name = "{0}={1} -> {2}")
  @CsvSource(
      delimiter = '|',
      value = {
        "RATE|0|RATE must be > 0",
        "RATE|-5|RATE must be > 0",
        "RATE|20001|RATE must be > 0 and <= 20000",
        "RATE|fast|RATE is not a number",
        "TARGET_URL|ftp://host/|TARGET_URL must be http(s)",
        "TARGET_URL|localhost:8080|TARGET_URL must be http(s)",
        "TARGET_URL|http://host:8080/?a=1|must not have a query",
        "TARGET_URL|http://bad host/|TARGET_URL is not a URL",
        "MIX|everything|unknown MIX",
        "DURATION|ten|bad duration",
        "WARMUP|-1s|bad duration",
        "MAX_IN_FLIGHT|0|MAX_IN_FLIGHT must be between",
        "REQUEST_TIMEOUT|0s|REQUEST_TIMEOUT must be positive",
        "HTTP_THREADS|65|HTTP_THREADS must be between",
        "MAX_UNEXPECTED_RATE|1.5|MAX_UNEXPECTED_RATE must be between"
      })
  void rejectsBadValues(String name, String value, String message) {
    assertThatThrownBy(() -> LoadConfig.from(Map.of(name, value)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining(message)
        .hasMessageContaining("usage:");
  }

  @Test
  @DisplayName("Unknown options and options without a value are rejected with the usage")
  void rejectsBadOptions() {
    assertThatThrownBy(() -> LoadConfig.from(Map.of(), "--speed", "3"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("unknown option --speed")
        .hasMessageContaining("usage:");
    assertThatThrownBy(() -> LoadConfig.from(Map.of(), "--rate"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("--rate needs a value");
  }
}
