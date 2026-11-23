/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.core.stats;

import static org.assertj.core.api.Assertions.assertThat;

import io.argos.agent.bootstrap.DictKind;
import io.argos.agent.core.trace.Dictionary;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** Connection waits per pool (13 §3.5, ADR-0007 C-68; WO-16). */
@Tag("req:F-A03")
class PoolWaitCountersTest {
  @Test
  @DisplayName(
      "T-AG-062 pool waits are cumulative {count, nanos} per pool name; id 0 is ignored, at most 64"
          + " pools")
  void perPool() {
    Dictionary dictionary = new Dictionary();
    PoolWaitCounters waits = new PoolWaitCounters(dictionary);
    assertThat(waits.poolWaits()).isEmpty();
    long hikari = dictionary.id(DictKind.POOL, "HikariPool-1");
    long tomcat = dictionary.id(DictKind.POOL, "tomcat-pool");
    waits.record(hikari, 1_000_000L);
    waits.record(hikari, 2_000_000L);
    waits.record(tomcat, 5L);
    waits.record(0, 9L);
    waits.record(tomcat, -3L); // a negative time counts as 0
    Map<String, long[]> values = waits.poolWaits();
    assertThat(values).containsOnlyKeys("HikariPool-1", "tomcat-pool");
    assertThat(values.get("HikariPool-1")).containsExactly(2L, 3_000_000L);
    assertThat(values.get("tomcat-pool")).containsExactly(2L, 5L);

    for (int i = 0; i < 70; i++) {
      waits.record(dictionary.id(DictKind.POOL, "pool-" + i), 1L);
    }
    assertThat(waits.poolWaits()).hasSize(PoolWaitCounters.MAX_POOLS);
    assertThat(waits.overflow()).isEqualTo(70 - (PoolWaitCounters.MAX_POOLS - 2));
  }
}
