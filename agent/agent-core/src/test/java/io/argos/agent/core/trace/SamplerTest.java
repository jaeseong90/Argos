/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.core.trace;

import static org.assertj.core.api.Assertions.assertThat;

import io.argos.agent.core.config.AgentConfig;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** Sampler (10 §9.2 step 3, DSN-10-08): error, slow per service, then normal rate. */
@Tag("req:DSN-10-08")
class SamplerTest {
  private static final long MS = TimeUnit.MILLISECONDS.toNanos(1);

  private static Sampler.RandomSource fixed(final double value) {
    return new Sampler.RandomSource() {
      @Override
      public double nextDouble() {
        return value;
      }
    };
  }

  @Test
  @DisplayName("error first, then elapsed >= slow threshold of the service, then random < rate")
  void decisions() {
    Map<String, Integer> services = new HashMap<String, Integer>();
    services.put("GET /fast-limit", 50);
    Sampler sampler = new Sampler(1000, services, 0.1, fixed(0.5));
    assertThat(sampler.decide(true, 0, "GET /x")).isEqualTo(Sampler.ERROR);
    assertThat(sampler.decide(false, 1000 * MS, "GET /x")).isEqualTo(Sampler.SLOW);
    assertThat(sampler.decide(false, 999 * MS, "GET /x")).isEqualTo(Sampler.NONE);
    assertThat(sampler.decide(false, 50 * MS, "GET /fast-limit")).isEqualTo(Sampler.SLOW);
    assertThat(sampler.decide(false, 49 * MS, "GET /fast-limit")).isEqualTo(Sampler.NONE);
    assertThat(sampler.decide(false, 999 * MS, null)).isEqualTo(Sampler.NONE);
    assertThat(sampler.slowThresholdNanos("GET /fast-limit")).isEqualTo(50 * MS);
    assertThat(sampler.slowThresholdNanos("unknown")).isEqualTo(1000 * MS);

    Sampler lucky = new Sampler(1000, services, 0.1, fixed(0.0999));
    assertThat(lucky.decide(false, 1, "GET /x")).isEqualTo(Sampler.RANDOM);
    Sampler never = new Sampler(1000, null, 0.0, fixed(0.0));
    assertThat(never.decide(false, 1, "GET /x")).isEqualTo(Sampler.NONE);
    Sampler always = new Sampler(1000, null, 1.0, fixed(0.9999));
    assertThat(always.decide(false, 1, "GET /x")).isEqualTo(Sampler.RANDOM);
    assertThat(new Sampler(1000, null, Double.NaN).normalRate()).isZero();
    assertThat(new Sampler(1000, null, 7).normalRate()).isEqualTo(1.0);
  }

  @Test
  @DisplayName("defaults: 1,000 ms and 0.1; the configuration's per-service map is copied, capped")
  void configuration() {
    Sampler defaults = Sampler.defaults();
    assertThat(defaults.slowThresholdNanos("any")).isEqualTo(1000 * MS);
    assertThat(defaults.normalRate()).isEqualTo(0.1);
    Sampler configured = Sampler.of(AgentConfig.of(Collections.<String, String>emptyMap()));
    assertThat(configured.slowThresholdNanos("any")).isEqualTo(1000 * MS);
    assertThat(configured.normalRate()).isEqualTo(0.1);
    Map<String, Integer> many = new HashMap<String, Integer>();
    for (int i = 0; i < Sampler.MAX_SERVICES + 50; i++) {
      many.put("GET /" + i, 5);
    }
    Sampler capped = new Sampler(1000, many, 0.1);
    int own = 0;
    for (int i = 0; i < Sampler.MAX_SERVICES + 50; i++) {
      if (capped.slowThresholdNanos("GET /" + i) == 5 * MS) {
        own++;
      }
    }
    assertThat(own).isEqualTo(Sampler.MAX_SERVICES);
    // Statistically: about 10 % of normal transactions with the thread-local random source.
    int captured = 0;
    for (int i = 0; i < 20_000; i++) {
      if (defaults.decide(false, 1, "GET /n") == Sampler.RANDOM) {
        captured++;
      }
    }
    assertThat(captured).isBetween(1_600, 2_400);
  }
}
