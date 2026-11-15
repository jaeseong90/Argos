/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.core.log;

import java.io.IOException;
import java.util.TimeZone;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.LockSupport;

/**
 * Asynchronous log pipeline behind {@link AgentLogger} (10 §14): application threads only enqueue
 * (lock-free, bounded, never waiting); the {@code argos-logger} thread formats and writes.
 *
 * <p>Bounds of the queued records (N-02, ADR-0010 C-120 (3)): at most {@value #CAPACITY} records
 * and at most {@value #MAX_QUEUED_BYTES} estimated bytes of queued text in total, counted as
 * characters times 2 (message and thread name) plus {@value #RECORD_OVERHEAD_BYTES} bytes per
 * record ({@value #ERROR_OVERHEAD_BYTES} more when the record carries a throwable, whose stack is
 * formatted on the logger thread). A record beyond either bound is dropped and counted ({@link
 * #dropped()}, the self metric {@code argos_agent_log_dropped_total}); the enqueuing thread never
 * waits. Without the byte bound the worst case was 4,096 records of 8,192 characters, about 64 MB.
 */
final class LogPipeline {
  static final int CAPACITY = 4096;
  static final int MAX_MESSAGE_CHARS = 8192;

  /** Total estimated bytes of the queued records (ADR-0010 C-120 (3): 1 MiB). */
  static final long MAX_QUEUED_BYTES = 1024L * 1024;

  /** Estimated fixed bytes of one queued record: the record, two String headers, references. */
  static final long RECORD_OVERHEAD_BYTES = 128L;

  /** Extra estimate for a record that keeps a throwable until the logger thread formats it. */
  static final long ERROR_OVERHEAD_BYTES = 512L;

  private static final int MAX_ONCE_KEYS = 1024;
  private static final int MAX_STACK_FRAMES = 40;
  private static final long IDLE_PARK_NANOS = TimeUnit.MILLISECONDS.toNanos(50);

  private final ConcurrentLinkedQueue<LogRecord> queue = new ConcurrentLinkedQueue<LogRecord>();
  private final AtomicInteger queued = new AtomicInteger();
  private final AtomicLong queuedBytes = new AtomicLong();
  private final AtomicLong dropped = new AtomicLong();
  private final ConcurrentHashMap<String, Boolean> onceKeys =
      new ConcurrentHashMap<String, Boolean>();

  /**
   * Created by the first {@link #format} (the {@code argos-logger} thread, inside {@link #drain}):
   * the default time zone is not read on the premain thread (C-79).
   */
  private Timestamps timestamps;

  private volatile LogLevel level = LogLevel.INFO;
  private volatile LogSink sink;
  private volatile Thread thread;
  private volatile boolean running;
  private volatile boolean flushRequested;
  private volatile boolean silenced;
  private long reportedDrops;

  LogLevel level() {
    return level;
  }

  void setLevel(LogLevel value) {
    level = value;
  }

  /** Installs the destination; records queued before are written to it. */
  void setSink(LogSink value) {
    sink = value;
  }

  LogSink sink() {
    return sink;
  }

  long dropped() {
    return dropped.get();
  }

  /** Nothing is queued any more (the start failed without a log file, C-78). */
  void silence() {
    silenced = true;
  }

  boolean silenced() {
    return silenced;
  }

  /**
   * Enqueues an event unless the record count or the byte bound is reached (then counted as
   * dropped). Never blocks.
   */
  void enqueue(LogLevel recordLevel, String message, Throwable error) {
    if (silenced) {
      return;
    }
    if (queued.incrementAndGet() > CAPACITY) {
      queued.decrementAndGet();
      dropped.incrementAndGet();
      return;
    }
    String text =
        message.length() > MAX_MESSAGE_CHARS
            ? message.substring(0, MAX_MESSAGE_CHARS) + "...(truncated)"
            : message;
    String threadName = Thread.currentThread().getName();
    long bytes = estimatedBytes(text, threadName, error);
    if (!reserveBytes(bytes)) {
      queued.decrementAndGet();
      dropped.incrementAndGet();
      return;
    }
    queue.offer(
        new LogRecord(System.currentTimeMillis(), recordLevel, threadName, text, error, bytes));
  }

  /** The byte estimate of a queued record (see the class comment). */
  static long estimatedBytes(String text, String threadName, Throwable error) {
    long chars = (long) text.length() + (threadName == null ? 0 : threadName.length());
    return RECORD_OVERHEAD_BYTES + 2L * chars + (error != null ? ERROR_OVERHEAD_BYTES : 0L);
  }

  /** Adds {@code bytes} to the queued total unless that passes the bound; one CAS, no wait. */
  private boolean reserveBytes(long bytes) {
    while (true) {
      long used = queuedBytes.get();
      if (used + bytes > MAX_QUEUED_BYTES) {
        return false;
      }
      if (queuedBytes.compareAndSet(used, used + bytes)) {
        return true;
      }
    }
  }

  /** Estimated bytes of the records queued now. */
  long queuedBytes() {
    return queuedBytes.get();
  }

  /** Records queued now. */
  int queued() {
    return queued.get();
  }

  /** True the first time {@code key} is seen (bounded: later keys are always "seen"). */
  boolean firstTime(String key) {
    if (onceKeys.size() >= MAX_ONCE_KEYS) {
      return false;
    }
    return onceKeys.putIfAbsent(key, Boolean.TRUE) == null;
  }

  /** Writes every queued record to the sink; returns the number written. Logger thread only. */
  synchronized int drain() {
    LogSink target = sink;
    if (target == null) {
      return 0;
    }
    int written = 0;
    LogRecord record;
    while ((record = queue.poll()) != null) {
      queued.decrementAndGet();
      queuedBytes.addAndGet(-record.bytes);
      write(target, record);
      written++;
    }
    long drops = dropped.get();
    if (drops != reportedDrops) {
      long newDrops = drops - reportedDrops;
      reportedDrops = drops;
      write(
          target,
          new LogRecord(
              System.currentTimeMillis(),
              LogLevel.WARN,
              "argos-logger",
              newDrops
                  + " log record(s) dropped: logger queue full ("
                  + CAPACITY
                  + " records or "
                  + MAX_QUEUED_BYTES
                  + " bytes)",
              null));
    }
    boolean requested = flushRequested;
    if (written > 0 || requested) {
      flushRequested = false;
      try {
        target.flush();
      } catch (IOException e) {
        switchToFallback(e);
      }
    }
    return written;
  }

  /** The next {@link #drain()} flushes the sink even when nothing was queued (held lines). */
  void requestFlush() {
    flushRequested = true;
    Thread worker = thread;
    if (worker != null) {
      LockSupport.unpark(worker);
    }
  }

  /** Drops every queued record without writing it (start failure without a log file, C-78). */
  synchronized void discardQueued() {
    LogRecord record;
    while ((record = queue.poll()) != null) {
      queued.decrementAndGet();
      queuedBytes.addAndGet(-record.bytes);
    }
  }

  private void write(LogSink target, LogRecord record) {
    String line = format(record);
    try {
      target.write(record, line);
    } catch (IOException e) {
      switchToFallback(e);
      try {
        sink.write(record, line);
      } catch (IOException ignored) {
        // the fallback writes to stderr and does not throw
      }
    }
  }

  private void switchToFallback(IOException cause) {
    LogSink current = sink;
    if (current instanceof StderrFallbackSink) {
      return;
    }
    if (current != null) {
      current.close();
    }
    sink = new StderrFallbackSink(System.err, "log file write failed: " + cause);
  }

  String format(LogRecord record) {
    Timestamps clock = timestamps;
    if (clock == null) {
      clock = new Timestamps(TimeZone.getDefault());
      timestamps = clock;
    }
    StringBuilder line = new StringBuilder(96 + record.message.length());
    clock.append(line, record.epochMillis);
    line.append(' ').append(record.level.name());
    for (int i = record.level.name().length(); i < 5; i++) {
      line.append(' ');
    }
    line.append(" [").append(record.thread).append("] ").append(record.message);
    if (record.error != null) {
      // 11 §11, SEC-06, C-94: a throwable's message and its causes' messages are not the agent's
      // own text (a library may put a header or a URL in them): masked like library messages,
      // here on the logger thread, which pays the cost.
      line.append('\n').append(LogMask.mask(stackTrace(record.error)));
    }
    return line.toString();
  }

  /** The stack trace of {@code error}, at most {@value #MAX_STACK_FRAMES} frames per cause. */
  @SuppressWarnings("ReferenceEquality") // self-referencing cause
  static String stackTrace(Throwable error) {
    // '\n' on every platform: one log format whatever line.separator is.
    StringBuilder out = new StringBuilder(1024);
    Throwable current = error;
    for (int depth = 0; current != null && depth < 5; depth++) {
      if (depth > 0) {
        out.append("\nCaused by: ");
      }
      out.append(current);
      StackTraceElement[] frames = current.getStackTrace();
      int shown = Math.min(frames.length, MAX_STACK_FRAMES);
      for (int i = 0; i < shown; i++) {
        out.append("\n\tat ").append(frames[i]);
      }
      if (frames.length > shown) {
        out.append("\n\t... ").append(frames.length - shown).append(" more");
      }
      current = current.getCause() == current ? null : current.getCause();
    }
    return out.toString();
  }

  /** Starts the {@code argos-logger} thread (once). */
  synchronized void start(ThreadFactory factory) {
    if (thread != null) {
      return;
    }
    running = true;
    Thread worker =
        factory.newThread(
            new Runnable() {
              @Override
              public void run() {
                loop();
              }
            });
    thread = worker;
    worker.start();
  }

  private void loop() {
    while (running) {
      try {
        if (drain() == 0) {
          LockSupport.parkNanos(IDLE_PARK_NANOS);
        }
      } catch (Throwable t) {
        // A logging failure must not kill the logger thread.
        LockSupport.parkNanos(IDLE_PARK_NANOS);
      }
    }
  }

  /** Stops the thread, then writes what is left and closes the sink. */
  void stop(long timeoutMillis) {
    running = false;
    Thread worker = thread;
    if (worker != null) {
      LockSupport.unpark(worker);
      try {
        worker.join(timeoutMillis);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    }
    drain();
    LogSink target = sink;
    if (target != null) {
      target.close();
    }
  }
}
