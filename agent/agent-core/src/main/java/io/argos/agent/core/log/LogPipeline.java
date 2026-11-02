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
 */
final class LogPipeline {
  static final int CAPACITY = 4096;
  static final int MAX_MESSAGE_CHARS = 8192;
  private static final int MAX_ONCE_KEYS = 1024;
  private static final int MAX_STACK_FRAMES = 40;
  private static final long IDLE_PARK_NANOS = TimeUnit.MILLISECONDS.toNanos(50);

  private final ConcurrentLinkedQueue<LogRecord> queue = new ConcurrentLinkedQueue<LogRecord>();
  private final AtomicInteger queued = new AtomicInteger();
  private final AtomicLong dropped = new AtomicLong();
  private final ConcurrentHashMap<String, Boolean> onceKeys =
      new ConcurrentHashMap<String, Boolean>();
  private final Timestamps timestamps = new Timestamps(TimeZone.getDefault());

  private volatile LogLevel level = LogLevel.INFO;
  private volatile LogSink sink;
  private volatile Thread thread;
  private volatile boolean running;
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

  /** Enqueues an event unless the queue is full (then counted as dropped). Never blocks. */
  void enqueue(LogLevel recordLevel, String message, Throwable error) {
    if (queued.incrementAndGet() > CAPACITY) {
      queued.decrementAndGet();
      dropped.incrementAndGet();
      return;
    }
    String text =
        message.length() > MAX_MESSAGE_CHARS
            ? message.substring(0, MAX_MESSAGE_CHARS) + "...(truncated)"
            : message;
    queue.offer(
        new LogRecord(
            System.currentTimeMillis(),
            recordLevel,
            Thread.currentThread().getName(),
            text,
            error));
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
              newDrops + " log record(s) dropped: logger queue full (" + CAPACITY + ")",
              null));
    }
    if (written > 0) {
      try {
        target.flush();
      } catch (IOException e) {
        switchToFallback(e);
      }
    }
    return written;
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
    StringBuilder line = new StringBuilder(96 + record.message.length());
    timestamps.append(line, record.epochMillis);
    line.append(' ').append(record.level.name());
    for (int i = record.level.name().length(); i < 5; i++) {
      line.append(' ');
    }
    line.append(" [").append(record.thread).append("] ").append(record.message);
    if (record.error != null) {
      line.append('\n').append(stackTrace(record.error));
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
