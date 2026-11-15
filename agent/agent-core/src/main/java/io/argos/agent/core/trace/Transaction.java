/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.core.trace;

import io.argos.agent.bootstrap.CaptureConfig;
import io.argos.agent.bootstrap.TraceContext;
import io.argos.agent.core.transport.QueueItem;

/**
 * Core side of a transaction (10 §9.1), reachable from {@link TraceContext#coreRef} while it runs,
 * and the item of the transaction queue once it ended (10 §9.2 step 2, the XLog point of 11 {@code
 * TransactionRecord}). Keeps strings, primitives, bootstrap and core types only (T-AG-004).
 *
 * <p>Threads: the request fields ({@link #request}) are written while the transaction runs; the end
 * fields are written once by the thread that ends it ({@code TracerImpl.end}) before the item is
 * handed to a queue, and read after that hand-over (the queue orders the two).
 *
 * <p>Missing is not zero (C-4, D-23): {@link #cpuNanos()} and {@link #allocatedBytes()} are -1 when
 * not measured (platform without support, virtual thread, transaction ended on another thread);
 * exporters then leave the field out.
 *
 * <p>Memory: the strings are cut (method 16, path 2,048, remote IP 64, user id 128, error class
 * 1,024 characters, at most 10 headers of 128 + 1,024 characters, request id 128; 10 §14, 11 §4.4);
 * {@link #estimatedBytes()} is fixed at the end and includes the step buffer only while the
 * transaction still holds it ({@link #steps()}).
 */
public final class Transaction implements QueueItem {
  /** Upper bounds of captured strings (10 §14, 11 §4.4). */
  static final int PATH_MAX_CHARS = 2048;

  static final int VALUE_MAX_CHARS = 1024;
  static final int METHOD_MAX_CHARS = 16;
  static final int IP_MAX_CHARS = 64;

  /** Estimated bytes of the transaction and its context without strings and steps. */
  static final long BASE_BYTES = 512L;

  private static final String[] NO_HEADERS = new String[0];

  private final TraceContext context;
  private final String threadName;
  private final String threadNameNormalized;
  private final long threadNameId;
  private volatile StepBuffer steps;

  private volatile String httpMethod;
  private volatile String path;
  private volatile String remoteIp;
  private volatile String userId;
  private volatile boolean userIdDecided;
  private volatile String[] headers = NO_HEADERS;
  private volatile String requestId;

  private String errorClass;
  private String errorMessage;
  private long endNanos = -1L;
  private long endEpochMs;
  private long cpuNanos = -1L;
  private long allocatedBytes = -1L;
  private boolean profileCaptured;
  private int samplerReason;
  private long totalSteps;
  private boolean truncated;
  private long estimatedBytes = -1L;
  private long createdNanos;

  /**
   * @param threadName raw name of the thread that began the transaction (cut at 256 characters)
   * @param threadNameNormalized its {@code ThreadNames} form (after the 1,000-name cap)
   * @param threadNameId dictionary id of the normalized name ({@code thread_name_id})
   */
  Transaction(
      TraceContext context,
      int maxSteps,
      String threadName,
      String threadNameNormalized,
      long threadNameId) {
    this.context = context;
    this.steps = new StepBuffer(context.startNanos, maxSteps);
    this.threadName = threadName;
    this.threadNameNormalized = threadNameNormalized;
    this.threadNameId = threadNameId;
  }

  /** Method, recorded URL (path and query by mode) and client address after the IP mode. */
  void request(String method, String requestPath, String ip) {
    httpMethod = truncate(method, METHOD_MAX_CHARS);
    path = truncate(requestPath, PATH_MAX_CHARS);
    remoteIp = truncate(ip, IP_MAX_CHARS);
  }

  /** True once {@code capture.user-id} decided what this transaction records ({@link UserIds}). */
  boolean userIdDecided() {
    return userIdDecided;
  }

  /** The user id decision of {@link UserIds}: {@code value} to send, or null to send none. */
  void decideUserId(String value) {
    userId = truncate(value, CaptureConfig.USER_ID_MAX_LENGTH);
    userIdDecided = true;
  }

  /** Header pairs already cut by {@link HeaderCapture#headers}. */
  void headers(String[] pairs) {
    headers = pairs == null ? NO_HEADERS : pairs;
  }

  /** The request id already cut by {@link HeaderCapture#requestId}. */
  void requestId(String value) {
    requestId = value;
  }

  /** Called once by the ending thread, after the step buffer was sealed. */
  void finish(
      String error,
      String message,
      long nanos,
      long epochMs,
      long cpu,
      long allocated,
      long createdAtNanos) {
    errorClass = truncate(error, VALUE_MAX_CHARS);
    errorMessage = message;
    endNanos = nanos;
    endEpochMs = epochMs;
    cpuNanos = cpu < 0 ? -1L : cpu;
    allocatedBytes = allocated < 0 ? -1L : allocated;
    StepBuffer buffer = steps;
    if (buffer != null) {
      totalSteps = buffer.totalSteps();
      truncated = buffer.summarized();
    }
    createdNanos = createdAtNanos;
  }

  /** Records the sampler decision ({@code Sampler.NONE} ... ) of 10 §9.2 step 3. */
  void sampled(int reason) {
    samplerReason = reason;
    profileCaptured = reason != Sampler.NONE;
  }

  /**
   * Drops the step buffer (the profile, if captured, holds it) and fixes {@link #estimatedBytes()}:
   * a transaction record in the queue then costs its own fields only.
   */
  void detachSteps() {
    steps = null;
  }

  /** Fixes {@link #estimatedBytes()}; called last before the item is offered. */
  void fixEstimate() {
    estimatedBytes = computeBytes();
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

  /**
   * The steps; null once the transaction ended without keeping them (the steps then travel with the
   * profile, if the sampler captured it).
   */
  public StepBuffer steps() {
    return steps;
  }

  /** Raw name of the thread that began the transaction (cut at 256 characters). */
  public String threadName() {
    return threadName;
  }

  /** {@code ThreadNames} form of {@link #threadName()} ({@code _other_} beyond the cap). */
  public String threadNameNormalized() {
    return threadNameNormalized;
  }

  /** Dictionary id of {@link #threadNameNormalized()} ({@code thread_name_id}), 0 if unknown. */
  public long threadNameId() {
    return threadNameId;
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

  /**
   * Whitelisted request headers as name, value pairs ({@code [name0, value0, name1, ...]}, at most
   * 10 pairs, names lower case); empty when none was captured. Callers must not modify it.
   */
  public String[] headers() {
    return headers;
  }

  /** The {@code capture.request-id-header} value (C-124), or null. */
  public String requestId() {
    return requestId;
  }

  public String errorClass() {
    return errorClass;
  }

  public String errorMessage() {
    return errorMessage;
  }

  /** {@code System.nanoTime()} at the end, -1 while running. */
  public long endNanos() {
    return endNanos;
  }

  public long endEpochMs() {
    return endEpochMs;
  }

  /** Elapsed nanoseconds (D-01: nanoTime difference only), -1 while running. */
  public long elapsedNanos() {
    return endNanos < 0 ? -1L : Math.max(0L, endNanos - context.startNanos);
  }

  /** CPU nanoseconds of the transaction's thread, -1 when not measured (missing, D-23). */
  public long cpuNanos() {
    return cpuNanos;
  }

  /** Bytes allocated by the transaction's thread, -1 when not measured (missing, D-23). */
  public long allocatedBytes() {
    return allocatedBytes;
  }

  /** {@code xlog.flags} bits of ADR-0007 C-69 ({@link TraceContext#flags()}). */
  public int flags() {
    return context.flags();
  }

  /**
   * True when the sampler chose to send the profile (10 §9.2 step 3). Stays true when the profile
   * queue then dropped it ({@code profile_dropped_total}, D-07 "LOST").
   */
  public boolean profileCaptured() {
    return profileCaptured;
  }

  /** {@code Sampler.NONE}, {@code ERROR}, {@code SLOW} or {@code RANDOM}. */
  public int samplerReason() {
    return samplerReason;
  }

  /** Steps that started in this transaction (11 {@code total_steps}). */
  public long totalSteps() {
    return totalSteps;
  }

  /** True when steps beyond {@code max-steps} were summarized (11 {@code truncated}). */
  public boolean truncated() {
    return truncated;
  }

  @Override
  public long createdNanos() {
    return createdNanos;
  }

  /**
   * Rough heap size in bytes (transaction queue byte bound, 10 §10.1, N-02): fixed at the end so
   * that the queue adds and subtracts the same value.
   */
  @Override
  public long estimatedBytes() {
    long fixed = estimatedBytes;
    return fixed >= 0 ? fixed : computeBytes();
  }

  private long computeBytes() {
    long bytes = BASE_BYTES;
    bytes += chars(context.serviceName) + chars(threadNameNormalized);
    bytes += chars(threadName) + chars(httpMethod) + chars(path) + chars(remoteIp);
    bytes += chars(userId) + chars(errorClass) + chars(errorMessage) + chars(requestId);
    String[] pairs = headers;
    for (String value : pairs) {
      bytes += chars(value);
    }
    StepBuffer buffer = steps;
    if (buffer != null) {
      bytes += buffer.estimatedBytes();
    }
    return bytes;
  }

  private static long chars(String value) {
    return value == null ? 0L : 40L + 2L * value.length();
  }
}
