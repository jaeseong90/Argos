/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.instrumentation.jdbc;

import io.argos.agent.bootstrap.Argos;
import io.argos.agent.bootstrap.CallDepth;
import io.argos.agent.bootstrap.CaptureConfig;
import io.argos.agent.bootstrap.ContextStore;
import io.argos.agent.bootstrap.DbInfo;
import io.argos.agent.bootstrap.DictKind;
import io.argos.agent.bootstrap.FetchState;
import io.argos.agent.bootstrap.SqlState;
import io.argos.agent.bootstrap.StepType;
import io.argos.agent.bootstrap.TraceContext;
import io.argos.agent.bootstrap.Tracer;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.Statement;
import javax.sql.DataSource;

/**
 * The work of the JDBC advice (10 §6.3), injected into the class loader of the JDBC classes (10
 * §5.4): bootstrap API, {@code java.sql} and {@code javax.sql} only, no state of its own. No method
 * throws: a failure is counted against the module ({@code Tracer.moduleError}) and the application
 * call runs unrecorded, so the advice can always pair its {@code CallDepth} calls.
 *
 * <p>State lives in {@link ContextStore}s with bootstrap values only (10 §5.3 A-9): {@code
 * Statement -> SqlState}, {@code ResultSet -> FetchState}, {@code Connection -> DbInfo} and {@code
 * DataSource -> DbInfo}. The raw SQL of a prepared statement is held by reference until its first
 * execution normalizes it ({@link Tracer#sqlId}); the helper never logs, cuts, copies or sends it
 * (SEC-35, DSN-10-17).
 *
 * <p>JDBC calls the agent makes on application objects: {@code Connection.getMetaData().getURL()}
 * at most once per connection object (10 §6.3 DbInfo, DSN-10-06) and {@code
 * Statement.getConnection()} for a plain statement without a prepared state, both under a {@code
 * CallDepth} guard with exceptions contained. Nothing else: no validation query, no catalog read,
 * no {@code toString()} of a bind value, no stream or LOB access.
 */
public final class JdbcHelper {
  /** Step name of a prepared statement whose SQL was not seen (prepared outside a transaction). */
  static final String UNKNOWN_SQL = "<unknown prepared statement>";

  private JdbcHelper() {}

  private static ContextStore<Statement, SqlState> statements() {
    return ContextStore.of(Statement.class, SqlState.class);
  }

  private static ContextStore<ResultSet, FetchState> results() {
    return ContextStore.of(ResultSet.class, FetchState.class);
  }

  private static ContextStore<Connection, DbInfo> connections() {
    return ContextStore.of(Connection.class, DbInfo.class);
  }

  private static ContextStore<DataSource, DbInfo> dataSources() {
    return ContextStore.of(DataSource.class, DbInfo.class);
  }

  // --- DataSource.getConnection: DBC step ------------------------------------------------------

  /**
   * Enter of the outermost {@code getConnection} of a transaction: clears the pool name a nested
   * call may hand up ({@code CallDepth} value slot), marks the transaction as waiting for a
   * connection for the active snapshot ({@code Tracer.connectionWait}, C-225 (1)) and returns the
   * start time.
   */
  public static long connectionStart() {
    CallDepth.setValue(CallDepth.JDBC_DATASOURCE, 0L);
    try {
      Tracer tracer = Argos.tracer();
      tracer.connectionWait(tracer.current(), true);
    } catch (Throwable t) {
      Argos.tracer().moduleError(JdbcModule.MODULE_ID, t);
    }
    return System.nanoTime();
  }

  /**
   * Exit of a {@code getConnection} call at nesting {@code depth} (0: the outermost one). Every
   * level links the returned connection to the data source that handed it out first (the innermost
   * one, {@code DbInfo.source}); a nested level hands its pool name up; the outermost level writes
   * the DBC step (ADR-0010 C-101, C-143 (4): {@code name_id} = the POOL id of the innermost known
   * pool, else of the outermost data source, else its class name; {@code target_id} = the target
   * last read for a connection of that data source, 0 when none yet) and adds its time to {@code
   * dbc_wait_ms}.
   *
   * <p>A JDK proxy connection handed out by a chain without a known pool is a lazy handle, not an
   * acquisition: Spring's {@code TransactionAwareDataSourceProxy} and {@code
   * LazyConnectionDataSourceProxy} return one without asking the data source below, which they do
   * at the proxy's first use, in a later outermost {@code getConnection} that writes the DBC step
   * (10 §6.3: one DBC step through a routing data source). The handle gets no step and no {@code
   * dbc_wait_ms}. A registered pool that hands out JDK proxies (Tomcat JDBC) is known by name, so
   * its calls stay DBC steps; an unregistered one that does would lose its DBC steps (none of the
   * supported pools of 10 §6.4).
   */
  public static void connectionEnd(
      TraceContext context,
      Object dataSource,
      Object connection,
      Throwable error,
      int depth,
      long startNanos) {
    try {
      Tracer tracer = Argos.tracer();
      if (error == null && connection instanceof Connection && dataSource instanceof DataSource) {
        link((Connection) connection, (DataSource) dataSource);
      }
      if (depth > 0) {
        if (CallDepth.value(CallDepth.JDBC_DATASOURCE) == 0) {
          CallDepth.setValue(CallDepth.JDBC_DATASOURCE, tracer.poolNameId(dataSource));
        }
        return;
      }
      long end = System.nanoTime();
      tracer.connectionWait(context, false);
      long nameId = CallDepth.value(CallDepth.JDBC_DATASOURCE);
      CallDepth.setValue(CallDepth.JDBC_DATASOURCE, 0L);
      if (context.isEnded()) {
        return;
      }
      if (nameId == 0) {
        nameId = tracer.poolNameId(dataSource);
      }
      if (nameId == 0 && error == null && connection instanceof java.lang.reflect.Proxy) {
        return;
      }
      if (nameId == 0 && dataSource != null) {
        nameId = tracer.dict(DictKind.POOL, dataSource.getClass().getName());
      }
      long target = 0L;
      if (connection instanceof Connection) {
        DbInfo info = connections().get((Connection) connection);
        target = info == null ? 0L : info.knownTarget();
      }
      int seq = tracer.stepStart(context, StepType.DBC, nameId, target, startNanos);
      tracer.stepEnd(context, seq, 0, error != null, 0L, end);
      context.addDbcWait(end - startNanos);
    } catch (Throwable t) {
      Argos.tracer().moduleError(JdbcModule.MODULE_ID, t);
    }
  }

  /** Gives {@code connection} a {@code DbInfo} whose source is the one of {@code dataSource}. */
  private static void link(Connection connection, DataSource dataSource) {
    ContextStore<Connection, DbInfo> store = connections();
    if (store.get(connection) != null) {
      return;
    }
    ContextStore<DataSource, DbInfo> sources = dataSources();
    DbInfo source = sources.get(dataSource);
    if (source == null) {
      source = new DbInfo();
      sources.put(dataSource, source);
    }
    store.put(connection, new DbInfo(source));
  }

  // --- Connection: prepare, commit, rollback ---------------------------------------------------

  /**
   * Exit of the outermost {@code prepareStatement}/{@code prepareCall}: the statement's state holds
   * the raw SQL by reference (normalized at its first execution) and the connection's target.
   */
  public static void prepared(
      TraceContext context, Object connection, Object statement, String sql) {
    try {
      if (!(statement instanceof Statement) || sql == null || context.isEnded()) {
        return;
      }
      long dbId =
          connection instanceof Connection ? dbId(Argos.tracer(), (Connection) connection) : 0L;
      statements().put((Statement) statement, new SqlState(sql, dbId));
    } catch (Throwable t) {
      Argos.tracer().moduleError(JdbcModule.MODULE_ID, t);
    }
  }

  /**
   * Exit of the outermost {@code commit()} or {@code rollback()}: a COMMIT or ROLLBACK step (C-1)
   * whose name and target are the connection's {@code DB_TARGET} id; error flag when it threw.
   */
  public static void transactionEnd(
      TraceContext context, Object connection, String method, long startNanos, Throwable error) {
    try {
      if (context.isEnded()) {
        return;
      }
      Tracer tracer = Argos.tracer();
      long dbId = connection instanceof Connection ? dbId(tracer, (Connection) connection) : 0L;
      int type = "rollback".equals(method) ? StepType.ROLLBACK : StepType.COMMIT;
      long end = System.nanoTime();
      int seq = tracer.stepStart(context, type, dbId, dbId, startNanos);
      tracer.stepEnd(context, seq, 0, error != null, 0L, end);
    } catch (Throwable t) {
      Argos.tracer().moduleError(JdbcModule.MODULE_ID, t);
    }
  }

  // --- Statement executions --------------------------------------------------------------------

  /**
   * Enter of the outermost execution. Without a running transaction: counted ({@code
   * orphan_steps_total{type=sql}}, {@code sql_outside_tx_total}, DSN-10-07, D-20) and null.
   * Otherwise: the rows of the statement's previous result set go to its step first (10 §6.3 "다음
   * execute"), then the SQL step opens: name = the normalized SQL id ({@code sql}, the prepared
   * SQL, or the first batch text of a plain statement; {@value #UNKNOWN_SQL} for a statement
   * prepared outside a transaction), target = the connection's target. Returns the statement's
   * state with the execution in it, or null.
   */
  public static SqlState executeStart(
      TraceContext context, Object statement, String sql, String method) {
    try {
      Tracer tracer = Argos.tracer();
      if (context == null || context.isEnded()) {
        tracer.countOrphan(StepType.SQL);
        return null;
      }
      if (!(statement instanceof Statement)) {
        return null;
      }
      SqlState state = state(tracer, (Statement) statement);
      if (state == null) {
        return null;
      }
      long now = System.nanoTime();
      FetchState previous = state.fetch;
      if (previous != null) {
        state.fetch = null;
        report(tracer, context, previous, now);
      }
      long sqlId;
      if (sql != null) {
        sqlId = tracer.sqlId(sql, state.dbId);
      } else if (state.sqlId != 0 || state.rawSql != null) {
        if (state.sqlId == 0) {
          state.sqlId = tracer.sqlId(state.rawSql, state.dbId);
          // Normalized: the raw text is no longer needed (DSN-10-17, held by reference only).
          state.rawSql = null;
        }
        sqlId = state.sqlId;
      } else if (isBatch(method) && state.batchSql != null) {
        sqlId = tracer.sqlId(state.batchSql, state.dbId);
      } else {
        sqlId = tracer.dict(DictKind.SQL, UNKNOWN_SQL);
      }
      state.execSqlId = sqlId;
      state.txId = context.txId;
      state.execStartNanos = now;
      state.seq = tracer.stepStart(context, StepType.SQL, sqlId, state.dbId, now);
      return state;
    } catch (Throwable t) {
      Argos.tracer().moduleError(JdbcModule.MODULE_ID, t);
      return null;
    }
  }

  /**
   * Exit of the outermost execution: closes the step (error flag when it threw; rows come from the
   * result set later), sets the batch size of {@code executeBatch}, writes the bind parameter
   * detail when the mode is not {@code masked} (16 §6.7, the first set of a batch) and drops the
   * kept values, counts the execution in the transaction ({@code sql_count}, {@code sql_time_ms}),
   * records the full statistics ({@code recordSqlStat}, D-12, inside a transaction only) and links
   * a returned result set to the step.
   */
  public static void executeEnd(
      TraceContext context, SqlState state, String method, Object result, Throwable error) {
    try {
      long end = System.nanoTime();
      Tracer tracer = Argos.tracer();
      boolean failed = error != null;
      int seq = state.seq;
      long elapsed = Math.max(0L, end - state.execStartNanos);
      tracer.stepEnd(context, seq, 0, failed, 0L, end);
      String detail =
          tracer.capture().sqlParamMode() != CaptureConfig.SQL_PARAMS_MASKED
              ? state.paramDetail()
              : null;
      if (isBatch(method)) {
        tracer.stepBatch(context, seq, state.endBatch());
      }
      if (detail != null) {
        tracer.stepDetail(context, seq, detail);
      }
      state.clearParams();
      if (!context.isEnded()) {
        context.addSqlExecution(elapsed);
        tracer.recordSqlStat(state.execSqlId, state.dbId, elapsed, failed, 0L);
      }
      if (!failed && seq >= 0 && result instanceof ResultSet) {
        FetchState fetch = new FetchState(seq, state.txId);
        fetch.sqlId = state.execSqlId;
        fetch.dbId = state.dbId;
        results().put((ResultSet) result, fetch);
        state.fetch = fetch;
      }
    } catch (Throwable t) {
      Argos.tracer().moduleError(JdbcModule.MODULE_ID, t);
    }
  }

  /** The state of {@code statement}; a new one (with its connection's target) when it has none. */
  private static SqlState state(Tracer tracer, Statement statement) {
    ContextStore<Statement, SqlState> store = statements();
    SqlState state = store.get(statement);
    if (state == null) {
      state = new SqlState();
      state.dbId = dbIdOfStatement(tracer, statement);
      store.put(statement, state);
    }
    return state;
  }

  private static boolean isBatch(String method) {
    return "executeBatch".equals(method) || "executeLargeBatch".equals(method);
  }

  // --- batches and parameters ------------------------------------------------------------------

  /**
   * {@code Statement.addBatch(String)} (outermost call): inside a transaction, counts one batch
   * item and keeps the first SQL text of the batch by reference.
   */
  public static void addBatchSql(Object statement, String sql) {
    try {
      Tracer tracer = Argos.tracer();
      TraceContext context = tracer.current();
      if (context == null || context.isEnded() || !(statement instanceof Statement)) {
        return;
      }
      SqlState state = state(tracer, (Statement) statement);
      if (state.batchCount == 0) {
        state.batchSql = sql;
      }
      state.addBatch();
    } catch (Throwable t) {
      Argos.tracer().moduleError(JdbcModule.MODULE_ID, t);
    }
  }

  /**
   * {@code addBatch()}, {@code clearBatch()} or {@code clearParameters()} of a statement with a
   * state: one batch item (the parameter set is frozen at the first one), the end of a batch, or no
   * kept values. {@code clearParameters()} in {@code masked} mode returns at once (nothing is kept
   * then).
   */
  public static void statementCall(Object statement, String method) {
    try {
      if (!(statement instanceof Statement)) {
        return;
      }
      boolean clear = "clearParameters".equals(method);
      if (clear && Argos.tracer().capture().sqlParamMode() == CaptureConfig.SQL_PARAMS_MASKED) {
        return;
      }
      SqlState state = statements().get((Statement) statement);
      if (state == null) {
        return;
      }
      if (clear) {
        state.clearParams();
      } else if ("addBatch".equals(method)) {
        state.addBatch();
      } else {
        state.endBatch();
      }
    } catch (Throwable t) {
      Argos.tracer().moduleError(JdbcModule.MODULE_ID, t);
    }
  }

  /**
   * A {@code set*(index, value)} call outside the {@code masked} mode: the core renders the value
   * into the statement's state (a statement without a state, such as a driver statement under a
   * pool proxy, is skipped).
   */
  public static void parameter(Object statement, int index, Object value, String setter) {
    try {
      if (!(statement instanceof Statement)) {
        return;
      }
      SqlState state = statements().get((Statement) statement);
      if (state != null) {
        Argos.tracer().sqlParam(state, index, value, setter);
      }
    } catch (Throwable t) {
      Argos.tracer().moduleError(JdbcModule.MODULE_ID, t);
    }
  }

  // --- result sets -----------------------------------------------------------------------------

  /** {@code getResultSet()} exit: links the result set to the statement's last SQL step. */
  public static void resultSetOf(Object statement, Object resultSet) {
    try {
      if (!(statement instanceof Statement) || !(resultSet instanceof ResultSet)) {
        return;
      }
      SqlState state = statements().get((Statement) statement);
      if (state == null || state.seq < 0) {
        return;
      }
      ContextStore<ResultSet, FetchState> store = results();
      if (store.get((ResultSet) resultSet) != null) {
        return;
      }
      FetchState fetch = new FetchState(state.seq, state.txId);
      fetch.sqlId = state.execSqlId;
      fetch.dbId = state.dbId;
      store.put((ResultSet) resultSet, fetch);
      state.fetch = fetch;
    } catch (Throwable t) {
      Argos.tracer().moduleError(JdbcModule.MODULE_ID, t);
    }
  }

  /**
   * Enter of {@code next()}: the fetch state of a result set linked to an SQL step (the first call
   * starts the fetch time), or null. No thread-local is read on this per-row path.
   */
  public static FetchState fetchState(Object resultSet) {
    try {
      if (!(resultSet instanceof ResultSet)) {
        return null;
      }
      FetchState fetch = results().get((ResultSet) resultSet);
      if (fetch != null && fetch.needsStart()) {
        fetch.begin(System.nanoTime());
      }
      return fetch;
    } catch (Throwable t) {
      Argos.tracer().moduleError(JdbcModule.MODULE_ID, t);
      return null;
    }
  }

  /** {@code close()}: the rows and fetch time of a linked result set go to its step, once. */
  public static void resultSetClosed(Object resultSet) {
    try {
      if (!(resultSet instanceof ResultSet)) {
        return;
      }
      FetchState fetch = results().remove((ResultSet) resultSet);
      if (fetch != null) {
        Tracer tracer = Argos.tracer();
        report(tracer, tracer.current(), fetch, System.nanoTime());
      }
    } catch (Throwable t) {
      Argos.tracer().moduleError(JdbcModule.MODULE_ID, t);
    }
  }

  /**
   * Adds the rows and the fetch time of {@code fetch} to its SQL step ({@code Tracer.stepFetch})
   * and the fetch time to the transaction's SQL time, once, and only in the transaction that
   * recorded the step; the same rows and time go to the key's full statistics ({@code
   * Tracer.recordSqlFetch}, C-185 (2)).
   */
  private static void report(Tracer tracer, TraceContext context, FetchState fetch, long now) {
    if (context == null || context.txId != fetch.txId || !fetch.markReported()) {
      return;
    }
    long nanos = fetch.fetchNanos(fetch.endNanos(now));
    if (fetch.rows == 0 && nanos == 0) {
      return;
    }
    tracer.stepFetch(context, fetch.seq, fetch.rows, nanos);
    tracer.recordSqlFetch(context, fetch.sqlId, fetch.dbId, nanos, fetch.rows);
    if (!context.isEnded()) {
      context.addSqlNanos(nanos);
    }
  }

  // --- targets (DbInfo) ------------------------------------------------------------------------

  /**
   * The {@code DB_TARGET} id of {@code connection}: {@code getMetaData().getURL()} at most once per
   * connection object (10 §6.3 DbInfo, DSN-10-06), credentials and parameters removed by the core
   * ({@code JdbcUrlParser}). JDBC calls the driver makes meanwhile are nested ({@code CallDepth})
   * and never recorded. 0 when the URL cannot be read; the result also becomes the last known
   * target of the connection's data source (the DBC step's {@code target_id}).
   */
  static long dbId(Tracer tracer, Connection connection) {
    ContextStore<Connection, DbInfo> store = connections();
    DbInfo info = store.get(connection);
    if (info == null) {
      info = new DbInfo();
      store.put(connection, info);
    }
    if (info.resolved) {
      return info.dbId;
    }
    long id = 0L;
    CallDepth.enter(CallDepth.JDBC_CONNECTION);
    CallDepth.enter(CallDepth.JDBC_STATEMENT);
    try {
      DatabaseMetaData metaData = connection.getMetaData();
      String url = metaData == null ? null : metaData.getURL();
      id = url == null ? 0L : tracer.dbId(url);
    } catch (Throwable t) {
      id = 0L;
    } finally {
      CallDepth.exit(CallDepth.JDBC_STATEMENT);
      CallDepth.exit(CallDepth.JDBC_CONNECTION);
    }
    info.resolve(id);
    return id;
  }

  /** The target of a plain statement's connection ({@code getConnection()} under a guard). */
  private static long dbIdOfStatement(Tracer tracer, Statement statement) {
    Connection connection;
    CallDepth.enter(CallDepth.JDBC_CONNECTION);
    try {
      connection = statement.getConnection();
    } catch (Throwable t) {
      return 0L;
    } finally {
      CallDepth.exit(CallDepth.JDBC_CONNECTION);
    }
    return connection == null ? 0L : dbId(tracer, connection);
  }
}
