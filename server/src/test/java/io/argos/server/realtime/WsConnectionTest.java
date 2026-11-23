/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.realtime;

import static io.argos.server.realtime.BusFixture.T0;
import static org.assertj.core.api.Assertions.assertThat;

import io.argos.server.auth.Role;
import io.argos.server.realtime.BusFixture.Sources;
import io.argos.server.realtime.BusFixture.TestClock;
import io.argos.server.realtime.WsFixture.RecordingTransport;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** One connection: the 4 KiB message limit, the single sender and the close protocol. */
@Tag("req:SEC-44")
class WsConnectionTest {

  private final TestClock clock = new TestClock(T0);
  private final RealtimeBus bus =
      BusFixture.bus(new Sources().app(3, "prod", 1_000), clock, new SimpleMeterRegistry());
  private final RecordingTransport transport = new RecordingTransport();
  private final List<String> handled = new ArrayList<>();

  private WsConnection connection() {
    return WsFixture.connection(bus, transport, Runnable::run, Role.VIEWER, clock.millis());
  }

  @Test
  @DisplayName(
      "T-API-007c a message of 4,096 UTF-8 bytes is handled, 4,097 bytes close 4413 (also when it"
          + " arrives in parts, and counting multi-byte characters as bytes)")
  void messageLimit() {
    WsConnection ok = connection();
    ok.onText("a".repeat(4_096), true, clock.millis(), (c, m) -> handled.add(m));
    assertThat(handled).hasSize(1);
    assertThat(transport.closes).isEmpty();

    ok.onText("a".repeat(2_000), false, clock.millis(), (c, m) -> handled.add(m));
    ok.onText("b".repeat(2_096), true, clock.millis(), (c, m) -> handled.add(m));
    assertThat(handled).hasSize(2);
    assertThat(handled.get(1)).hasSize(4_096).startsWith("aaa").endsWith("bbb");

    RecordingTransport second = new RecordingTransport();
    WsConnection big =
        WsFixture.connection(bus, second, Runnable::run, Role.VIEWER, clock.millis());
    big.onText("a".repeat(3_000), false, clock.millis(), (c, m) -> handled.add(m));
    big.onText("b".repeat(1_097), true, clock.millis(), (c, m) -> handled.add(m));
    assertThat(handled).hasSize(2);
    assertThat(second.closes).containsExactly(WsConnection.CLOSE_TOO_BIG);

    RecordingTransport third = new RecordingTransport();
    WsConnection multiByte =
        WsFixture.connection(bus, third, Runnable::run, Role.VIEWER, clock.millis());
    // 1,366 three-byte characters: 4,098 bytes in 1,366 chars.
    multiByte.onText("가".repeat(1_366), true, clock.millis(), (c, m) -> handled.add(m));
    assertThat(third.closes).containsExactly(WsConnection.CLOSE_TOO_BIG);
    assertThat(handled).hasSize(2);
  }

  @Test
  @DisplayName(
      "T-SEC-44d a close request with reauth: subscriptions end, queued frames are dropped,"
          + " reauth is the last frame, then close 4401; nothing is sent afterwards")
  void reauthClose() {
    List<Runnable> scheduled = new ArrayList<>();
    WsConnection c =
        WsFixture.connection(bus, transport, scheduled::add, Role.VIEWER, clock.millis());
    c.sendControl(ServerFrames.pong(1, clock.instant()));
    c.sendControl(ServerFrames.pong(2, clock.instant()));
    assertThat(scheduled).hasSize(1);
    c.requestClose(WsConnection.CLOSE_UNAUTHENTICATED, "Session ended", true);
    c.requestClose(WsConnection.CLOSE_TIMEOUT, "Timeout", false);
    c.sendControl(ServerFrames.pong(3, clock.instant()));
    scheduled.get(0).run();
    assertThat(transport.ops()).containsExactly("reauth");
    assertThat(transport.closes).containsExactly(WsConnection.CLOSE_UNAUTHENTICATED);
    c.sendControl(ServerFrames.pong(4, clock.instant()));
    c.drain();
    assertThat(transport.ops()).containsExactly("reauth");
    assertThat(transport.closes).hasSize(1);
  }

  @Test
  @DisplayName(
      "T-SEC-44d frames are sent by one drain task at a time, in order, from many offering"
          + " threads; a close waits for the frame being sent")
  void singleSender() throws Exception {
    ExecutorService pool = Executors.newFixedThreadPool(8);
    try {
      WsConnection c = WsFixture.connection(bus, transport, pool, Role.VIEWER, clock.millis());
      CountDownLatch go = new CountDownLatch(1);
      List<Thread> threads = new ArrayList<>();
      for (int t = 0; t < 4; t++) {
        int base = t * 1_000;
        Thread th =
            Thread.ofPlatform()
                .start(
                    () -> {
                      try {
                        go.await();
                      } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                      }
                      for (int i = 0; i < 500; i++) {
                        c.sendControl(ServerFrames.pong(base + i, clock.instant()));
                      }
                    });
        threads.add(th);
      }
      go.countDown();
      for (Thread th : threads) {
        th.join();
      }
      long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
      while (transport.sent.size() < 2_000 && System.nanoTime() < deadline) {
        Thread.sleep(5);
      }
      assertThat(transport.sent).hasSize(2_000);
      for (int t = 0; t < 4; t++) {
        long last = -1;
        for (var f : transport.frames()) {
          long v = f.get("t").asLong();
          if (v / 1_000 == t) {
            assertThat(v).as("order of thread " + t).isGreaterThan(last);
            last = v;
          }
        }
      }
      // A close while a send blocks: the close comes after that frame.
      transport.blockSend = new CountDownLatch(1);
      c.sendControl(ServerFrames.pong(9_999, clock.instant()));
      Thread.sleep(50);
      c.requestClose(WsConnection.CLOSE_RESTART, "Server restart", false);
      Thread.sleep(50);
      assertThat(transport.closes).isEmpty();
      transport.blockSend.countDown();
      assertThat(transport.closed.await(5, TimeUnit.SECONDS)).isTrue();
      assertThat(transport.closes).containsExactly(WsConnection.CLOSE_RESTART);
      assertThat(transport.sent.get(transport.sent.size() - 1)).contains("9999");
    } finally {
      pool.shutdownNow();
    }
  }
}
