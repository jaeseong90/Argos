/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.instrumentation.jdbc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.argos.agent.bootstrap.AgentControl;
import io.argos.agent.bootstrap.CallDepth;
import io.argos.agent.bootstrap.ContextStore;
import io.argos.agent.bootstrap.Kind;
import io.argos.agent.bootstrap.StepType;
import io.argos.agent.bootstrap.TestControls;
import io.argos.agent.bootstrap.TraceContext;
import io.argos.agent.core.trace.Dictionary;
import io.argos.agent.core.trace.StepBuffer;
import io.argos.agent.core.trace.TracerImpl;
import io.argos.agent.core.trace.Transaction;
import io.argos.agent.core.trace.TxIdGenerator;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The JDBC advice inlined by ByteBuddy into generated JDBC classes (pool over driver), against the
 * real tracer: one SQL step per outermost execution (D-24), normalized SQL text (SEC-35), the
 * database id without credentials, the application's results and exceptions untouched.
 */
@Tag("req:F-A03")
class JdbcAdviceTest {
  private final List<Transaction> ended =
      Collections.synchronizedList(new ArrayList<Transaction>());
  private final AgentControl control = TestControls.newControl();
  private final Dictionary dictionary = new Dictionary();
  private TracerImpl tracer;
  private int adviceErrorsBefore;

  @BeforeEach
  void installTracer() {
    tracer =
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
    control.installTracer(tracer);
    adviceErrorsBefore = io.argos.agent.bootstrap.ModuleHealth.errors(JdbcModule.MODULE_ID);
  }

  @AfterEach
  void uninstallTracer() {
    TraceContext leftover = tracer.current();
    if (leftover != null) {
      tracer.end(leftover, 0, null, null, null, System.nanoTime());
    }
    control.installTracer(null);
    assertThat(io.argos.agent.bootstrap.ModuleHealth.errors(JdbcModule.MODULE_ID))
        .as("advice errors (suppressed exceptions)")
        .isEqualTo(adviceErrorsBefore);
    assertThat(CallDepth.depth(CallDepth.JDBC_STATEMENT)).isZero();
    assertThat(CallDepth.depth(CallDepth.JDBC_CONNECTION)).isZero();
  }

  private Transaction run(Body body) throws Exception {
    TraceContext ctx = tracer.begin(Kind.HTTP, "GET /api/orders/{id}", System.nanoTime());
    try {
      body.run();
    } finally {
      tracer.end(ctx, 200, null, null, null, System.nanoTime());
    }
    assertThat(ended).hasSize(1);
    return ended.get(0);
  }

  interface Body {
    void run() throws Exception;
  }

  @Test
  @Tag("req:D-24")
  @DisplayName("pool over driver: prepareStatement + executeQuery = one SQL step, outermost only")
  void preparedStatementThroughAPool() throws Exception {
    final Connection pool = AdvisedJdbc.poolConnection(AdvisedJdbc.driverConnection(null));
    Transaction tx =
        run(
            new Body() {
              @Override
              public void run() throws Exception {
                PreparedStatement statement =
                    pool.prepareStatement("select * from orders where id = ?");
                statement.setLong(1, 1L);
                statement.executeQuery();
              }
            });
    StepBuffer steps = tx.steps();
    assertThat(steps.size()).isEqualTo(1);
    assertThat(steps.type(0)).isEqualTo(StepType.SQL);
    assertThat(dictionary.text(steps.nameId(0))).isEqualTo("select * from orders where id = ?");
    assertThat(dictionary.text(steps.targetId(0)))
        .as("credentials and parameters removed (SEC-35)")
        .isEqualTo("jdbc:h2:mem:shop");
    assertThat(steps.error(0)).isFalse();
    assertThat(steps.elapsedNanos(0)).isGreaterThanOrEqualTo(0L);
  }

  @Test
  @DisplayName("Statement.execute(String): literals never reach the dictionary")
  void plainStatementIsNormalized() throws Exception {
    final Connection pool = AdvisedJdbc.poolConnection(AdvisedJdbc.driverConnection(null));
    final PreparedStatement statement = pool.prepareStatement("select 1");
    Transaction tx =
        run(
            new Body() {
              @Override
              public void run() throws Exception {
                ((Statement) statement)
                    .executeUpdate("update customer set email = 'kim@example.test' where id = 7");
              }
            });
    StepBuffer steps = tx.steps();
    assertThat(steps.size()).isEqualTo(1);
    assertThat(dictionary.text(steps.nameId(0)))
        .isEqualTo("update customer set email = ? where id = ?");
    assertThat(dictionary.text(steps.targetId(0))).isEqualTo("jdbc:h2:mem:shop");
  }

  @Test
  @DisplayName("each execution of a prepared statement is a step; prepared outside: <unknown>")
  void repeatedExecutionsAndStatementsPreparedOutsideATransaction() throws Exception {
    final Connection pool = AdvisedJdbc.poolConnection(AdvisedJdbc.driverConnection(null));
    final PreparedStatement early = pool.prepareStatement("select * from product");
    Transaction tx =
        run(
            new Body() {
              @Override
              public void run() throws Exception {
                PreparedStatement statement =
                    pool.prepareStatement("delete from cart where id = ?");
                statement.executeUpdate();
                statement.executeUpdate();
                early.execute();
              }
            });
    StepBuffer steps = tx.steps();
    assertThat(steps.size()).isEqualTo(3);
    assertThat(dictionary.text(steps.nameId(0))).isEqualTo("delete from cart where id = ?");
    assertThat(steps.nameId(1)).isEqualTo(steps.nameId(0));
    assertThat(dictionary.text(steps.nameId(2))).isEqualTo(JdbcHelper.UNKNOWN_SQL);
    assertThat(dictionary.text(steps.targetId(2))).isEqualTo("jdbc:h2:mem:shop");
  }

  @Test
  @DisplayName("a failing execution: same exception for the application, step marked error")
  void failureIsRecordedAndRethrownUnchanged() throws Exception {
    final SQLException failure = new SQLException("relation does not exist", "42P01");
    final Connection pool = AdvisedJdbc.poolConnection(AdvisedJdbc.driverConnection(failure));
    final List<Throwable> seen = new ArrayList<Throwable>();
    Transaction tx =
        run(
            new Body() {
              @Override
              public void run() throws Exception {
                PreparedStatement statement = pool.prepareStatement("select * from missing");
                try {
                  statement.executeQuery();
                } catch (SQLException e) {
                  seen.add(e);
                }
              }
            });
    assertThat(seen).containsExactly(failure);
    StepBuffer steps = tx.steps();
    assertThat(steps.size()).isEqualTo(1);
    assertThat(steps.error(0)).isTrue();
  }

  @Test
  @DisplayName("outside a transaction: nothing recorded, results unchanged")
  void outsideATransactionNothingIsRecorded() throws Exception {
    Connection pool = AdvisedJdbc.poolConnection(AdvisedJdbc.driverConnection(null));
    long entries = ContextStore.totalEntries();
    PreparedStatement statement = pool.prepareStatement("select * from orders");
    assertThat(statement.execute()).isFalse();
    assertThat(statement.executeUpdate()).isZero();
    assertThat(ContextStore.totalEntries()).isEqualTo(entries);
    assertThat(ended).isEmpty();
  }

  @Test
  @DisplayName("getMetaData().getURL() once per connection object")
  void databaseIdIsReadOncePerConnection() throws Exception {
    final Connection pool = AdvisedJdbc.poolConnection(AdvisedJdbc.driverConnection(null));
    AdvisedJdbc.META_DATA_CALLS.set(0);
    run(
        new Body() {
          @Override
          public void run() throws Exception {
            for (int i = 0; i < 5; i++) {
              pool.prepareStatement("select " + i).executeQuery();
            }
          }
        });
    assertThat(AdvisedJdbc.META_DATA_CALLS.get()).isEqualTo(1);
    assertThat(ended.get(0).steps().size()).isEqualTo(5);
  }

  @Test
  @DisplayName("module disabled: Argos.skip returns before any work")
  void disabledModuleRecordsNothing() throws Exception {
    final Connection pool = AdvisedJdbc.poolConnection(AdvisedJdbc.driverConnection(null));
    control.setModuleState(
        JdbcModule.MODULE_ID, io.argos.agent.bootstrap.ModuleHealth.DISABLED_BY_CONFIG);
    try {
      Transaction tx =
          run(
              new Body() {
                @Override
                public void run() throws Exception {
                  pool.prepareStatement("select 1").executeQuery();
                }
              });
      assertThat(tx.steps().size()).isZero();
    } finally {
      control.setModuleState(JdbcModule.MODULE_ID, io.argos.agent.bootstrap.ModuleHealth.ENABLED);
    }
  }

  @Test
  @DisplayName("a helper failure is counted, the call still completes")
  void helperFailureIsContained() {
    assertThat(JdbcHelper.startPrepared(null, new Object())).isNull();
    assertThat(JdbcHelper.startStatement(null, null, "select 1")).isNull();
    JdbcHelper.prepared(null, null, null);
    assertThatThrownBy(
            new org.assertj.core.api.ThrowableAssert.ThrowingCallable() {
              @Override
              public void call() throws Throwable {
                AdvisedJdbc.driverStatement(null, new SQLException("boom")).executeQuery();
              }
            })
        .isInstanceOf(SQLException.class)
        .hasMessage("boom");
  }
}
