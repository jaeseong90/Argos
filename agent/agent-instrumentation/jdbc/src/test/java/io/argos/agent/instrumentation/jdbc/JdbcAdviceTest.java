/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.instrumentation.jdbc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.argos.agent.bootstrap.AgentControl;
import io.argos.agent.bootstrap.CallDepth;
import io.argos.agent.bootstrap.CaptureConfig;
import io.argos.agent.bootstrap.ContextStore;
import io.argos.agent.bootstrap.DictKind;
import io.argos.agent.bootstrap.Kind;
import io.argos.agent.bootstrap.ModuleHealth;
import io.argos.agent.bootstrap.StepType;
import io.argos.agent.bootstrap.TestControls;
import io.argos.agent.bootstrap.TraceContext;
import io.argos.agent.core.trace.Dictionary;
import io.argos.agent.core.trace.StepBuffer;
import io.argos.agent.core.trace.TracerImpl;
import io.argos.agent.core.trace.Transaction;
import io.argos.agent.core.trace.TxIdGenerator;
import java.io.ByteArrayInputStream;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The JDBC advice inlined by ByteBuddy into generated JDBC classes (routing data sources over a
 * pool over a driver wrapper over a driver), against the real tracer: one SQL step per outermost
 * execution (D-24), DBC, COMMIT and ROLLBACK steps, batch sizes, exec and fetch time apart, bind
 * parameter modes, counting outside transactions, normalized SQL (SEC-35), the target without
 * credentials, the application's results and exceptions untouched.
 */
@Tag("req:F-A03")
class JdbcAdviceTest {
  private final List<Transaction> ended =
      Collections.synchronizedList(new ArrayList<Transaction>());
  private final AgentControl control = TestControls.newControl();
  private final Dictionary dictionary = new Dictionary();
  private TracerImpl core;
  private TestTracer tracer;
  private int adviceErrorsBefore;
  private AdvisedJdbc.Driver driver;

  @BeforeEach
  void installTracer() {
    core =
        new TracerImpl(
            dictionary,
            new TracerImpl.Sink() {
              @Override
              public void accept(Transaction tx) {
                ended.add(tx);
              }
            },
            control,
            new TxIdGenerator(),
            1000,
            100);
    tracer = new TestTracer(core);
    control.installTracer(tracer);
    adviceErrorsBefore = ModuleHealth.errors(JdbcModule.MODULE_ID);
    driver = new AdvisedJdbc.Driver();
  }

  @AfterEach
  void uninstallTracer() {
    TraceContext leftover = core.current();
    if (leftover != null) {
      core.end(leftover, 0, null, null, null, System.nanoTime());
    }
    control.installTracer(null);
    assertThat(ModuleHealth.errors(JdbcModule.MODULE_ID))
        .as("advice errors (suppressed exceptions)")
        .isEqualTo(adviceErrorsBefore);
    assertThat(CallDepth.depth(CallDepth.JDBC_STATEMENT)).isZero();
    assertThat(CallDepth.depth(CallDepth.JDBC_CONNECTION)).isZero();
    assertThat(CallDepth.depth(CallDepth.JDBC_DATASOURCE)).isZero();
    assertThat(CallDepth.value(CallDepth.JDBC_DATASOURCE)).isZero();
  }

  private Transaction run(Body body) throws Exception {
    int before = ended.size();
    TraceContext ctx = core.begin(Kind.HTTP, "GET /api/orders/{id}", System.nanoTime());
    try {
      body.run(ctx);
    } finally {
      core.end(ctx, 200, null, null, null, System.nanoTime());
    }
    assertThat(ended).hasSize(before + 1);
    return ended.get(before);
  }

  interface Body {
    void run(TraceContext ctx) throws Exception;
  }

  /** Routing over routing over a pool over a driver wrapper over the driver: five data sources. */
  private DataSource fiveLayers() {
    DataSource pool = AdvisedJdbc.pool(AdvisedJdbc.pool(AdvisedJdbc.driver(driver)));
    tracer.pool(pool, "HikariPool-orders");
    return AdvisedJdbc.routing(AdvisedJdbc.routing(pool));
  }

  private String text(long id) {
    return dictionary.text(id);
  }

  private static List<Integer> types(StepBuffer steps) {
    List<Integer> types = new ArrayList<Integer>();
    for (int i = 0; i < steps.size(); i++) {
      types.add(steps.type(i));
    }
    return types;
  }

  @Test
  @Tag("req:D-24")
  @DisplayName(
      "T-AG-031a five data source layers and three connection layers: one DBC step named after"
          + " the nested pool, one SQL step, one COMMIT step, sql_count 1, getMetaData once")
  void oneStepThroughFiveLayers() throws Exception {
    final DataSource dataSource = fiveLayers();
    final int[] rows = {0};
    Transaction tx =
        run(
            new Body() {
              @Override
              public void run(TraceContext ctx) throws Exception {
                Connection connection = dataSource.getConnection();
                PreparedStatement statement =
                    connection.prepareStatement("select * from orders where id = ?");
                statement.setLong(1, 42L);
                ResultSet resultSet = statement.executeQuery();
                while (resultSet.next()) {
                  rows[0]++;
                }
                resultSet.close();
                connection.commit();
                assertThat(ctx.sqlCount).as("sql_count").isEqualTo(1);
                assertThat(ctx.dbcNanos).as("dbc_wait").isPositive();
              }
            });
    assertThat(rows[0]).isEqualTo(3);
    StepBuffer steps = tx.steps();
    assertThat(types(steps)).containsExactly(StepType.DBC, StepType.SQL, StepType.COMMIT);
    assertThat(text(steps.nameId(0))).isEqualTo("HikariPool-orders");
    assertThat(dictionary.kind(steps.nameId(0))).isEqualTo(DictKind.POOL);
    assertThat(text(steps.nameId(1))).isEqualTo("select * from orders where id = ?");
    assertThat(text(steps.targetId(1))).as("credentials removed").isEqualTo(AdvisedJdbc.TARGET);
    assertThat(steps.rows(1)).isEqualTo(3);
    assertThat(steps.error(1)).isFalse();
    assertThat(text(steps.nameId(2))).isEqualTo(AdvisedJdbc.TARGET);
    assertThat(steps.targetId(2)).isEqualTo(steps.targetId(1));
    assertThat(driver.metaDataCalls.get()).isEqualTo(1);
    assertThat(driver.executed).hasSize(1);
    assertThat(tracer.sqlParamCalls.get()).as("masked: set* never reaches the core").isZero();
    assertThat(steps.detail(1)).isNull();

    // The next connection of the same pool: the DBC step knows the target now (C-143 (4)).
    Transaction second =
        run(
            new Body() {
              @Override
              public void run(TraceContext ctx) throws Exception {
                dataSource.getConnection().prepareStatement("select 1").execute();
              }
            });
    assertThat(second.steps().type(0)).isEqualTo(StepType.DBC);
    assertThat(text(second.steps().targetId(0))).isEqualTo(AdvisedJdbc.TARGET);
  }

  @Test
  @DisplayName(
      "T-AG-032b DBC errors, ROLLBACK and failing COMMIT steps, executeBatch with batch_size,"
          + " a plain statement batch named after its first SQL; pool SQL inside getConnection"
          + " is not recorded")
  void dbcCommitRollbackAndBatches() throws Exception {
    final DataSource dataSource = fiveLayers();
    driver.validationQuery = "select 1 from dual where 'validation' = 'validation'";
    Transaction tx =
        run(
            new Body() {
              @Override
              public void run(TraceContext ctx) throws Exception {
                Connection connection = dataSource.getConnection();
                PreparedStatement insert =
                    connection.prepareStatement("insert into item (id, name) values (?, ?)");
                for (int i = 0; i < 3; i++) {
                  insert.setInt(1, i);
                  insert.setString(2, "name" + i);
                  insert.addBatch();
                }
                insert.executeBatch();
                Statement plain = connection.createStatement();
                plain.addBatch("delete from cart where id = 7");
                plain.addBatch("delete from wish where id = 8");
                plain.executeBatch();
                plain.addBatch("delete from cart where id = 9");
                plain.clearBatch();
                plain.executeLargeBatch();
                connection.rollback();
                driver.commitFailure = new SQLException("commit failed");
                try {
                  connection.commit();
                } catch (SQLException expected) {
                  // the application's exception, unchanged
                }
                driver.connectFailure = new SQLException("pool exhausted");
                try {
                  dataSource.getConnection();
                } catch (SQLException expected) {
                  assertThat(expected.getMessage()).isEqualTo("pool exhausted");
                }
              }
            });
    StepBuffer steps = tx.steps();
    assertThat(types(steps))
        .containsExactly(
            StepType.DBC,
            StepType.SQL,
            StepType.SQL,
            StepType.SQL,
            StepType.ROLLBACK,
            StepType.COMMIT,
            StepType.DBC);
    assertThat(text(steps.nameId(1))).isEqualTo("insert into item (id, name) values (?, ?)");
    assertThat(steps.batchSize(1)).isEqualTo(3);
    assertThat(text(steps.nameId(2))).isEqualTo("delete from cart where id = ?");
    assertThat(steps.batchSize(2)).isEqualTo(2);
    assertThat(steps.batchSize(3)).as("clearBatch reset the count").isZero();
    assertThat(steps.error(4)).isFalse();
    assertThat(steps.error(5)).as("failing commit").isTrue();
    assertThat(steps.error(6)).as("failing getConnection").isTrue();
    assertThat(text(steps.nameId(6))).isEqualTo("HikariPool-orders");
    assertThat(driver.executed).as("the validation query ran").contains(driver.validationQuery);
    for (int i = 0; i < steps.size(); i++) {
      assertThat(text(steps.nameId(i))).doesNotContain("validation");
    }
  }

  @Test
  @DisplayName(
      "T-AG-032e a lazy proxy connection (routing over a transaction-aware proxy) is no DBC step:"
          + " the pool acquisition at its first use is the one DBC step; a registered pool that"
          + " hands out JDK proxies keeps its DBC step; the driver's commit() inside"
          + " setAutoCommit(true) is no COMMIT step")
  void lazyConnectionsAndAutoCommitRestore() throws Exception {
    DataSource pool = AdvisedJdbc.pool(AdvisedJdbc.pool(AdvisedJdbc.driver(driver)));
    tracer.pool(pool, "HikariPool-orders");
    final DataSource dataSource = AdvisedJdbc.routing(AdvisedJdbc.lazy(pool));
    driver.commitInSetAutoCommit = true;
    final long[] dbcBeforeUse = {-1L};
    Transaction rolledBack =
        run(
            new Body() {
              @Override
              public void run(TraceContext ctx) throws Exception {
                Connection connection = dataSource.getConnection();
                dbcBeforeUse[0] = ctx.dbcNanos;
                connection.setAutoCommit(false);
                connection.prepareStatement("insert into audit (id) values (?)").executeUpdate();
                connection.rollback();
                connection.setAutoCommit(true);
              }
            });
    assertThat(dbcBeforeUse[0]).as("dbc_wait of the lazy handle").isZero();
    StepBuffer steps = rolledBack.steps();
    assertThat(types(steps)).containsExactly(StepType.DBC, StepType.SQL, StepType.ROLLBACK);
    assertThat(text(steps.nameId(0))).isEqualTo("HikariPool-orders");
    assertThat(driver.commits.get()).as("the driver's own commit() ran").isEqualTo(1);

    Transaction committed =
        run(
            new Body() {
              @Override
              public void run(TraceContext ctx) throws Exception {
                Connection connection = dataSource.getConnection();
                connection.setAutoCommit(false);
                connection.prepareStatement("insert into audit (id) values (?)").executeUpdate();
                connection.commit();
                connection.setAutoCommit(true);
              }
            });
    assertThat(types(committed.steps()))
        .containsExactly(StepType.DBC, StepType.SQL, StepType.COMMIT);
    assertThat(driver.commits.get()).isEqualTo(3);

    Transaction unused =
        run(
            new Body() {
              @Override
              public void run(TraceContext ctx) throws Exception {
                dataSource.getConnection();
                assertThat(ctx.dbcNanos).isZero();
              }
            });
    assertThat(unused.steps().size()).as("a lazy handle never used: no step").isZero();

    final DataSource tomcatLike = AdvisedJdbc.proxying(AdvisedJdbc.driver(driver));
    tracer.pool(tomcatLike, "Tomcat Connection Pool[1-42]");
    Transaction proxied =
        run(
            new Body() {
              @Override
              public void run(TraceContext ctx) throws Exception {
                tomcatLike.getConnection().prepareStatement("select 1").execute();
                assertThat(ctx.dbcNanos).isPositive();
              }
            });
    assertThat(types(proxied.steps())).containsExactly(StepType.DBC, StepType.SQL);
    assertThat(text(proxied.steps().nameId(0))).isEqualTo("Tomcat Connection Pool[1-42]");
  }

  @Test
  @DisplayName(
      "T-AG-030a execution and fetch apart: exec time on the step, rows and fetch time added at"
          + " close, at the next execution, and for execute() + getResultSet()")
  void executionAndFetchApart() throws Exception {
    final DataSource dataSource = fiveLayers();
    driver.executeMillis = 20;
    driver.nextMillis = 10;
    Transaction tx =
        run(
            new Body() {
              @Override
              public void run(TraceContext ctx) throws Exception {
                Connection connection = dataSource.getConnection();
                PreparedStatement query = connection.prepareStatement("select * from orders");
                ResultSet first = query.executeQuery();
                while (first.next()) {
                  // read every row
                }
                first.close();
                first.close();
                ResultSet second = query.executeQuery();
                second.next();
                // not closed: the next execution reports it
                query.executeQuery();
                PreparedStatement mapper = connection.prepareStatement("select * from item");
                mapper.execute();
                ResultSet rows = mapper.getResultSet();
                rows.next();
                rows.next();
                rows.close();
              }
            });
    StepBuffer steps = tx.steps();
    assertThat(types(steps))
        .containsExactly(StepType.DBC, StepType.SQL, StepType.SQL, StepType.SQL, StepType.SQL);
    long millis = 1_000_000L;
    assertThat(steps.elapsedNanos(1)).as("exec time").isGreaterThanOrEqualTo(15 * millis);
    assertThat(steps.rows(1)).isEqualTo(3);
    assertThat(steps.fetchNanos(1))
        .as("fetch time, 4 next() calls")
        .isGreaterThanOrEqualTo(30 * millis);
    assertThat(steps.rows(2)).as("reported at the next execution").isEqualTo(1);
    assertThat(steps.fetchNanos(2)).isGreaterThanOrEqualTo(5 * millis);
    assertThat(steps.rows(3)).as("never read").isZero();
    assertThat(steps.rows(4)).as("execute() + getResultSet()").isEqualTo(2);
  }

  @Test
  @Tag("req:D-20")
  @DisplayName(
      "T-AG-036a outside a transaction: no step, no state, one orphan count and one"
          + " sql_outside_tx_total per outermost execution through three layers")
  void outsideATransactionOnlyCounted() throws Exception {
    DataSource dataSource = fiveLayers();
    long entries = ContextStore.totalEntries();
    long outside = core.counters().sqlOutsideTx();
    long orphans = core.counters().orphanSteps(StepType.SQL);
    Connection connection = dataSource.getConnection();
    PreparedStatement statement = connection.prepareStatement("select * from orders");
    assertThat(statement.execute()).isTrue();
    assertThat(statement.executeUpdate()).isEqualTo(1);
    connection.createStatement().executeQuery("select 2");
    assertThat(core.counters().sqlOutsideTx() - outside).isEqualTo(3);
    assertThat(core.counters().orphanSteps(StepType.SQL) - orphans).isEqualTo(3);
    assertThat(ContextStore.totalEntries()).isEqualTo(entries);
    assertThat(ended).isEmpty();
    assertThat(driver.executed).hasSize(3);
  }

  @Test
  @Tag("req:F-A04")
  @Tag("req:SEC-3A")
  @DisplayName(
      "T-AG-033b bind parameters through the advice: masked keeps nothing, partial and full write"
          + " the detail after the masker, the first set of a batch; streams and LOBs untouched")
  void bindParameterModes() throws Exception {
    final DataSource dataSource = fiveLayers();
    final String sql = "update member set email = ?, phone = ?, photo = ? where id = ?";
    final RecordingStream photo = new RecordingStream();
    Body body =
        new Body() {
          @Override
          public void run(TraceContext ctx) throws Exception {
            PreparedStatement statement = dataSource.getConnection().prepareStatement(sql);
            statement.setString(1, "kim.argos_seed@example.test");
            statement.setString(2, "010-1234-5678");
            statement.setBinaryStream(3, photo);
            statement.setLong(4, 42L);
            statement.executeUpdate();
            statement.setString(1, "first@example.test");
            statement.setNull(2, java.sql.Types.VARCHAR);
            statement.addBatch();
            statement.setString(1, "second@example.test");
            statement.addBatch();
            statement.executeBatch();
          }
        };
    Transaction masked = run(body);
    assertThat(masked.steps().detail(1)).isNull();
    assertThat(tracer.sqlParamCalls.get()).isZero();

    core.setCapture(
        CaptureConfig.builder()
            .sqlParamMode(CaptureConfig.SQL_PARAMS_PARTIAL)
            .sqlParamWhitelistColumns(new String[] {"phone", "id"})
            .build());
    Transaction partial = run(body);
    assertThat(partial.steps().detail(1))
        .isEqualTo("1=[String(27)], 2='<phone>', 3=<RecordingStream>, 4=42");
    assertThat(partial.steps().detail(2))
        .as("first set of the batch")
        .isEqualTo("1=[String(18)], 2=NULL");

    core.setCapture(CaptureConfig.builder().sqlParamMode(CaptureConfig.SQL_PARAMS_FULL).build());
    Transaction full = run(body);
    assertThat(full.steps().detail(1))
        .isEqualTo("1='<email>', 2='<phone>', 3=<RecordingStream>, 4=42")
        .doesNotContain("argos_seed");
    assertThat(full.steps().detail(2)).isEqualTo("1='<email>', 2=NULL");
    core.setCapture(null);

    assertThat(photo.calls).as("calls on the stream").isZero();
    assertThat(driver.setValues).as("the driver still got the values").contains(photo, 42L);
  }

  @Test
  @DisplayName(
      "T-AG-032b summary mode (10 §9.1, C-141): SQL steps past max-steps fold into one SQL summary"
          + " entry; fetch, batch and detail of a summary handle are ignored, nothing fails")
  void summaryMode() throws Exception {
    final DataSource dataSource = fiveLayers();
    core.setMaxSteps(2);
    core.setCapture(CaptureConfig.builder().sqlParamMode(CaptureConfig.SQL_PARAMS_FULL).build());
    try {
      Transaction tx =
          run(
              new Body() {
                @Override
                public void run(TraceContext ctx) throws Exception {
                  Connection connection = dataSource.getConnection();
                  PreparedStatement query =
                      connection.prepareStatement("select * from orders where id = ?");
                  for (int i = 0; i < 5; i++) {
                    query.setInt(1, i);
                    ResultSet rows = query.executeQuery();
                    while (rows.next()) {
                      // read
                    }
                    rows.close();
                  }
                  query.addBatch();
                  query.executeBatch();
                  assertThat(ctx.sqlCount).isEqualTo(6);
                }
              });
      StepBuffer steps = tx.steps();
      assertThat(types(steps)).containsExactly(StepType.DBC, StepType.SQL);
      assertThat(steps.rows(1)).isEqualTo(3);
      assertThat(steps.summarized()).isTrue();
      assertThat(steps.summaryKeys()).isEqualTo(1);
      assertThat(steps.summaryType(0)).isEqualTo(StepType.SQL);
      assertThat(steps.summaryNameId(0)).isEqualTo(steps.nameId(1));
      assertThat(steps.summaryCount(0)).isEqualTo(5);
      assertThat(steps.totalSteps()).isEqualTo(7);
    } finally {
      core.setMaxSteps(1000);
      core.setCapture(null);
    }
  }

  @Test
  @Tag("req:D-24")
  @DisplayName("pool over driver: prepareStatement + executeQuery = one SQL step, outermost only")
  void preparedStatementThroughAPool() throws Exception {
    final Connection pool = AdvisedJdbc.pool(AdvisedJdbc.driverConnection(driver));
    Transaction tx =
        run(
            new Body() {
              @Override
              public void run(TraceContext ctx) throws Exception {
                PreparedStatement statement =
                    pool.prepareStatement("select * from orders where id = ?");
                statement.setLong(1, 1L);
                statement.executeQuery();
              }
            });
    StepBuffer steps = tx.steps();
    assertThat(steps.size()).isEqualTo(1);
    assertThat(steps.type(0)).isEqualTo(StepType.SQL);
    assertThat(text(steps.nameId(0))).isEqualTo("select * from orders where id = ?");
    assertThat(text(steps.targetId(0)))
        .as("credentials and parameters removed (SEC-35)")
        .isEqualTo(AdvisedJdbc.TARGET);
    assertThat(steps.error(0)).isFalse();
    assertThat(steps.elapsedNanos(0)).isGreaterThanOrEqualTo(0L);
  }

  @Test
  @DisplayName("Statement.execute(String): literals never reach the dictionary")
  void plainStatementIsNormalized() throws Exception {
    final Connection pool = AdvisedJdbc.pool(AdvisedJdbc.driverConnection(driver));
    final Statement statement = pool.createStatement();
    Transaction tx =
        run(
            new Body() {
              @Override
              public void run(TraceContext ctx) throws Exception {
                statement.executeUpdate(
                    "update customer set email = 'kim@example.test' where id = 7");
              }
            });
    StepBuffer steps = tx.steps();
    assertThat(steps.size()).isEqualTo(1);
    assertThat(text(steps.nameId(0))).isEqualTo("update customer set email = ? where id = ?");
    assertThat(text(steps.targetId(0))).isEqualTo(AdvisedJdbc.TARGET);
  }

  @Test
  @DisplayName("each execution of a prepared statement is a step; prepared outside: <unknown>")
  void repeatedExecutionsAndStatementsPreparedOutsideATransaction() throws Exception {
    final Connection pool = AdvisedJdbc.pool(AdvisedJdbc.driverConnection(driver));
    final PreparedStatement early = pool.prepareStatement("select * from product");
    Transaction tx =
        run(
            new Body() {
              @Override
              public void run(TraceContext ctx) throws Exception {
                PreparedStatement statement =
                    pool.prepareStatement("delete from cart where id = ?");
                statement.executeUpdate();
                statement.executeUpdate();
                early.execute();
              }
            });
    StepBuffer steps = tx.steps();
    assertThat(steps.size()).isEqualTo(3);
    assertThat(text(steps.nameId(0))).isEqualTo("delete from cart where id = ?");
    assertThat(steps.nameId(1)).isEqualTo(steps.nameId(0));
    assertThat(text(steps.nameId(2))).isEqualTo(JdbcHelper.UNKNOWN_SQL);
    assertThat(text(steps.targetId(2))).isEqualTo(AdvisedJdbc.TARGET);
  }

  @Test
  @DisplayName("a failing execution: same exception for the application, step marked error")
  void failureIsRecordedAndRethrownUnchanged() throws Exception {
    final SQLException failure = new SQLException("relation does not exist", "42P01");
    driver.executeFailure = failure;
    final Connection pool = AdvisedJdbc.pool(AdvisedJdbc.driverConnection(driver));
    final List<Throwable> seen = new ArrayList<Throwable>();
    Transaction tx =
        run(
            new Body() {
              @Override
              public void run(TraceContext ctx) throws Exception {
                PreparedStatement statement = pool.prepareStatement("select * from missing");
                try {
                  statement.executeQuery();
                } catch (SQLException e) {
                  seen.add(e);
                }
                assertThat(ctx.sqlCount).isEqualTo(1);
              }
            });
    assertThat(seen).containsExactly(failure);
    StepBuffer steps = tx.steps();
    assertThat(steps.size()).isEqualTo(1);
    assertThat(steps.error(0)).isTrue();
  }

  @Test
  @DisplayName("getMetaData().getURL() once per connection object")
  void databaseIdIsReadOncePerConnection() throws Exception {
    final Connection pool = AdvisedJdbc.pool(AdvisedJdbc.driverConnection(driver));
    run(
        new Body() {
          @Override
          public void run(TraceContext ctx) throws Exception {
            for (int i = 0; i < 5; i++) {
              pool.prepareStatement("select " + i).executeQuery();
            }
            pool.commit();
          }
        });
    assertThat(driver.metaDataCalls.get()).isEqualTo(1);
    assertThat(tracer.dbIdCalls.get()).isEqualTo(1);
    assertThat(ended.get(0).steps().size()).isEqualTo(6);
  }

  @Test
  @DisplayName("module disabled: Argos.skip returns before any work")
  void disabledModuleRecordsNothing() throws Exception {
    final DataSource dataSource = fiveLayers();
    control.setModuleState(JdbcModule.MODULE_ID, ModuleHealth.DISABLED_BY_CONFIG);
    try {
      long outside = core.counters().sqlOutsideTx();
      Transaction tx =
          run(
              new Body() {
                @Override
                public void run(TraceContext ctx) throws Exception {
                  dataSource.getConnection().prepareStatement("select 1").executeQuery();
                }
              });
      assertThat(tx.steps().size()).isZero();
      assertThat(core.counters().sqlOutsideTx()).isEqualTo(outside);
    } finally {
      control.setModuleState(JdbcModule.MODULE_ID, ModuleHealth.ENABLED);
    }
  }

  @Test
  @DisplayName("a transaction ended by the agent (max-duration) gets no more steps or counts")
  void endedTransactionGetsNothing() throws Exception {
    final DataSource dataSource = fiveLayers();
    final TraceContext ctx = core.begin(Kind.HTTP, "GET /slow", System.nanoTime());
    // MaxDurationEnforcer ends it on its own thread; the request thread keeps the ended context
    // bound (10 §7.1, WO-12 hand-over).
    final boolean[] won = new boolean[1];
    Thread watch =
        new Thread(
            new Runnable() {
              @Override
              public void run() {
                won[0] = core.endTimedOut(ctx, System.nanoTime());
              }
            });
    watch.start();
    watch.join();
    assertThat(won[0]).isTrue();
    assertThat(core.current()).isSameAs(ctx);
    Connection connection = dataSource.getConnection();
    long outside = core.counters().sqlOutsideTx();
    connection.prepareStatement("select 1").executeQuery();
    connection.commit();
    assertThat(ctx.sqlCount).isZero();
    assertThat(ctx.dbcNanos).isZero();
    assertThat(core.counters().sqlOutsideTx() - outside).as("dropped and counted").isEqualTo(1);
    assertThat(ended).hasSize(1);
    assertThat(ended.get(0).steps().size()).isZero();
  }

  @Test
  @DisplayName("a helper failure is counted, the call still completes")
  void helperFailureIsContained() {
    TraceContext ctx = core.begin(Kind.HTTP, "GET /x", System.nanoTime());
    try {
      assertThat(JdbcHelper.executeStart(ctx, new Object(), null, "execute")).isNull();
      JdbcHelper.prepared(ctx, null, null, null);
      JdbcHelper.parameter(new Object(), 1, "x", "setString");
      JdbcHelper.statementCall(new Object(), "addBatch");
      JdbcHelper.resultSetOf(null, null);
      assertThat(JdbcHelper.fetchState(new Object())).isNull();
      JdbcHelper.resultSetClosed(new Object());
    } finally {
      core.end(ctx, 200, null, null, null, System.nanoTime());
    }
    assertThatThrownBy(
            new org.assertj.core.api.ThrowableAssert.ThrowingCallable() {
              @Override
              public void call() throws Throwable {
                driver.executeFailure = new SQLException("boom");
                AdvisedJdbc.driverConnection(driver).prepareStatement("x").executeQuery();
              }
            })
        .isInstanceOf(SQLException.class)
        .hasMessage("boom");
  }

  /** An input stream that counts every call; the agent must make none. */
  static final class RecordingStream extends ByteArrayInputStream {
    int calls;

    RecordingStream() {
      super(new byte[] {1, 2, 3});
    }

    @Override
    public synchronized int read() {
      calls++;
      return super.read();
    }

    @Override
    public synchronized int read(byte[] b, int off, int len) {
      calls++;
      return super.read(b, off, len);
    }

    @Override
    public synchronized int available() {
      calls++;
      return super.available();
    }

    @Override
    public String toString() {
      calls++;
      return "photo";
    }

    @Override
    public boolean equals(Object other) {
      calls++;
      return this == other;
    }

    @Override
    public int hashCode() {
      calls++;
      return 1;
    }
  }
}
