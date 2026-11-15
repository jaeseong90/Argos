/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.ingest;

import com.google.protobuf.Message;
import io.argos.proto.v1.ActiveTransactionSnapshot;
import io.argos.proto.v1.DictionaryRequest;
import io.argos.proto.v1.SessionClose;
import io.argos.server.collector.api.DedupKey;
import io.argos.server.config.ArgosProperties;
import io.argos.server.ingest.api.RealtimePublisher;
import io.argos.server.ingest.api.XlogPoint;
import io.argos.server.ops.migration.ClickHouseMigrationPhase;
import io.argos.server.ops.migration.MigrationSource;
import io.argos.server.ops.migration.PostgresMigrationPhase;
import io.argos.server.session.api.SessionContext;
import io.argos.server.session.api.SessionMessenger;
import io.argos.server.storage.clickhouse.BlockInserter;
import io.argos.server.storage.clickhouse.ClickHouseBatchWriter;
import io.argos.server.storage.clickhouse.ClickHouseBlockInserter;
import io.argos.server.storage.postgres.ingest.JdbcIngestStore;
import io.argos.server.testing.ServerTestEnvironment;
import io.argos.server.wal.FileWal;
import io.argos.server.wal.WalDirectory;
import io.argos.server.wal.WalRecovery;
import io.argos.server.wal.api.WalDataPayload;
import io.argos.server.wal.api.WalPosition;
import io.argos.server.wal.api.WalRecordType;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.function.UnaryOperator;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

/**
 * The durable ingest path of the server without Spring and without gRPC (lane durable-ingest): the
 * real WAL in a directory, the real ClickHouse writer as {@code argos_writer}, the real PostgreSQL
 * store as {@code argos_app}, the recovery of the {@code WAL_RECOVERY} phase. Tests append WAL
 * records the way the collector does after its checks ({@link #append}) and read the databases of a
 * {@link ServerTestEnvironment}.
 */
final class IngestStack implements AutoCloseable {

  /** Options of one stack. */
  record Options(
      int flushIntervalMs,
      int flushRows,
      int queueRows,
      long segmentBytes,
      long maxBytes,
      int dictionaryPerHour,
      long backoffMinMs,
      UnaryOperator<BlockInserter> inserterWrapper) {

    static Options defaults() {
      return new Options(200, 50_000, 200_000, 1L << 20, 1L << 30, 10_000, 200, i -> i);
    }

    Options withInserter(UnaryOperator<BlockInserter> wrapper) {
      return new Options(
          flushIntervalMs,
          flushRows,
          queueRows,
          segmentBytes,
          maxBytes,
          dictionaryPerHour,
          backoffMinMs,
          wrapper);
    }

    Options withFlush(int intervalMs, int rows) {
      return new Options(
          intervalMs,
          rows,
          queueRows,
          segmentBytes,
          maxBytes,
          dictionaryPerHour,
          backoffMinMs,
          inserterWrapper);
    }

    Options withSegmentBytes(long bytes) {
      return new Options(
          flushIntervalMs,
          flushRows,
          queueRows,
          bytes,
          maxBytes,
          dictionaryPerHour,
          backoffMinMs,
          inserterWrapper);
    }
  }

  /** Records what the ingest side publishes in real time. */
  static final class RecordingPublisher implements RealtimePublisher {
    final List<XlogPoint> points = new CopyOnWriteArrayList<>();

    @Override
    public void publishXlog(SessionContext session, List<XlogPoint> batch) {
      points.addAll(batch);
    }

    @Override
    public void publishActive(SessionContext session, ActiveTransactionSnapshot snapshot) {}
  }

  /** Records dictionary requests (no live agent). */
  static final class RecordingMessenger implements SessionMessenger {
    final List<DictionaryRequest> requests = new CopyOnWriteArrayList<>();

    @Override
    public boolean requestDictionary(int appId, int instanceId, DictionaryRequest request) {
      requests.add(request);
      return true;
    }

    @Override
    public boolean close(int instanceId, SessionClose close) {
      return false;
    }
  }

  private static final Object MIGRATION_LOCK = new Object();
  private static final Map<String, Boolean> MIGRATED = new HashMap<>();

  final ArgosProperties properties;
  final SimpleMeterRegistry registry = new SimpleMeterRegistry();
  final javax.sql.DataSource dataSource;
  final FileWal wal;
  final ClickHouseBatchWriter writer;
  final JdbcIngestStore store;
  final IngestMetrics metrics;
  final DictionaryResolver dictionary;
  final ErrorGroupRecorder errorGroups;
  final ApdexStamper apdex;
  final RecordDecoder decoder;
  final RecordingPublisher publisher = new RecordingPublisher();
  final RecordingMessenger messenger = new RecordingMessenger();
  final IngestService ingest;
  final ClickHouseBlockInserter inserter;
  final List<DedupKey> restoredKeys = new ArrayList<>();
  WalRecovery.Scan scan;

  private IngestStack(ServerTestEnvironment env, Path walDir, Options options) throws Exception {
    this(env.springProperties(), new EnvDataSource(env), walDir, options, () -> migrate(env));
  }

  /**
   * A stack from Spring properties and a runtime data source (a child JVM of a crash test has no
   * Testcontainers handle).
   */
  IngestStack(
      Map<String, String> springProperties,
      javax.sql.DataSource runtime,
      Path walDir,
      Options options,
      MigrationStep migration)
      throws Exception {
    Map<String, Object> props = new HashMap<>(springProperties);
    props.put("argos.wal.dir", walDir.toString());
    properties =
        new Binder(new MapConfigurationPropertySource(props))
            .bind("argos", ArgosProperties.class)
            .get();
    migration.run();
    dataSource = runtime;
    Clock clock = Clock.systemUTC();
    wal = new FileWal(walDir, options.segmentBytes(), options.maxBytes(), 10, 0.8, registry, clock);
    writer =
        new ClickHouseBatchWriter(
            wal,
            options.flushIntervalMs(),
            options.flushRows(),
            options.queueRows(),
            properties.clickhouse(),
            registry,
            clock);
    writer.setBackoffMinMs(options.backoffMinMs());
    store = new JdbcIngestStore(dataSource);
    metrics = new IngestMetrics(registry);
    StaticListableBeanFactory beans = new StaticListableBeanFactory();
    beans.addBean("messenger", messenger);
    dictionary =
        new DictionaryResolver(
            store,
            beans.getBeanProvider(SessionMessenger.class),
            metrics,
            options.dictionaryPerHour(),
            DictionaryResolver.DEFAULT_APP_CAP,
            clock);
    errorGroups = new ErrorGroupRecorder(store, metrics);
    apdex = new ApdexStamper(store);
    decoder = new RecordDecoder(dictionary, errorGroups, metrics);
    ingest = new IngestService(wal, writer, decoder, metrics, publisher, clock);
    ingest.setGcPeriodMs(1_000);
    WalDirectory dir = WalDirectory.open(walDir);
    scan =
        WalRecovery.scan(
            dir, r -> restoredKeys.add(new DedupKey(r.instanceId(), r.epoch(), r.type(), r.seq())));
    wal.start(dir, scan.segments());
    inserter = ClickHouseBlockInserter.open(properties.clickhouse());
    writer.start(options.inserterWrapper().apply(inserter));
    dictionary.start();
    errorGroups.start();
    apdex.start();
    ingest.recover(scan);
  }

  /**
   * Migrates PostgreSQL and ClickHouse once per environment and starts a stack on {@code walDir}.
   */
  static IngestStack start(ServerTestEnvironment env, Path walDir, Options options)
      throws Exception {
    return new IngestStack(env, walDir, options);
  }

  static IngestStack start(ServerTestEnvironment env, Path walDir) throws Exception {
    return start(env, walDir, Options.defaults());
  }

  /**
   * Runs the PostgreSQL and ClickHouse migrations of {@code env} once (the server's own runners).
   */
  static void ensureMigrated(ServerTestEnvironment env) throws Exception {
    migrate(env);
  }

  /** A migration run before the stack starts. */
  @FunctionalInterface
  interface MigrationStep {
    void run() throws Exception;
  }

  private static void migrate(ServerTestEnvironment env) throws Exception {
    Map<String, Object> props = new HashMap<>(env.springProperties());
    ArgosProperties properties =
        new Binder(new MapConfigurationPropertySource(props))
            .bind("argos", ArgosProperties.class)
            .get();
    synchronized (MIGRATION_LOCK) {
      String key = env.postgresJdbcUrl();
      if (MIGRATED.containsKey(key)) {
        return;
      }
      new PostgresMigrationPhase(properties).run();
      new ClickHouseMigrationPhase(
              properties, MigrationSource.fromClasspath(IngestStack.class.getClassLoader()))
          .run();
      MIGRATED.put(key, Boolean.TRUE);
    }
  }

  /** Appends one data record as the collector does after its checks (the ack point). */
  WalPosition append(WalRecordType type, SessionContext session, Message batch, long seq)
      throws Exception {
    return wal.append(
        type,
        session.instanceId(),
        session.agentEpoch(),
        seq,
        session.receivedAtMs(),
        session.tsCorrectionMs(),
        WalDataPayload.of(session, batch.toByteString()).encode());
  }

  /** Waits until every record durable now is decoded and committed in ClickHouse. */
  void awaitLoaded() throws InterruptedException {
    io.argos.server.wal.DurableMark mark = wal.durableMark();
    long deadline = System.nanoTime() + TimeUnit.MINUTES.toNanos(5);
    while (!ingest.isProcessedThrough(mark)) {
      if (System.nanoTime() > deadline) {
        throw new AssertionError("ingest did not reach " + mark + " within 5 minutes");
      }
      Thread.sleep(20);
    }
    writer.flushNow();
    if (!writer.awaitIdle(TimeUnit.MINUTES.toMillis(5))) {
      throw new AssertionError("ClickHouse writer not idle within 5 minutes");
    }
  }

  /** Flushes the PostgreSQL side (dictionary and error groups). */
  void flushPostgres() throws InterruptedException {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(240);
    while (dictionary.queuedEntries() > 0 && System.nanoTime() < deadline) {
      Thread.sleep(50);
    }
    Thread.sleep(700); // one more turn of the writer threads (500 ms batches)
    errorGroups.flush();
  }

  /**
   * Stops without the shutdown flush: what a crash leaves behind (writer threads interrupted, no
   * last flush, queued rows only in the WAL).
   */
  void crash() {
    ingest.close();
    writer.abort();
    inserter.close();
    dictionary.close();
    errorGroups.destroy();
    apdex.destroy();
    wal.close();
    closeDataSource();
  }

  @Override
  public void close() {
    ingest.close();
    writer.close();
    inserter.close();
    dictionary.close();
    errorGroups.destroy();
    apdex.destroy();
    wal.close();
    closeDataSource();
  }

  private void closeDataSource() {
    if (dataSource instanceof AutoCloseable closeable) {
      try {
        closeable.close();
      } catch (Exception e) {
        // test data source: nothing to report
      }
    }
  }

  /**
   * The runtime account {@code argos_app} as a {@link javax.sql.DataSource} without a pool (the
   * server uses HikariCP; the tests open one connection per call).
   */
  static final class EnvDataSource implements javax.sql.DataSource {
    private final ServerTestEnvironment env;

    EnvDataSource(ServerTestEnvironment env) {
      this.env = env;
    }

    @Override
    public java.sql.Connection getConnection() throws java.sql.SQLException {
      return env.connect(ServerTestEnvironment.PgAccount.APP);
    }

    @Override
    public java.sql.Connection getConnection(String username, String password) {
      throw new UnsupportedOperationException();
    }

    @Override
    public java.io.PrintWriter getLogWriter() {
      return null;
    }

    @Override
    public void setLogWriter(java.io.PrintWriter out) {}

    @Override
    public void setLoginTimeout(int seconds) {}

    @Override
    public int getLoginTimeout() {
      return 0;
    }

    @Override
    public java.util.logging.Logger getParentLogger() {
      return java.util.logging.Logger.getLogger("io.argos.server.ingest");
    }

    @Override
    public <T> T unwrap(Class<T> iface) throws java.sql.SQLException {
      throw new java.sql.SQLException("not a wrapper");
    }

    @Override
    public boolean isWrapperFor(Class<?> iface) {
      return false;
    }
  }
}
