/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.core.transport;

import com.google.protobuf.CodedOutputStream;
import com.google.protobuf.Message;
import io.argos.agent.core.guard.AgentCounters;
import io.argos.agent.core.log.AgentLogger;
import io.argos.agent.core.trace.ProfileRecord;
import io.argos.agent.core.trace.Transaction;
import io.argos.proto.ProtocolLimits;
import io.argos.proto.v1.Ack;
import io.argos.proto.v1.AckStatus;
import io.argos.proto.v1.ActiveTransactionSnapshot;
import io.argos.proto.v1.BatchHeader;
import io.argos.proto.v1.DictionaryEntries;
import io.argos.proto.v1.MetricBatch;
import io.argos.proto.v1.ProfileBatch;
import io.argos.proto.v1.StatBatch;
import io.argos.proto.v1.TransactionBatch;
import io.argos.proto.v1.TransactionProfile;
import io.argos.proto.v1.TransactionRecord;
import io.grpc.Metadata;
import io.grpc.Status;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.LockSupport;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * The batch sender of 10 §10.2 and 11 §3, §6, §10 (DSN-02-11), run by {@code argos-sender} (and,
 * for the final flush, by the shutdown hook). Single-threaded state: RPC outcomes arrive on {@code
 * argos-io} and are only queued here ({@link #completions}); the owner thread handles them.
 *
 * <p><b>Batches</b>: per kind, every {@code sender.flush-interval-ms} (100) or as soon as {@code
 * sender.batch-size} (500) records wait, transaction records are drained into a {@code
 * TransactionBatch} (at most the batch size and {@code Limits.max_transactions_per_batch});
 * profiles into a {@code ProfileBatch} closed at {@value #PROFILE_FLUSH_STEPS} steps (and at {@code
 * Limits.max_steps_per_batch}); metric, statistics and active items are one {@link PreparedBatch}
 * each (WO-16 builds them). Every batch stays at or under 1 MiB serialized ({@link
 * BatchAssembler#targetBatchBytes()}, 11 §2): the record or profile that would pass it starts the
 * next batch; a single profile above it is rebuilt with shorter details, then with fewer steps
 * ({@code truncated}). Batches above {@value ProtocolLimits#GZIP_MIN_BYTES} bytes are sent gzip
 * compressed when {@code sender.compression} is {@code gzip} (11 §2).
 *
 * <p><b>Ordering and idempotence</b>: each kind has its own {@code seq} counter starting at 1 (one
 * {@link AtomicLong} per kind, 11 §6, §16), never reset for the process; {@code agent_epoch}
 * (random per process) goes in every {@code BatchHeader}. A resent batch keeps its {@code seq}.
 *
 * <p><b>In flight</b>: at most {@code sender.max-in-flight} (4) batches of all kinds together hold
 * a slot (sending, or waiting for a retry or a session; 11 §6 "in-flight 합계 ≤ 4", T-AG-071);
 * without a free slot nothing is drained, so batches stay in their queues, where the bounds of 10
 * §10.1 drop the oldest and count them (D-06). A split batch (below) passes its slot to its first
 * half; its second half waits without a slot in a list that the split rule bounds (only a batch
 * with a slot is split, once, so it never holds more than {@code sender.max-in-flight} halves) and
 * takes the next free slot before any new batch is drained. Application threads are never involved
 * (N-05).
 *
 * <p><b>Outcomes</b> (11 §6, §10):
 *
 * <ul>
 *   <li>{@code OK}: delivered ({@code ACK_OK}, {@code ACK_DUPLICATE}); {@code Ack.dropped_items}
 *       (quota {@code ACK_PARTIAL_DROP}, full WAL) count as server drops and are never resent;
 *       {@code Ack.throttle.send_backoff_ms} pauses the next sends and {@code
 *       Ack.throttle.profile_normal_rate} lowers the profile sampling rate (never above the
 *       configured rate, 11 §4.2) through the {@link ProfileRate} callback;
 *   <li>{@code UNAVAILABLE}, {@code DEADLINE_EXCEEDED} and other transient codes: the same batch
 *       and {@code seq} again after 200 ms, 1 s, 3 s; after the third retry it is dropped and
 *       counted;
 *   <li>{@code FAILED_PRECONDITION} with {@code SESSION_REQUIRED}: a new session, then the same
 *       batch with the same {@code seq} (counted as a retry); other {@code FAILED_PRECONDITION}:
 *       dropped;
 *   <li>{@code RESOURCE_EXHAUSTED}: with the trailer {@code argos-retry-after-ms} (quota, rate) a
 *       retry after that time; without it (the batch is too large) one split into two halves with
 *       new {@code seq}s, then a drop;
 *   <li>{@code INVALID_ARGUMENT}: dropped, counted, one WARN per minute at most;
 *   <li>{@code PERMISSION_DENIED}: dropped (no retry), one ERROR, a new session;
 *   <li>{@code UNAUTHENTICATED}: dropped; the session waits the fixed 5 minutes (ADR-0010 C-102).
 * </ul>
 *
 * Dropped batches count their items in {@code argos_agent_dropped_total{queue}} (so the heartbeat's
 * {@code dropped_total} includes them) and the batch in {@code
 * argos_agent_batches_dropped_total{kind}}.
 *
 * <p><b>Session</b>: nothing is drained or resent while no session is established (11 §3); a
 * batch's dictionary entries are written on the session stream before its RPC starts (11 §7).
 */
final class Sender {
  /** Retry delays of 11 §6 and §16 (ADR-0010 C-105): 200 ms, 1 s, 3 s. */
  static final long[] RETRY_DELAYS_MILLIS = {200, 1_000, 3_000};

  /** Retries of one batch (11 §6). */
  static final int MAX_RETRIES = RETRY_DELAYS_MILLIS.length;

  /** A profile batch is closed at this many steps (11 §3 "100ms 또는 2,000 step"). */
  static final int PROFILE_FLUSH_STEPS = 2_000;

  /** Room left in a batch for its header and framing. */
  static final int HEADER_RESERVE_BYTES = 64;

  /** Detail cap of the second build of an oversized single profile. */
  static final int OVERSIZED_PROFILE_DETAIL_BYTES = 256;

  /** {@code INVALID_ARGUMENT} WARN at most once per minute (11 §6). */
  static final long INVALID_ARGUMENT_WARN_NANOS = TimeUnit.MINUTES.toNanos(1);

  /** The trailer of a quota or rate rejection (11 §10). */
  static final Metadata.Key<String> RETRY_AFTER_MS =
      Metadata.Key.of("argos-retry-after-ms", Metadata.ASCII_STRING_MARSHALLER);

  /** The trailer naming a rejection reason (11 §10). */
  static final Metadata.Key<String> REJECT_REASON =
      Metadata.Key.of("argos-reject-reason", Metadata.ASCII_STRING_MARSHALLER);

  /**
   * The detail of {@code FAILED_PRECONDITION} when the server does not know the session (11 §3).
   */
  static final String SESSION_REQUIRED = "SESSION_REQUIRED";

  /**
   * Applies {@code Throttle.profile_normal_rate} (11 §4.2, §6) to the profile sampler. Called on
   * the sender's owner thread; the implementation publishes the new sampler through a volatile
   * field ({@code TracerImpl.setSampler}), so the application threads see it without a lock.
   */
  interface ProfileRate {
    void apply(float rate);
  }

  /** No sampler to lower (tests that do not look at it). */
  static final ProfileRate IGNORE_PROFILE_RATE =
      new ProfileRate() {
        @Override
        public void apply(float rate) {}
      };

  /** Time source (tests inject one). */
  interface Clock {
    long nanoTime();

    long currentTimeMillis();
  }

  static final Clock SYSTEM_CLOCK =
      new Clock() {
        @Override
        public long nanoTime() {
          return System.nanoTime();
        }

        @Override
        public long currentTimeMillis() {
          return System.currentTimeMillis();
        }
      };

  /** One batch outside the queues. */
  static final class Batch {
    final BatchKind kind;
    final Message body;
    final int items;
    final long seq;
    int attempts;
    long dueNanos;
    boolean split;
    String sessionId = "";

    Batch(BatchKind kind, Message body, int items, long seq) {
      this.kind = kind;
      this.body = body;
      this.items = items;
      this.seq = seq;
    }
  }

  /** The outcome of one RPC, queued for the owner thread. */
  static final class Completion {
    final Batch batch;
    final Ack ack;
    final Status status;
    final Metadata trailers;

    Completion(Batch batch, Ack ack, Status status, Metadata trailers) {
      this.batch = batch;
      this.ack = ack;
      this.status = status;
      this.trailers = trailers;
    }
  }

  private static final BatchKind[] KINDS = BatchKind.values();

  private final Queues queues;
  private final BatchAssembler assembler;
  private final DictionaryCache dictionary;
  private final SessionLink session;
  private final BatchTransport transport;
  private final AgentCounters counters;
  private final TransportCounters transportCounters;
  private final TransportSettings settings;
  private final long agentEpoch;
  private final Clock clock;
  private final Runnable onFatal;
  private final ProfileRate profileRate;

  private final AtomicLong[] seqs = new AtomicLong[KINDS.length];
  private final ConcurrentLinkedQueue<Completion> completions =
      new ConcurrentLinkedQueue<Completion>();
  private final List<Batch> waiting = new ArrayList<Batch>();

  /**
   * Second halves of split batches, waiting for a free slot (they hold none). Bounded by the split
   * rule: only a batch that holds a slot is split, and only once, so this list never holds more
   * than {@code sender.max-in-flight} batches.
   */
  private final List<Batch> unslotted = new ArrayList<Batch>();

  private final long[] lastFlushNanos = new long[KINDS.length];
  private final Poll poll = new Poll();
  private final AtomicLong deliveredTransactions = new AtomicLong();

  private volatile Thread owner;

  /** True while {@link #run} is on the stack of {@code argos-sender} (shutdown hook guard). */
  private volatile boolean runActive;

  private volatile boolean disabled;
  private volatile long requestedPauseMillis;
  private int inFlight;
  private int maxObservedInFlight;
  private int nextKind;
  private long pausedUntilNanos;
  private boolean paused;
  private long lastInvalidWarnNanos;
  private boolean invalidWarned;
  private boolean permissionLogged;
  private TransactionRecord carriedRecord;
  private ProfileRecord carriedProfile;
  private boolean profileBatchFull;

  /**
   * @param onFatal called once when the transport must stop (direct memory exhausted, 10 §10.3)
   */
  Sender(
      Queues queues,
      BatchAssembler assembler,
      DictionaryCache dictionary,
      SessionLink session,
      BatchTransport transport,
      AgentCounters counters,
      TransportCounters transportCounters,
      TransportSettings settings,
      long agentEpoch,
      Clock clock,
      Runnable onFatal) {
    this(
        queues,
        assembler,
        dictionary,
        session,
        transport,
        counters,
        transportCounters,
        settings,
        agentEpoch,
        clock,
        onFatal,
        IGNORE_PROFILE_RATE);
  }

  /**
   * @param onFatal called once when the transport must stop (direct memory exhausted, 10 §10.3)
   * @param profileRate receives {@code Ack.throttle.profile_normal_rate} (11 §4.2, §6)
   */
  Sender(
      Queues queues,
      BatchAssembler assembler,
      DictionaryCache dictionary,
      SessionLink session,
      BatchTransport transport,
      AgentCounters counters,
      TransportCounters transportCounters,
      TransportSettings settings,
      long agentEpoch,
      Clock clock,
      Runnable onFatal,
      ProfileRate profileRate) {
    this.profileRate = profileRate;
    this.queues = queues;
    this.assembler = assembler;
    this.dictionary = dictionary;
    this.session = session;
    this.transport = transport;
    this.counters = counters;
    this.transportCounters = transportCounters;
    this.settings = settings;
    this.agentEpoch = agentEpoch;
    this.clock = clock;
    this.onFatal = onFatal;
    long now = clock.nanoTime();
    for (int i = 0; i < seqs.length; i++) {
      seqs[i] = new AtomicLong();
      // The first batch of a kind also waits one flush interval (or a full batch).
      lastFlushNanos[i] = now;
    }
  }

  // --- threads -----------------------------------------------------------------------------------

  /** The {@code argos-sender} loop; returns when {@code running} turns false. */
  void run(BooleanSupplier running) {
    runActive = true;
    try {
      owner = Thread.currentThread();
      while (running.getAsBoolean()) {
        long parkNanos;
        try {
          parkNanos = step(false, Long.MAX_VALUE);
        } catch (Throwable t) {
          AgentLogger.warn("sender round failed: {}", t.getClass().getName());
          parkNanos = flushNanos();
        }
        if (completions.isEmpty() && running.getAsBoolean()) {
          LockSupport.parkNanos(this, Math.max(1L, parkNanos));
        }
      }
    } finally {
      runActive = false;
    }
  }

  /** True while {@link #run} has not returned (its thread still owns the sender state). */
  boolean runActive() {
    return runActive;
  }

  /** Wakes the owner thread (a completion arrived, the session changed, stop requested). */
  void wake() {
    Thread thread = owner;
    if (thread != null) {
      LockSupport.unpark(thread);
    }
  }

  /**
   * The final flush of the shutdown hook (10 §10.2, 11 §6 "종료"): the calling thread takes over and
   * sends what the queues hold for at most {@code millis}, with RPC deadlines inside that time,
   * then returns (no blocking call: it waits for outcomes with short parks). What is left is lost
   * with the process.
   *
   * <p>Precondition: the previous owner has left {@link #run} (the state of this class, the {@link
   * DictionaryCache} and the single-consumer queue drains belong to one thread at a time). {@code
   * ExportWorker} calls this only after {@code argos-sender} ended; when {@link #run} is still
   * active this method returns at once without touching the state.
   */
  void flush(long millis) {
    if (runActive) {
      return;
    }
    owner = Thread.currentThread();
    long deadline = clock.nanoTime() + TimeUnit.MILLISECONDS.toNanos(Math.max(0, millis));
    while (true) {
      long now = clock.nanoTime();
      if (now >= deadline || disabled) {
        return;
      }
      step(true, deadline);
      if (!session.established()) {
        // Nothing can be sent without a session: the application's exit does not wait for one.
        return;
      }
      if (inFlight == 0 && waiting.isEmpty() && unslotted.isEmpty() && nothingQueued()) {
        return;
      }
      if (completions.isEmpty()) {
        LockSupport.parkNanos(this, Math.min(TimeUnit.MILLISECONDS.toNanos(10), deadline - now));
      }
    }
  }

  // --- one round ---------------------------------------------------------------------------------

  /**
   * One round on the owner thread: outcomes, due resends, new batches. Returns how long to park.
   * {@code drainAll} sends whatever is queued (shutdown); {@code deadlineNanos} bounds RPC
   * deadlines.
   */
  long step(boolean drainAll, long deadlineNanos) {
    processCompletions();
    long flush = flushNanos();
    if (disabled) {
      discardQueues();
      return flush;
    }
    if (!session.established()) {
      return flush;
    }
    long now = clock.nanoTime();
    long requested = requestedPauseMillis;
    if (requested > 0) {
      requestedPauseMillis = 0;
      pause(now, requested);
    }
    // 11 §6: a Throttle pauses every send, resends too; the shutdown flush has its own short limit.
    if (!drainAll && paused && now < pausedUntilNanos) {
      return Math.min(flush, pausedUntilNanos - now);
    }
    paused = false;
    long nextDue = resendDue(now, deadlineNanos);
    if (disabled) {
      return flush;
    }
    // Second halves of split batches take free slots before any new batch is drained.
    while (!unslotted.isEmpty() && inFlight < settings.maxInFlight) {
      Batch half = unslotted.remove(0);
      inFlight++;
      maxObservedInFlight = Math.max(maxObservedInFlight, inFlight);
      dispatch(half, deadlineNanos);
      if (disabled) {
        return flush;
      }
    }
    for (int k = 0; k < KINDS.length; k++) {
      BatchKind kind = KINDS[(nextKind + k) % KINDS.length];
      while (inFlight < settings.maxInFlight && (drainAll || due(kind, now))) {
        Batch batch = build(kind);
        if (batch == null) {
          break;
        }
        lastFlushNanos[kind.index()] = now;
        inFlight++;
        maxObservedInFlight = Math.max(maxObservedInFlight, inFlight);
        dispatch(batch, deadlineNanos);
        if (disabled) {
          return flush;
        }
      }
    }
    nextKind = (nextKind + 1) % KINDS.length;
    return nextDue > 0 ? Math.min(flush, Math.max(1L, nextDue - now)) : flush;
  }

  /** Resends the waiting batches that are due; returns the earliest later due time, or 0. */
  private long resendDue(long now, long deadlineNanos) {
    long next = 0;
    Iterator<Batch> it = waiting.iterator();
    List<Batch> due = null;
    while (it.hasNext()) {
      Batch batch = it.next();
      if (batch.dueNanos <= now) {
        it.remove();
        if (due == null) {
          due = new ArrayList<Batch>(waiting.size() + 1);
        }
        due.add(batch);
      } else if (next == 0 || batch.dueNanos < next) {
        next = batch.dueNanos;
      }
    }
    if (due != null) {
      for (Batch batch : due) {
        dispatch(batch, deadlineNanos);
      }
    }
    return next;
  }

  private boolean due(BatchKind kind, long now) {
    if (kind == BatchKind.TRANSACTIONS && carriedRecord != null) {
      return true;
    }
    if (kind == BatchKind.PROFILES && carriedProfile != null) {
      return true;
    }
    AgentQueue<QueueItem> queue = queue(kind);
    int size = queue.size();
    if (size == 0) {
      return false;
    }
    if (kind != BatchKind.TRANSACTIONS && kind != BatchKind.PROFILES) {
      return true;
    }
    if (kind == BatchKind.PROFILES && profileBatchFull) {
      // 11 §3 "100ms 또는 2,000 step": the last batch closed at its step bound, more wait.
      return true;
    }
    return size >= settings.batchSize || now - lastFlushNanos[kind.index()] >= flushNanos();
  }

  private boolean nothingQueued() {
    if (carriedRecord != null || carriedProfile != null) {
      return false;
    }
    for (BatchKind kind : KINDS) {
      if (queue(kind).size() > 0) {
        return false;
      }
    }
    return true;
  }

  // --- building ----------------------------------------------------------------------------------

  /** The next batch of {@code kind} from its queue, or null when there is nothing to send. */
  Batch build(BatchKind kind) {
    switch (kind) {
      case TRANSACTIONS:
        return buildTransactions();
      case PROFILES:
        return buildProfiles();
      default:
        return buildPrepared(kind);
    }
  }

  private Batch buildTransactions() {
    int maxRecords = Math.min(settings.batchSize, assembler.maxRecordsPerBatch());
    int target = assembler.targetBatchBytes() - HEADER_RESERVE_BYTES;
    TransactionBatch.Builder builder = TransactionBatch.newBuilder();
    int size = 0;
    while (builder.getRecordsCount() < maxRecords) {
      TransactionRecord record = carriedRecord;
      carriedRecord = null;
      if (record == null) {
        QueueItem item = pollOne(queues.transaction());
        if (item == null) {
          break;
        }
        if (!(item instanceof Transaction)) {
          counters.incDropped(Queues.TRANSACTION);
          continue;
        }
        record = assembler.record((Transaction) item);
      }
      int recordSize = CodedOutputStream.computeMessageSize(2, record);
      if (builder.getRecordsCount() > 0 && size + recordSize > target) {
        carriedRecord = record;
        break;
      }
      builder.addRecords(record);
      size += recordSize;
    }
    if (builder.getRecordsCount() == 0) {
      return null;
    }
    return new Batch(
        BatchKind.TRANSACTIONS,
        builder.build(),
        builder.getRecordsCount(),
        nextSeq(BatchKind.TRANSACTIONS));
  }

  private Batch buildProfiles() {
    int maxProfiles = Math.max(1, settings.batchSize);
    int maxSteps = assembler.maxStepsPerBatch();
    int target = assembler.targetBatchBytes() - HEADER_RESERVE_BYTES;
    ProfileBatch.Builder builder = ProfileBatch.newBuilder();
    int size = 0;
    int steps = 0;
    while (builder.getProfilesCount() < maxProfiles && steps < PROFILE_FLUSH_STEPS) {
      ProfileRecord record = carriedProfile;
      carriedProfile = null;
      if (record == null) {
        QueueItem item = pollOne(queues.profile());
        if (item == null) {
          break;
        }
        if (!(item instanceof ProfileRecord)) {
          counters.incDropped(Queues.PROFILE);
          continue;
        }
        record = (ProfileRecord) item;
      }
      TransactionProfile profile = fit(assembler.profile(record), target);
      int profileSize = CodedOutputStream.computeMessageSize(2, profile);
      int profileSteps = profile.getStepsCount();
      if (builder.getProfilesCount() > 0
          && (size + profileSize > target || steps + profileSteps > maxSteps)) {
        carriedProfile = record;
        break;
      }
      builder.addProfiles(profile);
      size += profileSize;
      steps += profileSteps;
    }
    profileBatchFull = steps >= PROFILE_FLUSH_STEPS;
    if (builder.getProfilesCount() == 0) {
      return null;
    }
    return new Batch(
        BatchKind.PROFILES,
        builder.build(),
        builder.getProfilesCount(),
        nextSeq(BatchKind.PROFILES));
  }

  /**
   * {@code profile} within {@code target} bytes: as built, else with details cut to {@value
   * #OVERSIZED_PROFILE_DETAIL_BYTES} bytes, else its first steps only ({@code truncated}).
   */
  private static TransactionProfile fit(TransactionProfile profile, int target) {
    if (profile.getSerializedSize() <= target) {
      return profile;
    }
    TransactionProfile.Builder shorter = profile.toBuilder().clearSteps();
    for (io.argos.proto.v1.ProfileStep step : profile.getStepsList()) {
      shorter.addSteps(
          step.toBuilder().setDetail(Utf8.cut(step.getDetail(), OVERSIZED_PROFILE_DETAIL_BYTES)));
    }
    TransactionProfile cut = shorter.build();
    if (cut.getSerializedSize() <= target) {
      return cut;
    }
    TransactionProfile.Builder fewer = cut.toBuilder().clearSteps().setTruncated(true);
    int size = fewer.build().getSerializedSize();
    for (io.argos.proto.v1.ProfileStep step : cut.getStepsList()) {
      int stepSize = CodedOutputStream.computeMessageSize(3, step);
      if (size + stepSize > target) {
        break;
      }
      fewer.addSteps(step);
      size += stepSize;
    }
    return fewer.build();
  }

  private Batch buildPrepared(BatchKind kind) {
    while (true) {
      QueueItem item = pollOne(queue(kind));
      if (item == null) {
        return null;
      }
      if (item instanceof PreparedBatch && ((PreparedBatch) item).kind() == kind) {
        // WO-16: dictionary pre-send and the active item cap of the session's Limits (11 §7).
        return new Batch(
            kind, assembler.prepared(kind, ((PreparedBatch) item).body()), 1, nextSeq(kind));
      }
      counters.incDropped(kind.queue());
    }
  }

  private QueueItem pollOne(AgentQueue<QueueItem> queue) {
    poll.item = null;
    queue.drain(1, poll);
    QueueItem item = poll.item;
    poll.item = null;
    return item;
  }

  private long nextSeq(BatchKind kind) {
    return seqs[kind.index()].incrementAndGet();
  }

  // --- sending -----------------------------------------------------------------------------------

  private void dispatch(final Batch batch, long deadlineNanos) {
    try {
      batch.sessionId = session.sessionId();
      if (dictionary.hasPending()) {
        List<DictionaryEntries> entries = dictionary.takePending();
        if (!session.sendDictionary(entries)) {
          dictionary.unsent(entries);
        }
      }
      BatchHeader header =
          BatchHeader.newBuilder()
              .setInstanceId(BatchAssembler.u32(session.instanceId()))
              .setAgentEpoch(agentEpoch)
              .setSeq(batch.seq)
              .setSentAtMs(clock.currentTimeMillis())
              .build();
      Message message = withHeader(batch.kind, batch.body, header);
      boolean gzip = settings.gzip && message.getSerializedSize() > ProtocolLimits.GZIP_MIN_BYTES;
      long deadline = batch.kind.deadlineMillis(settings.deadlineMillis);
      if (deadlineNanos != Long.MAX_VALUE) {
        long remaining = TimeUnit.NANOSECONDS.toMillis(deadlineNanos - clock.nanoTime());
        deadline = Math.max(1L, Math.min(deadline, remaining));
      }
      transport.send(
          batch.kind,
          message,
          gzip,
          deadline,
          new BatchTransport.Callback() {
            @Override
            public void acknowledged(Ack ack) {
              completed(new Completion(batch, ack, null, null));
            }

            @Override
            public void failed(Status status, Metadata trailers) {
              completed(new Completion(batch, null, status, trailers));
            }
          });
    } catch (OutOfMemoryError e) {
      fatal(e);
      drop(batch);
    } catch (RuntimeException e) {
      completed(new Completion(batch, null, Status.INTERNAL.withCause(e), new Metadata()));
    }
  }

  private void completed(Completion completion) {
    completions.add(completion);
    wake();
  }

  /** The body with {@code header} set ({@code header} is field 1 of every batch message). */
  static Message withHeader(BatchKind kind, Message body, BatchHeader header) {
    switch (kind) {
      case TRANSACTIONS:
        return ((TransactionBatch) body).toBuilder().setHeader(header).build();
      case PROFILES:
        return ((ProfileBatch) body).toBuilder().setHeader(header).build();
      case METRICS:
        return ((MetricBatch) body).toBuilder().setHeader(header).build();
      case STATS:
        return ((StatBatch) body).toBuilder().setHeader(header).build();
      case ACTIVE:
        return ((ActiveTransactionSnapshot) body).toBuilder().setHeader(header).build();
      default:
        throw new IllegalArgumentException("unknown batch kind " + kind);
    }
  }

  // --- outcomes ----------------------------------------------------------------------------------

  private void processCompletions() {
    Completion completion;
    while ((completion = completions.poll()) != null) {
      try {
        handle(completion);
      } catch (RuntimeException e) {
        AgentLogger.warn("batch outcome handling failed: {}", e.getClass().getName());
      }
    }
  }

  private void handle(Completion completion) {
    Batch batch = completion.batch;
    if (completion.ack != null) {
      acknowledged(batch, completion.ack);
      return;
    }
    Status status = completion.status;
    if (causedByOutOfMemory(status.getCause())) {
      fatal(status.getCause());
      drop(batch);
      return;
    }
    switch (status.getCode()) {
      case INVALID_ARGUMENT:
        drop(batch);
        long now = clock.nanoTime();
        if (!invalidWarned || now - lastInvalidWarnNanos >= INVALID_ARGUMENT_WARN_NANOS) {
          invalidWarned = true;
          lastInvalidWarnNanos = now;
          AgentLogger.warn(
              "collector rejected a {} batch as invalid (INVALID_ARGUMENT); it was dropped",
              batch.kind);
        }
        return;
      case PERMISSION_DENIED:
        drop(batch);
        if (!permissionLogged) {
          permissionLogged = true;
          AgentLogger.error(
              "collector denied the {} batch (PERMISSION_DENIED: the token's application does not"
                  + " match app.name); data is dropped and a new session is requested",
              batch.kind);
        }
        session.reestablish(batch.sessionId);
        return;
      case UNAUTHENTICATED:
        drop(batch);
        session.unauthenticated(batch.sessionId);
        return;
      case FAILED_PRECONDITION:
        if (isSessionRequired(status, completion.trailers)) {
          session.reestablish(batch.sessionId);
          retry(batch, 0L);
        } else {
          drop(batch);
        }
        return;
      case RESOURCE_EXHAUSTED:
        Long retryAfter = retryAfterMillis(completion.trailers);
        if (retryAfter != null) {
          retry(batch, retryAfter.longValue());
        } else {
          splitOrDrop(batch);
        }
        return;
      default:
        retry(batch, -1L);
    }
  }

  private void acknowledged(Batch batch, Ack ack) {
    inFlight--;
    transportCounters.incSent(batch.kind);
    long serverDropped = ack.getDroppedItems() & 0xFFFF_FFFFL;
    if (ack.getStatus() == AckStatus.ACK_PARTIAL_DROP || serverDropped > 0) {
      transportCounters.addServerDropped(batch.kind, serverDropped);
    }
    if (batch.kind == BatchKind.TRANSACTIONS) {
      deliveredTransactions.addAndGet(Math.max(0L, batch.items - serverDropped));
    }
    if (ack.hasThrottle()) {
      // 11 §6 quota row "Throttle 힌트가 있으면 적용": Ack.throttle (11 §4.3 Ack field 4) carries the
      // same hints as a session Throttle. The pause stays here (one pause, not one per path); the
      // profile rate goes to the sampler.
      if (ack.getThrottle().hasSendBackoffMs()) {
        pause(clock.nanoTime(), ack.getThrottle().getSendBackoffMs() & 0xFFFF_FFFFL);
      }
      if (ack.getThrottle().hasProfileNormalRate()) {
        profileRate.apply(ack.getThrottle().getProfileNormalRate());
      }
    }
  }

  /** {@code send_backoff_ms} of a {@code Throttle}: no new batch before that time (11 §6). */
  private void pause(long now, long millis) {
    if (millis <= 0) {
      return;
    }
    long until =
        now + TimeUnit.MILLISECONDS.toNanos(Math.min(millis, TimeUnit.MINUTES.toMillis(5)));
    if (!paused || until > pausedUntilNanos) {
      paused = true;
      pausedUntilNanos = until;
    }
  }

  /** A session {@code Throttle.send_backoff_ms} (any thread). */
  void requestPause(long millis) {
    requestedPauseMillis = millis;
    wake();
  }

  /**
   * Schedules a resend of the same batch and {@code seq}: after {@code delayMillis} when it is not
   * negative, else after the next step of 11 §6; drops the batch after {@value #MAX_RETRIES}
   * retries. The batch keeps its in-flight slot.
   */
  private void retry(Batch batch, long delayMillis) {
    if (batch.attempts >= MAX_RETRIES) {
      drop(batch);
      return;
    }
    long delay = delayMillis >= 0 ? delayMillis : RETRY_DELAYS_MILLIS[batch.attempts];
    batch.attempts++;
    batch.dueNanos = clock.nanoTime() + TimeUnit.MILLISECONDS.toNanos(delay);
    transportCounters.incRetry(batch.kind);
    waiting.add(batch);
  }

  /**
   * An oversized batch is split once into two halves with new {@code seq}s, then dropped (11 §10).
   * The first half takes over the original batch's slot and is resent at once; the second half
   * waits in {@link #unslotted} until a slot is free (11 §6: at most {@code sender.max-in-flight}
   * batches hold a slot).
   */
  private void splitOrDrop(Batch batch) {
    if (batch.split || batch.items < 2) {
      drop(batch);
      return;
    }
    Message[] halves = halves(batch);
    if (halves == null) {
      drop(batch);
      return;
    }
    Batch first =
        new Batch(batch.kind, halves[0], itemCount(batch.kind, halves[0]), nextSeq(batch.kind));
    first.split = true;
    first.dueNanos = clock.nanoTime();
    waiting.add(first);
    Batch second =
        new Batch(batch.kind, halves[1], itemCount(batch.kind, halves[1]), nextSeq(batch.kind));
    second.split = true;
    unslotted.add(second);
  }

  private static Message[] halves(Batch batch) {
    if (batch.kind == BatchKind.TRANSACTIONS) {
      TransactionBatch body = (TransactionBatch) batch.body;
      int half = body.getRecordsCount() / 2;
      return new Message[] {
        TransactionBatch.newBuilder().addAllRecords(body.getRecordsList().subList(0, half)).build(),
        TransactionBatch.newBuilder()
            .addAllRecords(body.getRecordsList().subList(half, body.getRecordsCount()))
            .build()
      };
    }
    if (batch.kind == BatchKind.PROFILES) {
      ProfileBatch body = (ProfileBatch) batch.body;
      int half = body.getProfilesCount() / 2;
      return new Message[] {
        ProfileBatch.newBuilder().addAllProfiles(body.getProfilesList().subList(0, half)).build(),
        ProfileBatch.newBuilder()
            .addAllProfiles(body.getProfilesList().subList(half, body.getProfilesCount()))
            .build()
      };
    }
    return null;
  }

  private static int itemCount(BatchKind kind, Message body) {
    if (kind == BatchKind.TRANSACTIONS) {
      return ((TransactionBatch) body).getRecordsCount();
    }
    if (kind == BatchKind.PROFILES) {
      return ((ProfileBatch) body).getProfilesCount();
    }
    return 1;
  }

  /** Drops a batch that holds a slot: the slot is freed, the batch and its items are counted. */
  private void drop(Batch batch) {
    inFlight--;
    countDropped(batch);
  }

  /** Counts a dropped batch and its items (D-06); the caller frees its slot when it holds one. */
  private void countDropped(Batch batch) {
    transportCounters.incDropped(batch.kind);
    counters.addDropped(batch.kind.queue(), batch.items);
  }

  static boolean isSessionRequired(Status status, Metadata trailers) {
    String description = status.getDescription();
    if (description != null && description.contains(SESSION_REQUIRED)) {
      return true;
    }
    String reason = trailers == null ? null : trailers.get(REJECT_REASON);
    return reason != null && reason.contains(SESSION_REQUIRED);
  }

  static Long retryAfterMillis(Metadata trailers) {
    String value = trailers == null ? null : trailers.get(RETRY_AFTER_MS);
    if (value == null) {
      return null;
    }
    try {
      long millis = Long.parseLong(value.trim());
      return millis >= 0 ? Long.valueOf(Math.min(millis, TimeUnit.MINUTES.toMillis(10))) : null;
    } catch (NumberFormatException e) {
      return null;
    }
  }

  static boolean causedByOutOfMemory(Throwable error) {
    Throwable t = error;
    for (int depth = 0; t != null && depth < 16; depth++) {
      if (t instanceof OutOfMemoryError) {
        return true;
      }
      Throwable cause = t.getCause();
      t = cause == null || cause.equals(t) ? null : cause;
    }
    return false;
  }

  /**
   * Direct memory (or heap) exhausted while sending (10 §10.3): the transport is disabled with one
   * ERROR; instrumentation keeps running and the queues are discarded from now on.
   */
  private void fatal(Throwable error) {
    if (disabled) {
      return;
    }
    disabled = true;
    AgentLogger.error(
        "agent transport disabled: memory exhausted while sending ({}); the application and the"
            + " instrumentation keep running, collected data is dropped",
        error.getClass().getName());
    try {
      onFatal.run();
    } catch (RuntimeException ignored) {
      // The transport is already unusable.
    }
  }

  /**
   * Disabled transport: what the queues hold is dropped and counted, so they never stay full; the
   * second halves that wait without a slot are dropped and counted too (they hold no slot, so the
   * in-flight count stays as it is).
   */
  private void discardQueues() {
    if (!unslotted.isEmpty()) {
      for (Batch half : unslotted) {
        countDropped(half);
      }
      unslotted.clear();
    }
    for (BatchKind kind : KINDS) {
      final String queue = kind.queue();
      queue(kind)
          .drain(
              Integer.MAX_VALUE,
              new Consumer<QueueItem>() {
                @Override
                public void accept(QueueItem item) {
                  counters.incDropped(queue);
                }
              });
    }
  }

  private AgentQueue<QueueItem> queue(BatchKind kind) {
    switch (kind) {
      case TRANSACTIONS:
        return queues.transaction();
      case PROFILES:
        return queues.profile();
      case METRICS:
        return queues.metric();
      case STATS:
        return queues.stats();
      default:
        return queues.active();
    }
  }

  private long flushNanos() {
    return TimeUnit.MILLISECONDS.toNanos(settings.flushIntervalMillis);
  }

  // --- state for the session, the shutdown hook and tests ----------------------------------------

  /** The last {@code seq} assigned for {@code kind} (0 before the first batch). */
  long lastSeq(BatchKind kind) {
    return seqs[kind.index()].get();
  }

  /** Disables sending (8u242 without ALPN, a fatal error): queues are discarded. */
  void disable() {
    disabled = true;
    wake();
  }

  boolean disabled() {
    return disabled;
  }

  /** Batches outside the queues now (owner thread). */
  int inFlight() {
    return inFlight;
  }

  /** The most batches that were outside the queues at once (owner thread; tests). */
  int maxObservedInFlight() {
    return maxObservedInFlight;
  }

  /** Transaction records the server acknowledged (minus the items it dropped). */
  long deliveredTransactions() {
    return deliveredTransactions.get();
  }

  /** The consumer that takes one item from a queue. */
  private static final class Poll implements Consumer<QueueItem> {
    QueueItem item;

    @Override
    public void accept(QueueItem value) {
      item = value;
    }
  }
}
