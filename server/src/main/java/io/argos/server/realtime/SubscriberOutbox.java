/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.realtime;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.LongConsumer;

/**
 * The send queue of one WebSocket connection with the backpressure of 14 §5.3 (normative over the
 * "256 frames" of 12 §3.7): frames wait here until the connection's sender takes them with {@link
 * #poll()}; when the queued frames exceed the budget ({@value #BUDGET_BYTES} bytes), the oldest
 * {@code xlog} pushes are dropped first. Their points are announced by a {@code notice
 * backpressure} frame (sent before anything else) and by the {@code dropped} field of the
 * subscription's next push. {@code counter}, {@code active} and {@code overview} keep only the
 * newest pending push per subscription ({@link #offerLatest}); control frames ({@code ack}, {@code
 * error}, {@code pong}, {@code reauth}) are never dropped.
 *
 * <p>Thread safety: offers come from the bus threads and the connection's handler, {@link #poll()}
 * from its sender; each call holds the outbox lock briefly and never waits for anything else. The
 * wake-up callback runs after an offer, outside the lock.
 */
public final class SubscriberOutbox {

  /** The send budget of a connection (14 §5.3: 1 MB). */
  public static final long BUDGET_BYTES = 1L << 20;

  /** The {@code xlog} subscription behind queued pushes: told about drops, asked for the count. */
  public interface XlogOwner {
    /**
     * A queued push of this subscription was dropped: {@code points} points, and the services whose
     * dictionary names it carried (to send again with a later push).
     */
    void dropped(int points, Set<Long> unsentServices);

    /** The points dropped since the previous push, reset to 0 (the push's {@code dropped}). */
    long takeDropped();
  }

  private sealed interface Entry permits Control, Latest, Xlog {
    long bytes();
  }

  private record Control(String json, long bytes) implements Entry {}

  private static final class Latest implements Entry {
    final String id;
    String json;
    long bytes;

    Latest(String id, String json) {
      this.id = id;
      this.json = json;
      this.bytes = utf8Length(json);
    }

    @Override
    public long bytes() {
      return bytes;
    }
  }

  private record Xlog(
      XlogOwner owner,
      String id,
      long tMs,
      String dataJson,
      String dictJson,
      int points,
      Set<Long> newServices,
      long bytes)
      implements Entry {}

  private final long budget;
  private final Runnable wakeUp;
  private final LongConsumer droppedPoints;
  private final ArrayDeque<Entry> queue = new ArrayDeque<>();
  private final Map<String, Latest> latest = new HashMap<>();
  private long bytes;
  private long noticePoints;
  private long droppedTotal;
  private boolean closed;

  /** An outbox with the 14 §5.3 budget. */
  public SubscriberOutbox(Runnable wakeUp, LongConsumer droppedPoints) {
    this(BUDGET_BYTES, wakeUp, droppedPoints);
  }

  /**
   * @param budget bytes of queued frames before {@code xlog} pushes are dropped
   * @param wakeUp called after each accepted offer (outside the lock): the sender should {@link
   *     #poll()}
   * @param droppedPoints told the points of each dropped push (metrics)
   */
  public SubscriberOutbox(long budget, Runnable wakeUp, LongConsumer droppedPoints) {
    this.budget = budget;
    this.wakeUp = Objects.requireNonNull(wakeUp, "wakeUp");
    this.droppedPoints = Objects.requireNonNull(droppedPoints, "droppedPoints");
  }

  /** Queues a control frame ({@code ack}, {@code error}, {@code pong}, {@code reauth}). */
  public void offerControl(String json) {
    synchronized (this) {
      if (closed) {
        return;
      }
      Control c = new Control(json, utf8Length(json));
      queue.addLast(c);
      bytes += c.bytes();
      trim();
    }
    wakeUp.run();
  }

  /**
   * Queues the push of a latest-only topic for subscription {@code id}, replacing a push of the
   * same subscription that is still waiting (at its place in the queue).
   */
  public void offerLatest(String id, String json) {
    synchronized (this) {
      if (closed) {
        return;
      }
      Latest waiting = latest.get(id);
      if (waiting != null) {
        bytes -= waiting.bytes;
        waiting.json = json;
        waiting.bytes = utf8Length(json);
        bytes += waiting.bytes;
      } else {
        Latest l = new Latest(id, json);
        latest.put(id, l);
        queue.addLast(l);
        bytes += l.bytes;
      }
      trim();
    }
    wakeUp.run();
  }

  /**
   * Queues an {@code xlog} push; its envelope (with {@code dropped}) is written when it is sent.
   *
   * @param newServices the services whose names {@code dictJson} carries for the first time
   */
  public void offerXlog(
      XlogOwner owner,
      String id,
      long tMs,
      String dataJson,
      String dictJson,
      int points,
      Set<Long> newServices) {
    synchronized (this) {
      if (closed) {
        owner.dropped(points, newServices);
        return;
      }
      long size =
          utf8Length(dataJson)
              + (dictJson == null ? 0 : utf8Length(dictJson))
              + utf8Length(id)
              + 96;
      Xlog x = new Xlog(owner, id, tMs, dataJson, dictJson, points, Set.copyOf(newServices), size);
      queue.addLast(x);
      bytes += size;
      trim();
    }
    wakeUp.run();
  }

  /** Forgets the waiting latest-only push of a subscription (after unsubscribe). */
  public void forget(String id) {
    synchronized (this) {
      Latest l = latest.remove(id);
      if (l != null) {
        queue.remove(l);
        bytes -= l.bytes;
      }
    }
  }

  /** The next frame to send, null when nothing waits. */
  public String poll() {
    synchronized (this) {
      if (noticePoints > 0) {
        long n = noticePoints;
        noticePoints = 0;
        return ServerFrames.backpressure(Topic.XLOG, n);
      }
      Entry e = queue.pollFirst();
      if (e == null) {
        return null;
      }
      bytes -= e.bytes();
      return switch (e) {
        case Control c -> c.json();
        case Latest l -> {
          latest.remove(l.id);
          yield l.json;
        }
        case Xlog x ->
            ServerFrames.push(
                x.id(), Topic.XLOG, x.tMs(), x.dataJson(), x.dictJson(), x.owner().takeDropped());
      };
    }
  }

  /** Bytes of the queued frames. */
  public synchronized long queuedBytes() {
    return bytes;
  }

  /** Frames queued. */
  public synchronized int size() {
    return queue.size() + (noticePoints > 0 ? 1 : 0);
  }

  /** Points of {@code xlog} pushes dropped so far by this outbox. */
  public synchronized long droppedPoints() {
    return droppedTotal;
  }

  /** Drops everything and accepts nothing more (the connection closed). */
  public void close() {
    synchronized (this) {
      closed = true;
      queue.clear();
      latest.clear();
      bytes = 0;
      noticePoints = 0;
    }
  }

  /** Drops the oldest {@code xlog} pushes while over budget. Holds the lock. */
  private void trim() {
    if (bytes <= budget) {
      return;
    }
    Iterator<Entry> it = queue.iterator();
    while (bytes > budget && it.hasNext()) {
      if (it.next() instanceof Xlog x) {
        it.remove();
        bytes -= x.bytes();
        noticePoints += x.points();
        droppedTotal += x.points();
        x.owner().dropped(x.points(), x.newServices());
        droppedPoints.accept(x.points());
      }
    }
  }

  /** The UTF-8 length of a string without encoding it. */
  static long utf8Length(String s) {
    long n = 0;
    for (int i = 0; i < s.length(); i++) {
      char c = s.charAt(i);
      if (c < 0x80) {
        n++;
      } else if (c < 0x800) {
        n += 2;
      } else if (Character.isHighSurrogate(c)) {
        n += 4;
        i++;
      } else {
        n += 3;
      }
    }
    return n;
  }
}
