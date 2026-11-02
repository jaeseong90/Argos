/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("req:D-24")
class CallDepthTest {

  @Test
  @DisplayName("CallDepth counts nesting per key: 1 is the outermost call, exit never goes below 0")
  void nestingPerKey() {
    assertThat(CallDepth.enter(CallDepth.JDBC_STATEMENT)).isEqualTo(1);
    assertThat(CallDepth.enter(CallDepth.JDBC_STATEMENT)).isEqualTo(2);
    assertThat(CallDepth.enter(CallDepth.HTTP_CLIENT)).isEqualTo(1);
    assertThat(CallDepth.exit(CallDepth.JDBC_STATEMENT)).isEqualTo(1);
    assertThat(CallDepth.exit(CallDepth.JDBC_STATEMENT)).isEqualTo(0);
    assertThat(CallDepth.exit(CallDepth.JDBC_STATEMENT)).isEqualTo(0);
    assertThat(CallDepth.depth(CallDepth.HTTP_CLIENT)).isEqualTo(1);
    assertThat(CallDepth.exit(CallDepth.HTTP_CLIENT)).isEqualTo(0);
  }

  @Test
  @DisplayName(
      "CallDepth ignores keys outside 0..7 instead of throwing (advice code must not fail)")
  void invalidKeys() {
    assertThat(CallDepth.enter(-1)).isEqualTo(0);
    assertThat(CallDepth.enter(CallDepth.KEYS)).isEqualTo(0);
    assertThat(CallDepth.exit(99)).isEqualTo(0);
    assertThat(CallDepth.depth(-5)).isEqualTo(0);
  }

  @Test
  @DisplayName("CallDepth is per thread")
  void perThread() throws InterruptedException {
    CallDepth.enter(CallDepth.SERVLET);
    final AtomicInteger other = new AtomicInteger(-1);
    Thread thread =
        new Thread(
            new Runnable() {
              @Override
              public void run() {
                other.set(CallDepth.enter(CallDepth.SERVLET));
              }
            });
    thread.start();
    thread.join();
    assertThat(other.get()).isEqualTo(1);
    assertThat(CallDepth.exit(CallDepth.SERVLET)).isEqualTo(0);
  }
}
