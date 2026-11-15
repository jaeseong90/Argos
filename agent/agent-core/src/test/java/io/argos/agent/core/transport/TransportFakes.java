/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.core.transport;

import com.google.protobuf.Message;
import io.argos.proto.v1.Ack;
import io.argos.proto.v1.AckStatus;
import io.argos.proto.v1.AgentMessage;
import io.argos.proto.v1.DictionaryEntries;
import io.argos.proto.v1.ServerMessage;
import io.grpc.Metadata;
import io.grpc.Status;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/** Fakes of the transport's seams: virtual time, loop, stream, unary transport, session link. */
final class TransportFakes {
  private TransportFakes() {}

  /** A clock that moves only when told to. */
  static final class ManualClock implements Sender.Clock, ClockSync.WallClock {
    long nanos = 1_000_000_000L;
    long millis = 1_700_000_000_000L;

    void advanceMillis(long value) {
      nanos += value * 1_000_000L;
      millis += value;
    }

    @Override
    public long nanoTime() {
      return nanos;
    }

    @Override
    public long currentTimeMillis() {
      return millis;
    }
  }

  /** A single-threaded loop run by the test: nothing runs until {@link #run()}. */
  static final class ManualLoop implements Loop {
    private final ArrayDeque<Runnable> now = new ArrayDeque<Runnable>();
    private final List<Timed> timed = new ArrayList<Timed>();
    long clockMillis;

    /** Tasks handed to {@link #execute} so far. */
    int submitted;

    private static final class Timed implements Task {
      final Runnable task;
      final long at;
      boolean cancelled;

      Timed(Runnable task, long at) {
        this.task = task;
        this.at = at;
      }

      @Override
      public void cancel() {
        cancelled = true;
      }
    }

    @Override
    public void execute(Runnable task) {
      submitted++;
      now.add(task);
    }

    @Override
    public Task schedule(Runnable task, long delayMillis) {
      Timed entry = new Timed(task, clockMillis + delayMillis);
      timed.add(entry);
      return entry;
    }

    @Override
    public boolean inLoop() {
      return true;
    }

    /** Runs what is due now. */
    void run() {
      while (true) {
        Runnable task = now.poll();
        if (task == null) {
          Timed due = nextDue();
          if (due == null) {
            return;
          }
          due.task.run();
        } else {
          task.run();
        }
      }
    }

    private Timed nextDue() {
      Timed best = null;
      for (Iterator<Timed> it = timed.iterator(); it.hasNext(); ) {
        Timed entry = it.next();
        if (entry.cancelled) {
          it.remove();
        } else if (entry.at <= clockMillis && (best == null || entry.at < best.at)) {
          best = entry;
        }
      }
      if (best != null) {
        timed.remove(best);
      }
      return best;
    }

    /** Moves the time on and runs what became due, in order. */
    void advance(long millis) {
      long end = clockMillis + millis;
      while (true) {
        long next = Long.MAX_VALUE;
        for (Timed entry : timed) {
          if (!entry.cancelled && entry.at < next) {
            next = entry.at;
          }
        }
        if (next > end) {
          break;
        }
        clockMillis = Math.max(clockMillis, next);
        run();
      }
      clockMillis = end;
      run();
    }

    /** The delay of the earliest pending timer, or -1. */
    long nextDelay() {
      long next = Long.MAX_VALUE;
      for (Timed entry : timed) {
        if (!entry.cancelled && entry.at < next) {
          next = entry.at;
        }
      }
      return next == Long.MAX_VALUE ? -1 : next - clockMillis;
    }
  }

  /** One fake session stream. */
  static final class FakeStream implements SessionStream {
    final SessionStream.Listener listener;
    final List<AgentMessage> sent = new ArrayList<AgentMessage>();
    boolean ready = true;
    boolean halfClosed;
    String cancelled;
    String security = SessionClient.TLS_1_3;

    FakeStream(SessionStream.Listener listener) {
      this.listener = listener;
    }

    @Override
    public boolean isReady() {
      return ready;
    }

    @Override
    public void send(AgentMessage message) {
      sent.add(message);
    }

    @Override
    public void halfClose() {
      halfClosed = true;
    }

    @Override
    public void cancel(String reason) {
      cancelled = reason;
    }

    @Override
    public String security() {
      return security;
    }

    void server(ServerMessage message) {
      listener.message(message);
    }

    void close(Status status) {
      listener.closed(status, new Metadata());
    }

    void close(Status status, Metadata trailers) {
      listener.closed(status, trailers);
    }

    List<AgentMessage> sentOf(AgentMessage.BodyCase body) {
      List<AgentMessage> out = new ArrayList<AgentMessage>();
      for (AgentMessage message : sent) {
        if (message.getBodyCase() == body) {
          out.add(message);
        }
      }
      return out;
    }
  }

  /** Opens {@link FakeStream}s and keeps them; records {@link #idle()} calls among the opens. */
  static final class FakeOpener implements SessionStream.Opener {
    final List<FakeStream> streams = new ArrayList<FakeStream>();

    /** "open" and "idle" in call order. */
    final List<String> events = new ArrayList<String>();

    int idles;
    RuntimeException failure;

    @Override
    public SessionStream open(SessionStream.Listener listener) {
      events.add("open");
      if (failure != null) {
        throw failure;
      }
      FakeStream stream = new FakeStream(listener);
      streams.add(stream);
      return stream;
    }

    @Override
    public void idle() {
      idles++;
      events.add("idle");
    }

    FakeStream last() {
      return streams.get(streams.size() - 1);
    }
  }

  /** One RPC seen by {@link FakeTransport}. */
  static final class Call {
    final BatchKind kind;
    final Message batch;
    final boolean gzip;
    final long deadlineMillis;
    final BatchTransport.Callback callback;

    Call(
        BatchKind kind,
        Message batch,
        boolean gzip,
        long deadlineMillis,
        BatchTransport.Callback callback) {
      this.kind = kind;
      this.batch = batch;
      this.gzip = gzip;
      this.deadlineMillis = deadlineMillis;
      this.callback = callback;
    }

    void ok() {
      ack(Ack.newBuilder().setStatus(AckStatus.ACK_OK).build());
    }

    void ack(Ack ack) {
      callback.acknowledged(ack);
    }

    void fail(Status status) {
      callback.failed(status, new Metadata());
    }

    void fail(Status status, Metadata trailers) {
      callback.failed(status, trailers);
    }
  }

  /** Records the sends; the test answers them. */
  static final class FakeTransport implements BatchTransport {
    final List<Call> calls = new ArrayList<Call>();
    RuntimeException failure;
    OutOfMemoryError outOfMemory;

    @Override
    public void send(
        BatchKind kind, Message batch, boolean gzip, long deadlineMillis, Callback callback) {
      if (outOfMemory != null) {
        throw outOfMemory;
      }
      if (failure != null) {
        throw failure;
      }
      calls.add(new Call(kind, batch, gzip, deadlineMillis, callback));
    }

    Call last() {
      return calls.get(calls.size() - 1);
    }
  }

  /** A {@link SessionLink} driven by the test. */
  static final class FakeSession implements SessionLink {
    boolean established = true;
    long instanceId = 7;
    String sessionId = "session-1";
    int reestablished;
    int unauthenticated;
    boolean acceptDictionary = true;
    final List<String> failedSessions = new ArrayList<String>();
    final List<DictionaryEntries> dictionary = new ArrayList<DictionaryEntries>();

    @Override
    public boolean established() {
      return established;
    }

    @Override
    public long instanceId() {
      return instanceId;
    }

    @Override
    public String sessionId() {
      return sessionId;
    }

    @Override
    public boolean sendDictionary(List<DictionaryEntries> messages) {
      if (!acceptDictionary) {
        return false;
      }
      dictionary.addAll(messages);
      return true;
    }

    @Override
    public void reestablish(String session) {
      reestablished++;
      failedSessions.add(session);
    }

    @Override
    public void unauthenticated(String session) {
      unauthenticated++;
      failedSessions.add(session);
    }
  }
}
