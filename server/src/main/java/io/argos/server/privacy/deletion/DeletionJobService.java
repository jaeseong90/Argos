/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.privacy.deletion;

import io.argos.server.audit.Actor;
import io.argos.server.audit.AuditEvent;
import io.argos.server.audit.AuditResult;
import io.argos.server.audit.AuditService;
import io.argos.server.audit.Target;
import io.argos.server.config.ArgosProperties;
import io.argos.server.ops.migration.SchemaParameters;
import io.argos.server.privacy.deletion.DeletionQueries.Ip;
import io.argos.server.privacy.deletion.DeletionQueries.Subject;
import io.argos.server.privacy.deletion.DeletionQueries.UserId;
import io.argos.server.privacy.salt.UserIdHasher;
import io.argos.server.storage.clickhouse.RollupPartitions;
import io.argos.server.storage.clickhouse.RollupPartitions.StatementException;
import io.argos.server.storage.clickhouse.TableCatalog;
import io.argos.server.storage.clickhouse.TableCatalog.MutationState;
import io.argos.server.storage.postgres.privacyjob.PrivacyJob;
import io.argos.server.storage.postgres.privacyjob.PrivacyJob.Status;
import io.argos.server.storage.postgres.privacyjob.PrivacyJob.TableMutation;
import io.argos.server.storage.postgres.privacyjob.PrivacyJobRepository;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;

/**
 * The privacy deletion jobs (SEC-33, F-S32; 13 §9, 16 §6.8; ADR-0007 C-60, C-66): {@code POST
 * /api/v1/admin/privacy/deletions} queues a job, one thread ({@code argos-privacy-job}) runs the
 * jobs one after the other with the migration account.
 *
 * <p>A job, for every UTC day of {@code [from, to)} that still has an {@code xlog} partition (the
 * range intersected with the retention), oldest first:
 *
 * <ol>
 *   <li>counts the matching {@code xlog} rows (rows of {@code [from, to)} only) and the {@code
 *       profile_step} rows of those transactions; a day without a matching row issues nothing;
 *   <li>issues {@code ALTER TABLE profile_step DELETE IN PARTITION ... WHERE (app_id, tx_id) IN
 *       (SELECT ... FROM xlog ...)} and waits until it is done (its subquery reads {@code xlog}, so
 *       it must finish before the {@code xlog} mutation of the day starts, 13 §9);
 *   <li>issues {@code ALTER TABLE xlog DELETE IN PARTITION ... WHERE ...}.
 * </ol>
 *
 * At most {@code argos.privacy.deletion.max-concurrent-mutations} (2) mutations of Argos are
 * pending at any time; the job waits for a free slot before it issues the next one. The state of
 * each mutation is read from {@code system.mutations} ({@code is_done}, {@code is_killed}, {@code
 * latest_fail_reason}; 12 open item 6) every {@link #POLL}; a mutation killed, or failing for
 * {@link #FAIL_POLLS} polls in a row, fails the job with its ClickHouse error code (never the text:
 * it may quote the condition). Rollups are aggregates and are not touched (13 §9 step 5).
 *
 * <p>The subject value is held in memory only, for the time the job issues its mutations:
 * PostgreSQL keeps its SHA-256 ({@code privacy_jobs.subject_hash}), no log line and no audit record
 * carries it (audit {@code PRIVACY_DELETE_REQUESTED} has the kind and the SHA-256, {@code
 * PRIVACY_DELETE_COMPLETED} the outcome). After a restart, a job whose mutations were all issued
 * ({@code rows_affected} set) is resumed: its pending mutations are polled to the end; a queued
 * job, or one interrupted while issuing, is failed with {@code interrupted_by_restart} (the request
 * is idempotent: sending it again deletes what is left).
 *
 * <p>Gauge {@value #GAUGE} ({@code argos_privacy_jobs{state}}): jobs in PostgreSQL by state.
 */
public final class DeletionJobService implements SmartLifecycle {

  /** Micrometer name of the gauge ({@code argos_privacy_jobs{state}}). */
  public static final String GAUGE = "argos.privacy.jobs";

  /** Jobs waiting in memory at most; a request beyond is refused. */
  public static final int QUEUE_CAPACITY = 100;

  /** Interval of the {@code system.mutations} polls. */
  static final Duration POLL = Duration.ofSeconds(1);

  /** Consecutive polls with a failure reason that fail a job. */
  static final int FAIL_POLLS = 60;

  /** Timeout of one ClickHouse statement of a job (counts and {@code ALTER}). */
  static final Duration STATEMENT_TIMEOUT = Duration.ofMinutes(30);

  /** Error of a job that a restart interrupted. */
  public static final String INTERRUPTED = "interrupted_by_restart";

  /** Warning: rows of masked instances matched the whole /24 or /48 network of the address. */
  public static final String IP_MASKED_SCOPE = "ip_masked_scope";

  private static final Logger LOG = LoggerFactory.getLogger(DeletionJobService.class);
  private static final Pattern ERROR_CODE = Pattern.compile("Code:\\s*(\\d+)");

  private final ArgosProperties properties;
  private final PrivacyJobRepository jobs;
  private final UserIdHasher hasher;
  private final AuditService audit;
  private final Clock clock;
  private final BlockingQueue<Work> queue = new ArrayBlockingQueue<>(QUEUE_CAPACITY);
  private final Map<Status, AtomicLong> counts = new EnumMap<>(Status.class);
  private final Object lifecycle = new Object();
  private volatile boolean running;
  private Thread worker; // guarded by lifecycle

  /**
   * @param properties the server configuration
   * @param jobs the job table
   * @param hasher the hashed user ids of the applications
   * @param audit the audit service
   * @param clock UTC clock
   * @param meters the server's registry
   */
  public DeletionJobService(
      ArgosProperties properties,
      PrivacyJobRepository jobs,
      UserIdHasher hasher,
      AuditService audit,
      Clock clock,
      MeterRegistry meters) {
    this.properties = properties;
    this.jobs = jobs;
    this.hasher = hasher;
    this.audit = audit;
    this.clock = clock;
    for (Status s : Status.values()) {
      AtomicLong value = new AtomicLong();
      counts.put(s, value);
      Gauge.builder(GAUGE, value, AtomicLong::get)
          .tag("state", s.value())
          .description("Privacy deletion jobs by state (13 §9)")
          .register(meters);
    }
  }

  /** The kind of a subject (14 §4.14 parameters). */
  public enum SubjectKind {
    /** {@code user_id}. */
    USER_ID("user_id"),
    /** {@code ip}. */
    IP("ip");

    private final String value;

    SubjectKind(String value) {
      this.value = value;
    }

    /** The column and API value. */
    public String value() {
      return value;
    }
  }

  /** A request refused before a job was created (invalid range, unknown app, full queue). */
  public static final class RequestException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final String reason;

    RequestException(String reason, String message) {
      super(message);
      this.reason = reason;
    }

    /** A short code: {@code range_too_long}, {@code before_from}, {@code app_not_found}, ... */
    public String reason() {
      return reason;
    }
  }

  /** The work of a queued job: its id and the subject (memory only). */
  private record Work(UUID jobId, Subject subject) {}

  /**
   * Queues a deletion of a user id.
   *
   * @param normalizedUserId the user id normalised like the search (C-163 (2))
   * @param appId the application, or null for every application
   * @param from range start
   * @param to range end (exclusive)
   * @param userId the administrator
   * @param actor the audit actor
   * @return the queued job
   * @throws RequestException when the request is refused
   */
  public PrivacyJob submitUserId(
      String normalizedUserId,
      @Nullable Integer appId,
      Instant from,
      Instant to,
      @Nullable UUID userId,
      Actor actor) {
    checkRequest(appId, from, to);
    Map<Integer, String> hashed = new LinkedHashMap<>();
    List<Integer> apps = appId == null ? jobs.appIds() : List.of(appId);
    for (int app : apps) {
      hasher.hashed(app, normalizedUserId).ifPresent(h -> hashed.put(app, h));
    }
    return submit(
        SubjectKind.USER_ID,
        sha256(normalizedUserId),
        new UserId(normalizedUserId, hashed),
        appId,
        from,
        to,
        userId,
        actor,
        List.of());
  }

  /**
   * Queues a deletion of a client address.
   *
   * @param address the parsed IP literal
   * @see #submitUserId
   */
  public PrivacyJob submitIp(
      InetAddress address,
      @Nullable Integer appId,
      Instant from,
      Instant to,
      @Nullable UUID userId,
      Actor actor) {
    checkRequest(appId, from, to);
    return submit(
        SubjectKind.IP,
        sha256(DeletionQueries.text(address)),
        new Ip(address),
        appId,
        from,
        to,
        userId,
        actor,
        List.of());
  }

  private void checkRequest(@Nullable Integer appId, Instant from, Instant to) {
    if (!to.isAfter(from)) {
      throw new RequestException("before_from", "to must be after from");
    }
    if (Duration.between(from, to).compareTo(properties.privacy().deletion().maxRange()) > 0) {
      throw new RequestException(
          "range_too_long",
          "The range may cover at most "
              + properties.privacy().deletion().maxRange().toDays()
              + " days");
    }
    if (appId != null && (appId < 1 || !jobs.appExists(appId))) {
      throw new RequestException("app_not_found", "The application does not exist");
    }
  }

  private PrivacyJob submit(
      SubjectKind kind,
      String hash,
      Subject subject,
      @Nullable Integer appId,
      Instant from,
      Instant to,
      @Nullable UUID userId,
      Actor actor,
      List<String> warnings) {
    if (queue.remainingCapacity() == 0) {
      throw new RequestException("queue_full", "Too many deletion jobs are waiting; retry later");
    }
    PrivacyJob job =
        new PrivacyJob(
            UUID.randomUUID(),
            kind.value(),
            hash,
            appId,
            from,
            to,
            Status.QUEUED,
            userId,
            clock.instant(),
            null,
            null,
            null,
            List.of(),
            warnings,
            null);
    jobs.insert(job);
    if (!queue.offer(new Work(job.id(), subject))) {
      jobs.finish(job.id(), Status.FAILED, clock.instant(), "queue_full");
      refreshCounts();
      throw new RequestException("queue_full", "Too many deletion jobs are waiting; retry later");
    }
    Map<String, Object> after = new LinkedHashMap<>();
    after.put("subject_kind", kind.value());
    after.put("subject_sha256", hash);
    after.put("app_id", appId);
    after.put("from", from.toString());
    after.put("to", to.toString());
    audit.record(
        AuditEvent.PRIVACY_DELETE_REQUESTED,
        actor,
        target(job.id()),
        AuditResult.success(),
        null,
        after);
    refreshCounts();
    return job;
  }

  /** The job, or empty. */
  public Optional<PrivacyJob> find(UUID id) {
    return jobs.find(id);
  }

  /** SHA-256 hex of a normalised value (UTF-8): what {@code privacy_jobs.subject_hash} keeps. */
  public static String sha256(String value) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  private static Target target(UUID jobId) {
    return new Target("privacy_job", jobId.toString(), null);
  }

  // --- the runner -------------------------------------------------------------------------------

  private void loop() {
    try {
      resume();
    } catch (RuntimeException e) {
      LOG.warn("Resuming privacy deletion jobs failed: {}", e.getClass().getSimpleName());
    }
    while (running) {
      Work work;
      try {
        work = queue.poll(1, TimeUnit.SECONDS);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        return;
      }
      if (work == null) {
        continue;
      }
      try {
        process(work);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        return;
      } catch (RuntimeException e) {
        LOG.warn("Privacy deletion job {} failed: {}", work.jobId(), e.getClass().getSimpleName());
        end(work.jobId(), Status.FAILED, "internal_error", null, List.of());
      }
    }
  }

  /** Jobs left over by the previous run (class comment). */
  void resume() {
    for (PrivacyJob job : jobs.unfinished()) {
      if (job.status() == Status.RUNNING && job.rowsAffected() != null) {
        LOG.info("Resuming privacy deletion job {}: polling its pending mutations", job.id());
        try {
          List<TableMutation> tables = new ArrayList<>(job.tables());
          try (RollupPartitions.Session sql = open()) {
            SchemaParameters params = SchemaParameters.from(properties);
            for (int i = 0; i < tables.size(); i++) {
              if (!tables.get(i).done()) {
                waitDone(sql, params, tables.get(i));
                tables.set(i, tables.get(i).finished());
                jobs.progress(job.id(), tables, job.warnings(), job.rowsAffected());
              }
            }
          }
          end(job.id(), Status.DONE, null, job.rowsAffected(), tables);
        } catch (JobFailure e) {
          end(job.id(), Status.FAILED, e.getMessage(), job.rowsAffected(), job.tables());
        } catch (StatementException e) {
          end(
              job.id(),
              Status.FAILED,
              "clickhouse_error" + code(e),
              job.rowsAffected(),
              job.tables());
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
          return;
        } catch (RuntimeException e) {
          end(job.id(), Status.FAILED, "clickhouse_unavailable", job.rowsAffected(), job.tables());
        }
      } else {
        LOG.warn(
            "Privacy deletion job {} was interrupted by a restart; send the request again",
            job.id());
        end(job.id(), Status.FAILED, INTERRUPTED, job.rowsAffected(), job.tables());
      }
    }
  }

  /** A job failure with a short code (never ClickHouse text). */
  private static final class JobFailure extends Exception {

    private static final long serialVersionUID = 1L;

    JobFailure(String code) {
      super(code);
    }
  }

  private RollupPartitions.Session open() {
    return RollupPartitions.open(properties.clickhouse(), STATEMENT_TIMEOUT);
  }

  private void process(Work work) throws InterruptedException {
    Optional<PrivacyJob> found = jobs.find(work.jobId());
    if (found.isEmpty() || found.get().status() != Status.QUEUED) {
      return;
    }
    PrivacyJob job = found.get();
    jobs.markRunning(job.id(), clock.instant());
    refreshCounts();
    List<TableMutation> tables = new ArrayList<>();
    Set<String> warnings = new LinkedHashSet<>(job.warnings());
    long rows = 0;
    SchemaParameters params;
    try {
      params = SchemaParameters.from(properties);
    } catch (IllegalArgumentException e) {
      end(job.id(), Status.FAILED, "invalid_configuration", null, tables);
      return;
    }
    String condition =
        DeletionQueries.condition(work.subject(), job.appId())
            + " AND ts >= fromUnixTimestamp64Milli("
            + job.from().toEpochMilli()
            + ", 'UTC') AND ts < fromUnixTimestamp64Milli("
            + job.to().toEpochMilli()
            + ", 'UTC')";
    int max = properties.privacy().deletion().maxConcurrentMutations();
    List<TableMutation> pending = new ArrayList<>();
    try (RollupPartitions.Session sql = open()) {
      // The range intersected with the retention: a day outside it has no partition, and a day
      // without a matching row issues nothing. Each count reads one partition only (the partition
      // key is toDate(ts)); the range has at most 31 days (argos.privacy.deletion.max-range).
      LocalDate first = LocalDate.ofInstant(job.from(), ZoneOffset.UTC);
      LocalDate last = LocalDate.ofInstant(job.to().minusMillis(1), ZoneOffset.UTC);
      for (LocalDate day : DeletionQueries.days(first, last)) {
        long x = count(sql, DeletionQueries.countXlog(params, day, condition));
        if (x == 0) {
          continue;
        }
        if (work.subject() instanceof Ip
            && count(sql, DeletionQueries.countMaskedXlog(params, day, condition)) > 0) {
          warnings.add(IP_MASKED_SCOPE);
        }
        long p = count(sql, DeletionQueries.countProfile(params, day, condition));
        rows += x + p;
        if (p > 0) {
          waitForSlot(sql, params, pending, max);
          TableMutation m =
              issue(
                  sql,
                  params,
                  "profile_step",
                  day,
                  DeletionQueries.deleteProfile(params, day, condition));
          tables.add(m);
          jobs.progress(job.id(), tables, List.copyOf(warnings), null);
          waitDone(sql, params, m);
          replace(tables, m.finished());
          jobs.progress(job.id(), tables, List.copyOf(warnings), null);
        }
        waitForSlot(sql, params, pending, max);
        TableMutation m =
            issue(sql, params, "xlog", day, DeletionQueries.deleteXlog(params, day, condition));
        tables.add(m);
        pending.add(m);
        jobs.progress(job.id(), tables, List.copyOf(warnings), null);
      }
      // Every mutation is issued: from here on a restart resumes the job (rows_affected set).
      jobs.progress(job.id(), tables, List.copyOf(warnings), rows);
      // Every mutation not yet marked done (including those a free slot found finished).
      for (TableMutation m : List.copyOf(tables)) {
        if (!m.done()) {
          waitDone(sql, params, m);
          replace(tables, m.finished());
          jobs.progress(job.id(), tables, List.copyOf(warnings), rows);
        }
      }
      end(job.id(), Status.DONE, null, rows, tables);
    } catch (JobFailure e) {
      end(job.id(), Status.FAILED, e.getMessage(), rows, tables);
    } catch (StatementException e) {
      end(job.id(), Status.FAILED, "clickhouse_error" + code(e), rows, tables);
    } catch (IllegalStateException e) {
      end(job.id(), Status.FAILED, "clickhouse_unavailable", rows, tables);
    }
  }

  private static void replace(List<TableMutation> tables, TableMutation done) {
    for (int i = 0; i < tables.size(); i++) {
      if (tables.get(i).mutationId().equals(done.mutationId())
          && tables.get(i).name().equals(done.name())) {
        tables.set(i, done);
      }
    }
  }

  private static long count(RollupPartitions.Session sql, String query) throws StatementException {
    List<String> v = sql.queryColumn(query);
    return v.isEmpty() ? 0 : Long.parseLong(v.get(0));
  }

  /** Issues one mutation and finds its id (the new id of the table in {@code system.mutations}). */
  private static TableMutation issue(
      RollupPartitions.Session sql,
      SchemaParameters params,
      String table,
      LocalDate day,
      String statement)
      throws StatementException, JobFailure {
    String physical = params.tablePrefix() + table;
    Set<String> before = new HashSet<>(TableCatalog.mutationIds(sql, params.database(), physical));
    sql.execute(statement);
    List<String> after = TableCatalog.mutationIds(sql, params.database(), physical);
    String id = null;
    for (String candidate : after) {
      if (!before.contains(candidate) && TableCatalog.isMutationId(candidate)) {
        id = candidate; // the newest new one (ordered by create_time)
      }
    }
    if (id == null) {
      throw new JobFailure("mutation_not_found");
    }
    return new TableMutation(table, day.toString(), id, false);
  }

  private static void waitForSlot(
      RollupPartitions.Session sql, SchemaParameters params, List<TableMutation> pending, int max)
      throws StatementException, InterruptedException, JobFailure {
    while (pending.size() >= max) {
      for (TableMutation m : List.copyOf(pending)) {
        MutationState s =
            TableCatalog.mutation(
                sql, params.database(), params.tablePrefix() + m.name(), m.mutationId());
        if (!s.found() || s.done()) {
          pending.remove(m);
        } else if (s.killed()) {
          throw new JobFailure("mutation_killed");
        }
      }
      if (pending.size() >= max) {
        Thread.sleep(POLL.toMillis());
      }
    }
  }

  private static void waitDone(
      RollupPartitions.Session sql, SchemaParameters params, TableMutation m)
      throws StatementException, InterruptedException, JobFailure {
    int failing = 0;
    while (true) {
      MutationState s =
          TableCatalog.mutation(
              sql, params.database(), params.tablePrefix() + m.name(), m.mutationId());
      if (!s.found() || s.done()) {
        return;
      }
      if (s.killed()) {
        throw new JobFailure("mutation_killed");
      }
      failing = s.failCode() == null ? 0 : failing + 1;
      if (failing >= FAIL_POLLS) {
        throw new JobFailure("mutation_failed:" + s.failCode());
      }
      Thread.sleep(POLL.toMillis());
    }
  }

  private static String code(StatementException e) {
    String message = e.getMessage();
    Matcher m = ERROR_CODE.matcher(message == null ? "" : message);
    return m.find() ? ":" + m.group(1) : "";
  }

  private void end(
      UUID id,
      Status status,
      @Nullable String error,
      @Nullable Long rows,
      List<TableMutation> tables) {
    try {
      jobs.finish(id, status, clock.instant(), error);
    } catch (RuntimeException e) {
      LOG.warn("Privacy deletion job {} could not be closed: {}", id, e.getClass().getSimpleName());
    }
    Map<String, Object> after = new LinkedHashMap<>();
    after.put("status", status.value());
    after.put("rows_affected", rows);
    after.put("mutations", tables.size());
    after.put("partitions", tables.stream().map(TableMutation::partition).distinct().count());
    audit.record(
        AuditEvent.PRIVACY_DELETE_COMPLETED,
        Actor.system(),
        target(id),
        status == Status.DONE ? AuditResult.success() : AuditResult.failure(error),
        null,
        after);
    LOG.info(
        "Privacy deletion job {} {}: {} mutation(s), {} matching row(s){}",
        id,
        status.value(),
        tables.size(),
        rows == null ? "unknown" : rows,
        error == null ? "" : " (" + error + ")");
    refreshCounts();
  }

  private void refreshCounts() {
    try {
      jobs.countByStatus().forEach((s, n) -> counts.get(s).set(n));
    } catch (RuntimeException e) {
      LOG.debug("Privacy job counts not refreshed: {}", e.getClass().getSimpleName());
    }
  }

  // --- lifecycle
  // -----------------------------------------------------------------------------------

  @Override
  public void start() {
    synchronized (lifecycle) {
      if (running) {
        return;
      }
      running = true;
      refreshCounts();
      Thread t = new Thread(this::loop, "argos-privacy-job");
      t.setDaemon(true);
      t.start();
      worker = t;
    }
  }

  @Override
  public void stop() {
    synchronized (lifecycle) {
      running = false;
      if (worker != null) {
        worker.interrupt();
        worker = null;
      }
    }
  }

  @Override
  public boolean isRunning() {
    return running;
  }
}
