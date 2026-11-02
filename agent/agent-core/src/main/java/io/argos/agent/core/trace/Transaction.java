/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.core.trace;

import io.argos.agent.bootstrap.TraceContext;

/**
 * Core side of a transaction (10 §9.1), reachable from {@link TraceContext#coreRef}. Written by the
 * application threads of the transaction, read by an exporter on the sender thread after {@link
 * #finish}. Keeps strings, primitives, bootstrap and core types only (T-AG-004).
 */
public final class Transaction {
  public static final int FLAG_STREAMING = 1;
  public static final int FLAG_TIMEOUT = 1 << 1;
  public static final int FLAG_LEAKED = 1 << 2;
  public static final int FLAG_PROFILE_CAPTURED = 1 << 3;
  public static final int FLAG_SUMMARIZED = 1 << 4;

  /** Upper bounds of captured strings (10 §14). */
  static final int PATH_MAX_CHARS = 2048;

  static final int VALUE_MAX_CHARS = 1024;

  private final TraceContext context;
  private final StepBuffer steps;
  private final String threadName;

  private volatile String httpMethod;
  private volatile String path;
  private volatile String remoteIp;
  private volatile String userId;

  private String errorClass;
  private String errorMessage;
  private long endNanos = -1;
  private long endEpochMs;
  private int flags;
  private long finishedBytes = -1;

  Transaction(TraceContext context, int maxSteps, String threadName) {
    this.context = context;
    this.steps = new StepBuffer(context.startNanos, maxSteps);
    this.threadName = threadName;
  }

  void http(String method, String requestPath, String ip, String user) {
    httpMethod = truncate(method, 16);
    path = truncate(requestPath, PATH_MAX_CHARS);
    remoteIp = truncate(ip, 64);
    userId = truncate(user, VALUE_MAX_CHARS);
  }

  synchronized void finish(String error, String message, long nanos, long epochMs) {
    errorClass = truncate(error, VALUE_MAX_CHARS);
    errorMessage = message;
    endNanos = nanos;
    endEpochMs = epochMs;
    if (steps.summarized()) {
      flags |= FLAG_SUMMARIZED;
    }
    finishedBytes = computeBytes();
  }

  /** Adds a flag ({@link #FLAG_STREAMING}, ...). */
  public synchronized void addFlag(int flag) {
    flags |= flag;
  }

  static String truncate(String value, int max) {
    if (value == null || value.length() <= max) {
      return value;
    }
    return value.substring(0, max);
  }

  public TraceContext context() {
    return context;
  }

  public StepBuffer steps() {
    return steps;
  }

  /** Name of the thread that began the transaction (normalisation arrives with WO-10/15). */
  public String threadName() {
    return threadName;
  }

  public String httpMethod() {
    return httpMethod;
  }

  public String path() {
    return path;
  }

  public String remoteIp() {
    return remoteIp;
  }

  public String userId() {
    return userId;
  }

  public synchronized String errorClass() {
    return errorClass;
  }

  public synchronized String errorMessage() {
    return errorMessage;
  }

  /** {@code System.nanoTime()} at the end, -1 while running. */
  public synchronized long endNanos() {
    return endNanos;
  }

  public synchronized long endEpochMs() {
    return endEpochMs;
  }

  /** Elapsed nanoseconds (D-01: nanoTime difference), -1 while running. */
  public synchronized long elapsedNanos() {
    return endNanos < 0 ? -1 : Math.max(0, endNanos - context.startNanos);
  }

  public synchronized int flags() {
    return flags;
  }

  /**
   * Rough heap size in bytes (export queue byte bound, 10 §10.1); fixed when the transaction ends
   * so that the queue adds and subtracts the same value.
   */
  public synchronized long estimatedBytes() {
    return finishedBytes >= 0 ? finishedBytes : computeBytes();
  }

  private long computeBytes() {
    long bytes = 400L + steps.estimatedBytes();
    String p = path;
    if (p != null) {
      bytes += 2L * p.length();
    }
    if (errorMessage != null) {
      bytes += 2L * errorMessage.length();
    }
    return bytes;
  }
}
