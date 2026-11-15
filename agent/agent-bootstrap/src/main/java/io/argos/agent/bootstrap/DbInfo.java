/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.bootstrap;

/**
 * The JDBC target of one connection or data source object (10 §6.3 ContextStore {@code Connection
 * -> DbInfo}): the {@link DictKind#DB_TARGET} dictionary id, 0 while unknown. A bootstrap type with
 * numbers and another {@code DbInfo} only, never an application object (10 §5.3 A-9, T-AG-004).
 *
 * <ul>
 *   <li>Connection: {@link #dbId} comes from {@code getMetaData().getURL()}, read at most once per
 *       connection object ({@link #resolved}), at its first {@code prepareStatement}/{@code
 *       prepareCall}, {@code execute*}, {@code commit} or {@code rollback} inside a transaction.
 *       {@link #source} is the {@code DbInfo} of the data source that handed the connection out
 *       (the innermost {@code getConnection} that returned this object).
 *   <li>Data source: {@link #dbId} is the target of the connection it handed out last whose target
 *       was read; the DBC step of its next {@code getConnection} uses it as {@code target_id}
 *       (ADR-0010 C-143 (4) "when known") without any JDBC call.
 * </ul>
 *
 * <p>Plain fields, no synchronization (N-05): a racing write can only replace one known id with
 * another known id of the same object.
 *
 * <p>Addition to 10 §3 (WO-13).
 */
public final class DbInfo {
  /** Dictionary id of the JDBC target ({@link DictKind#DB_TARGET}); 0 when unknown. */
  public long dbId;

  /** True once {@code getMetaData().getURL()} was attempted for this connection. */
  public boolean resolved;

  /** The data source's {@code DbInfo} of a connection; null for a data source or when unknown. */
  public DbInfo source;

  public DbInfo() {}

  /** The {@code DbInfo} of a connection handed out by the data source of {@code source}. */
  public DbInfo(DbInfo source) {
    this.source = source;
  }

  /** {@link #dbId}, or the last known target of {@link #source}; 0 when neither is known. */
  public long knownTarget() {
    long id = dbId;
    if (id != 0) {
      return id;
    }
    DbInfo from = source;
    return from == null ? 0 : from.dbId;
  }

  /** Records the target read for this connection and passes it on to its data source. */
  public void resolve(long id) {
    resolved = true;
    dbId = id;
    DbInfo from = source;
    if (from != null && id != 0) {
      from.dbId = id;
    }
  }
}
