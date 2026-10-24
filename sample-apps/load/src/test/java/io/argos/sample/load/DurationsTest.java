/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.sample.load;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class DurationsTest {

  @ParameterizedTest(name = "{0} = {1} ms")
  @CsvSource({
    "500ms, 500",
    "30s, 30000",
    "1m30s, 90000",
    "2h, 7200000",
    "45, 45000",
    "1h2m3s4ms, 3723004",
    "' 10S ', 10000",
    "0, 0"
  })
  @DisplayName("k6-style durations")
  void parses(String text, long millis) {
    assertThat(Durations.parse(text)).isEqualTo(Duration.ofMillis(millis));
  }

  @ParameterizedTest
  @ValueSource(strings = {"", " ", "ten", "1x", "-1s", "1.5s", "s", "10 s", "1m-1s"})
  @DisplayName("Anything else is rejected")
  void rejects(String text) {
    assertThatThrownBy(() -> Durations.parse(text)).isInstanceOf(IllegalArgumentException.class);
  }

  @ParameterizedTest
  @CsvSource({"1500, 1.5", "60000, 60.0"})
  void seconds(long millis, double seconds) {
    assertThat(Durations.seconds(Duration.ofMillis(millis))).isEqualTo(seconds);
  }
}
