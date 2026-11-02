/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.instrumentation.jdbc;

import io.argos.agent.bootstrap.Argos;
import io.argos.agent.bootstrap.CallDepth;
import io.argos.agent.bootstrap.ContextStore;
import io.argos.agent.bootstrap.DictKind;
import io.argos.agent.bootstrap.StepType;
import io.argos.agent.bootstrap.TraceContext;
import io.argos.agent.bootstrap.Tracer;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.Statement;

/**
 * The work of the JDBC advice (10 §6.3), injected into the class loader of the JDBC classes (10
 * §5.4): bootstrap API and {@code java.sql} only, no state. No method throws: a failure is counted
 * against the module ({@code Tracer.moduleError}) and the statement runs unrecorded, so the advice
 * can always pair its {@code CallDepth} calls.
 *
 * <p>Per statement the helper keeps {@code long[] {sqlId, dbId}} in a {@link ContextStore} (weak
 * keys, bounded; field injection replaces it in WO-13); per connection object the database id. The
 * SQL passed to {@code Tracer.dict(DictKind.SQL, ..)} is raw: the core normalizes it before
 * anything is kept, so no literal leaves the helper (SEC-35).
 */
public final class JdbcHelper {
  /** Step name of a prepared statement whose SQL was not seen (prepared outside a transaction). */
  static final String UNKNOWN_SQL = "<unknown prepared statement>";

  /** Indexes of the step array returned by {@link #startPrepared}/{@link #startStatement}. */
  static final int SEQ = 0;

  static final int SQL_ID = 1;
  static final int DB_ID = 2;
  static final int START = 3;

  private JdbcHelper() {}

  /**
   * Exit of the outermost {@code prepareStatement}/{@code prepareCall}: attaches the SQL and
   * database ids to {@code statement}.
   */
  public static void prepared(Object connection, Object statement, String sql) {
    try {
      if (!(statement instanceof Statement) || sql == null) {
        return;
      }
      Tracer tracer = Argos.tracer();
      long sqlId = tracer.dict(DictKind.SQL, sql);
      long dbId = connection instanceof Connection ? dbId(tracer, (Connection) connection) : 0L;
      ContextStore.of(Statement.class, long[].class)
          .put((Statement) statement, new long[] {sqlId, dbId});
    } catch (Throwable t) {
      Argos.tracer().moduleError(JdbcModule.MODULE_ID, t);
    }
  }

  /** Enter of the outermost {@code PreparedStatement.execute*()}: opens the SQL step. */
  public static long[] startPrepared(TraceContext context, Object statement) {
    try {
      if (!(statement instanceof Statement)) {
        return null;
      }
      Tracer tracer = Argos.tracer();
      long[] ids = ContextStore.of(Statement.class, long[].class).get((Statement) statement);
      if (ids != null) {
        return start(tracer, context, ids[0], ids[1]);
      }
      return start(
          tracer,
          context,
          tracer.dict(DictKind.SQL, UNKNOWN_SQL),
          dbIdOfStatement(tracer, (Statement) statement));
    } catch (Throwable t) {
      Argos.tracer().moduleError(JdbcModule.MODULE_ID, t);
      return null;
    }
  }

  /** Enter of the outermost {@code Statement.execute*(String, ..)}: opens the SQL step. */
  public static long[] startStatement(TraceContext context, Object statement, String sql) {
    try {
      if (!(statement instanceof Statement) || sql == null) {
        return null;
      }
      Tracer tracer = Argos.tracer();
      return start(
          tracer,
          context,
          tracer.dict(DictKind.SQL, sql),
          dbIdOfStatement(tracer, (Statement) statement));
    } catch (Throwable t) {
      Argos.tracer().moduleError(JdbcModule.MODULE_ID, t);
      return null;
    }
  }

  /**
   * Exit of the outermost execution: closes the step (status 0, error flag when the call threw;
   * rows come with ResultSet instrumentation in WO-13) and records the full statistics (D-12).
   */
  public static void end(TraceContext context, long[] step, Throwable error) {
    try {
      long now = System.nanoTime();
      Tracer tracer = Argos.tracer();
      boolean failed = error != null;
      tracer.stepEnd(context, (int) step[SEQ], 0, failed, 0L, now);
      tracer.recordSqlStat(step[SQL_ID], step[DB_ID], now - step[START], failed, 0L);
    } catch (Throwable t) {
      Argos.tracer().moduleError(JdbcModule.MODULE_ID, t);
    }
  }

  private static long[] start(Tracer tracer, TraceContext context, long sqlId, long dbId) {
    long now = System.nanoTime();
    int seq = tracer.stepStart(context, StepType.SQL, sqlId, dbId, now);
    return new long[] {seq, sqlId, dbId, now};
  }

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

  /**
   * The database id of {@code connection}: {@code getMetaData().getURL()} once per connection
   * object (10 §6.3 DbInfo), credentials and parameters removed by the core. JDBC calls the driver
   * makes meanwhile are nested ({@code CallDepth}) and never recorded. 0 when the URL cannot be
   * read.
   */
  static long dbId(Tracer tracer, Connection connection) {
    ContextStore<Connection, Long> ids = ContextStore.of(Connection.class, Long.class);
    Long known = ids.get(connection);
    if (known != null) {
      return known.longValue();
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
    ids.put(connection, Long.valueOf(id));
    return id;
  }
}
