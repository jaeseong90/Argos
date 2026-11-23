/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.realtime;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** The subscription rate limit of a connection (ADR-0010 C-136 (2), 16 §7.13). */
@Tag("req:SEC-56")
class SubscribeBucketTest {

  @Test
  @DisplayName(
      "T-API-007b 20 subscribe frames pass at once, the 21st is refused; one more per second")
  void capacityAndRefill() {
    AtomicLong nanos = new AtomicLong(1_000);
    SubscribeBucket bucket = new SubscribeBucket(nanos::get);
    for (int i = 0; i < SubscribeBucket.CAPACITY; i++) {
      assertThat(bucket.tryTake()).as("token " + (i + 1)).isTrue();
    }
    assertThat(bucket.tryTake()).isFalse();
    nanos.addAndGet(SubscribeBucket.REFILL_NANOS - 1);
    assertThat(bucket.tryTake()).as("just under a second").isFalse();
    nanos.addAndGet(1);
    assertThat(bucket.tryTake()).as("after one second").isTrue();
    assertThat(bucket.tryTake()).isFalse();
    nanos.addAndGet(3 * SubscribeBucket.REFILL_NANOS);
    assertThat(bucket.tryTake()).isTrue();
    assertThat(bucket.tryTake()).isTrue();
    assertThat(bucket.tryTake()).isTrue();
    assertThat(bucket.tryTake()).isFalse();
    nanos.addAndGet(3_600 * SubscribeBucket.REFILL_NANOS);
    int taken = 0;
    while (bucket.tryTake()) {
      taken++;
    }
    assertThat(taken).as("never more than the capacity").isEqualTo(SubscribeBucket.CAPACITY);
  }
}
