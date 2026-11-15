/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.ingest;

import io.argos.proto.v1.TransactionRecord;
import io.argos.server.session.api.SessionContext;
import io.argos.server.storage.api.Table;
import io.argos.server.testing.ServerTestEnvironment;
import io.argos.server.testing.ServerTestEnvironment.PgAccount;
import io.argos.server.wal.api.WalRecordType;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * The process that a crash test kills (T-SV-010 at the durable-ingest level). It starts the ingest
 * stack on a WAL directory, then, by {@code mode}:
 *
 * <ul>
 *   <li>{@code batches} (default): appends {@code batches} transaction batches as the collector
 *       does and prints {@code ACK <seq>} after each append returned (the point where the agent
 *       gets its ack). Inserts are slowed down so that the kill lands while blocks are being
 *       inserted or committed; prints {@code DONE}.
 *   <li>{@code skewed}: appends what an instance {@code skew.ms} ahead sends just before the true
 *       midnight {@code midnight.ms} ({@link IngestFixtures.SkewedRun#beforeTrueMidnight},
 *       corrected session, ADR-0010 C-129). Each raw table's writer stops right after its {@code
 *       BLOCK_INTENT} was fsynced, before its insert; prints {@code BLOCKED} once all five are
 *       there ({@code TIMEOUT} after 2 minutes).
 * </ul>
 *
 * Then it waits to be killed.
 *
 * <p>Argument: a properties file with {@code spring.<key>} (the server properties of the test
 * environment, password files only), {@code pg.url}, {@code pg.user}, {@code pg.password-file},
 * {@code wal.dir}, {@code app.id}, {@code instance.id}, and {@code batches}, {@code records},
 * {@code insert.delay.ms} or {@code skew.ms}, {@code midnight.ms}.
 */
public final class IngestCrashChild {

  private IngestCrashChild() {}

  public static void main(String[] args) throws Exception {
    Properties p = new Properties();
    try (Reader r = Files.newBufferedReader(Path.of(args[0]), StandardCharsets.UTF_8)) {
      p.load(r);
    }
    Map<String, String> spring = new HashMap<>();
    for (String key : p.stringPropertyNames()) {
      if (key.startsWith("spring.")) {
        spring.put(key.substring("spring.".length()), p.getProperty(key));
      }
    }
    String pgUrl = p.getProperty("pg.url");
    String pgUser = p.getProperty("pg.user");
    String pgPassword =
        Files.readString(Path.of(p.getProperty("pg.password-file")), StandardCharsets.UTF_8)
            .strip();
    int appId = Integer.parseInt(p.getProperty("app.id"));
    int instanceId = Integer.parseInt(p.getProperty("instance.id"));
    Path walDir = Path.of(p.getProperty("wal.dir"));
    PrintWriter out = new PrintWriter(System.out, true, StandardCharsets.UTF_8);
    javax.sql.DataSource ds = new DriverDataSource(pgUrl, pgUser, pgPassword);
    if ("skewed".equals(p.getProperty("mode"))) {
      skewed(
          out,
          spring,
          ds,
          walDir,
          IngestFixtures.SkewedRun.beforeTrueMidnight(
              instanceId,
              Long.parseLong(p.getProperty("skew.ms")),
              Long.parseLong(p.getProperty("midnight.ms"))),
          appId);
    } else {
      batches(
          out,
          spring,
          ds,
          walDir,
          appId,
          instanceId,
          Integer.parseInt(p.getProperty("batches")),
          Integer.parseInt(p.getProperty("records")),
          Long.parseLong(p.getProperty("insert.delay.ms")));
    }
    Thread.sleep(Long.MAX_VALUE);
  }

  private static void batches(
      PrintWriter out,
      Map<String, String> spring,
      javax.sql.DataSource ds,
      Path walDir,
      int appId,
      int instanceId,
      int batches,
      int records,
      long delayMs)
      throws Exception {
    IngestStack.Options options =
        IngestStack.Options.defaults()
            .withFlush(200, 500)
            .withInserter(
                real ->
                    (table, token, data) -> {
                      Thread.sleep(delayMs);
                      real.insert(table, token, data);
                      Thread.sleep(delayMs); // between the insert and its BLOCK_COMMIT
                    });
    IngestStack stack = new IngestStack(spring, ds, walDir, options, () -> {});
    out.println("STARTED");
    for (int seq = 1; seq <= batches; seq++) {
      long now = System.currentTimeMillis();
      SessionContext s = IngestFixtures.session(appId, instanceId, now, 0, 500, "");
      List<TransactionRecord> batch = new ArrayList<>(records);
      for (int j = 0; j < records; j++) {
        batch.add(
            IngestFixtures.tx(IngestFixtures.txId(now, seq * 100 + j), now - 50, 2_000, 1, 0)
                .build());
      }
      stack.append(WalRecordType.TX, s, IngestFixtures.txBatch(batch), seq);
      out.println("ACK " + seq);
    }
    out.println("DONE");
  }

  private static void skewed(
      PrintWriter out,
      Map<String, String> spring,
      javax.sql.DataSource ds,
      Path walDir,
      IngestFixtures.SkewedRun run,
      int appId)
      throws Exception {
    Set<Table> blocked = ConcurrentHashMap.newKeySet();
    CountDownLatch everyTable = new CountDownLatch(IngestFixtures.RAW_TABLES.size());
    IngestStack.Options options =
        IngestStack.Options.defaults()
            .withFlush(60_000, 1)
            .withInserter(
                real ->
                    (table, token, data) -> {
                      // The BLOCK_INTENT is fsynced; the process is killed before the insert.
                      if (blocked.add(table)) {
                        everyTable.countDown();
                      }
                      Thread.sleep(Long.MAX_VALUE);
                    });
    IngestStack stack = new IngestStack(spring, ds, walDir, options, () -> {});
    out.println("STARTED");
    IngestFixtures.sendSkewed(stack, appId, run);
    out.println(everyTable.await(2, TimeUnit.MINUTES) ? "BLOCKED" : "TIMEOUT " + blocked);
  }

  /** {@link DriverManager} connections of one account. */
  private static final class DriverDataSource implements javax.sql.DataSource {
    private final String url;
    private final String user;
    private final String password;

    DriverDataSource(String url, String user, String password) {
      this.url = url;
      this.user = user;
      this.password = password;
    }

    @Override
    public Connection getConnection() throws SQLException {
      return DriverManager.getConnection(url, user, password);
    }

    @Override
    public Connection getConnection(String username, String pw) {
      throw new UnsupportedOperationException();
    }

    @Override
    public PrintWriter getLogWriter() {
      return null;
    }

    @Override
    public void setLogWriter(PrintWriter writer) {}

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
    public <T> T unwrap(Class<T> iface) throws SQLException {
      throw new SQLException("not a wrapper");
    }

    @Override
    public boolean isWrapperFor(Class<?> iface) {
      return false;
    }
  }

  // --- parent side --------------------------------------------------------------------------

  /**
   * The properties every child needs (parent side): the server properties of {@code env} and the
   * runtime PostgreSQL account as a password file path.
   */
  static Map<String, String> baseProperties(ServerTestEnvironment env) {
    Map<String, String> props = new LinkedHashMap<>();
    env.springProperties().forEach((k, v) -> props.put("spring." + k, v));
    props.put("pg.url", env.postgresJdbcUrl());
    props.put("pg.user", PgAccount.APP.user());
    props.put("pg.password-file", env.passwordFile(PgAccount.APP).toString());
    return props;
  }

  /**
   * Starts a child JVM on {@code properties} (parent side): {@code child.properties} and {@code
   * child.args} are written to {@code work}, the child's stderr goes to {@code
   * work/child-stderr.txt}, its stdout is the returned process's input stream.
   */
  static Process launch(Path work, Map<String, String> properties) throws IOException {
    Path file = work.resolve("child.properties");
    writeProperties(file, properties);
    // The class path is longer than a Windows command line: a JDK argument file carries it.
    Path argFile = work.resolve("child.args");
    Files.writeString(
        argFile,
        "-Xmx512m\n"
            + quoted("-Djava.io.tmpdir=" + System.getProperty("java.io.tmpdir"))
            + "\n-cp\n"
            + quoted(System.getProperty("java.class.path"))
            + "\n",
        StandardCharsets.UTF_8);
    ProcessBuilder pb =
        new ProcessBuilder(
            Path.of(System.getProperty("java.home"), "bin", "java").toString(),
            "@" + argFile,
            IngestCrashChild.class.getName(),
            file.toString());
    pb.redirectError(work.resolve("child-stderr.txt").toFile());
    return pb.start();
  }

  /** One argument of a JDK argument file, quoted (backslashes doubled). */
  private static String quoted(String value) {
    return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
  }

  /** Writes the child's properties file (parent side). */
  static void writeProperties(Path file, Map<String, String> values) throws IOException {
    Properties p = new Properties();
    p.putAll(values);
    try (var w = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
      p.store(w, "IngestCrashChild");
    }
  }
}
