/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.realtime;

import io.argos.server.auth.Role;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicLong;
import tools.jackson.databind.JsonNode;

/** Pieces of the WebSocket unit tests: a recording transport and connections without Spring. */
final class WsFixture {

  private WsFixture() {}

  /** A transport that records what is sent and how it closed. */
  static final class RecordingTransport implements WsConnection.Transport {
    final List<String> sent = new CopyOnWriteArrayList<>();
    final List<Integer> closes = new CopyOnWriteArrayList<>();
    final CountDownLatch closed = new CountDownLatch(1);
    volatile boolean open = true;
    volatile CountDownLatch blockSend;
    volatile long closedAtNanos;

    @Override
    public void send(String text) throws IOException {
      CountDownLatch block = blockSend;
      if (block != null) {
        try {
          block.await();
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
          throw new IOException("interrupted", e);
        }
      }
      if (!open) {
        throw new IOException("closed");
      }
      sent.add(text);
    }

    @Override
    public void close(int code, String reason) {
      closes.add(code);
      open = false;
      closedAtNanos = System.nanoTime();
      closed.countDown();
    }

    @Override
    public boolean isOpen() {
      return open;
    }

    /** The sent frames, parsed. */
    List<JsonNode> frames() {
      List<JsonNode> out = new ArrayList<>();
      for (String s : sent) {
        out.add(BusFixture.JSON.readTree(s));
      }
      return out;
    }

    /** The {@code op} of every sent frame. */
    List<String> ops() {
      return frames().stream().map(f -> f.get("op").asString()).toList();
    }
  }

  /** A bucket clock that moves one second per read: never empty. */
  static SubscribeBucket unlimited() {
    AtomicLong nanos = new AtomicLong();
    return new SubscribeBucket(() -> nanos.addAndGet(SubscribeBucket.REFILL_NANOS));
  }

  /**
   * A connection whose drain runs on {@code executor} ({@code Runnable::run}: inline on the
   * offering thread).
   */
  static WsConnection connection(
      RealtimeBus bus, RecordingTransport transport, Executor executor, Role role, long nowMs) {
    return new WsConnection(
        "c-" + UUID.randomUUID(),
        UUID.randomUUID(),
        UUID.randomUUID(),
        role,
        transport,
        executor,
        bus::outbox,
        unlimited(),
        nowMs);
  }
}
