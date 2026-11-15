/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.api.transactions;

import io.argos.server.api.error.ApiException;
import io.argos.server.api.error.ErrorCode;
import io.argos.server.storage.query.QueryExecutor;
import io.argos.server.storage.query.QueryOptions;
import io.argos.server.storage.query.QueryParams;
import io.argos.server.storage.query.QueryResult;
import io.argos.server.storage.query.QueryRow;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * Finds a transaction by {@code tx_id} (14 §4.6, DSN-14-07, D-11) in the order of ADR-0007 C-50 as
 * decided by ADR-0010 C-150 (the Gate 0 carry-over of ADR-0006 A-6 handled without a design
 * change):
 *
 * <ol>
 *   <li>{@code ?ts} given: {@code ts} +-2 s, no time restored from the id ({@link Path#TS_HINT});
 *   <li>the time restored from the id, {@code (tx_id >> 23) + 2026-01-01}, +-2 s ({@link
 *       Path#RESTORED}), with the optional app hint;
 *   <li>nothing found: the day of the restored time and the day before ({@code toDate(ts) BETWEEN
 *       toDate(t0) - 1 AND toDate(t0)}, bloom index {@code idx_tx}; {@link Path#TWO_DAY_FALLBACK}).
 *       This finds the transactions of instances whose clock the server corrected (ADR-0010 C-129
 *       (6): their {@code tx_id} keeps the agent clock);
 *   <li>still nothing: 404 {@code not_found} with {@code details [{field: tx_id, reason:
 *       clock_skew_possible}]} and a message that points to the list or {@code ?ts}. There is never
 *       a bloom lookup without a time range (C-150).
 * </ol>
 *
 * Two rows are a {@code tx_id} collision (13 §17 #11): both are returned, the caller warns.
 */
@Component
public class TransactionLookup {

  /** The window of the {@code ?ts} and restored lookups. */
  static final Duration WINDOW = Duration.ofSeconds(2);

  /** How a transaction was found (the {@code lookup} field of the detail). */
  public enum Path {
    /** {@code ?ts} +-2 s. */
    TS_HINT,
    /** The time restored from the id, +-2 s. */
    RESTORED,
    /** The day of the restored time and the day before. */
    TWO_DAY_FALLBACK;

    /** The wire value. */
    public String value() {
      return name().toLowerCase(Locale.ROOT);
    }
  }

  /**
   * The rows found (one, or two on a collision) and how.
   *
   * @param rows the {@code lookup.*} rows
   * @param path the lookup that found them
   */
  public record Found(List<QueryRow> rows, Path path) {

    /** The first row. */
    public QueryRow first() {
      return rows.get(0);
    }
  }

  private final QueryExecutor executor;

  public TransactionLookup(QueryExecutor executor) {
    this.executor = executor;
  }

  /**
   * The transaction, or 404 as above.
   *
   * @param txId the id
   * @param tsHint {@code ?ts}, or null
   * @param appHint an application, or 0
   */
  public Found find(long txId, Instant tsHint, int appHint) {
    return tryFind(txId, tsHint, appHint).orElseThrow(TransactionLookup::notFound);
  }

  /** The transaction, empty when none of the lookups finds it. */
  public Optional<Found> tryFind(long txId, Instant tsHint, int appHint) {
    if (tsHint != null) {
      List<QueryRow> rows = window(txId, tsHint, appHint);
      if (!rows.isEmpty()) {
        return Optional.of(new Found(rows, Path.TS_HINT));
      }
    }
    Instant t0 = Ids.restoredTime(txId);
    List<QueryRow> rows = window(txId, t0, appHint);
    if (!rows.isEmpty()) {
      return Optional.of(new Found(rows, Path.RESTORED));
    }
    QueryResult days =
        executor.query(
            "transactions/lookup.days",
            QueryParams.create().uint32("app", appHint).dateTime64("t0", t0).uint64("tx", txId),
            QueryOptions.defaults().withMaxResultRows(2));
    if (!days.isEmpty()) {
      return Optional.of(new Found(days.rows(), Path.TWO_DAY_FALLBACK));
    }
    return Optional.empty();
  }

  private List<QueryRow> window(long txId, Instant center, int appHint) {
    return executor
        .query(
            "transactions/lookup.window",
            QueryParams.create()
                .uint32("app", appHint)
                .dateTime64("from", center.minus(WINDOW))
                .dateTime64("to", center.plus(WINDOW))
                .uint64("tx", txId),
            QueryOptions.defaults().withMaxResultRows(2))
        .rows();
  }

  /** The 404 of C-150. */
  static ApiException notFound() {
    return new ApiException(
            ErrorCode.NOT_FOUND,
            "No transaction with this id near its time; the agent clock may have been off by more"
                + " than a day: open it from a list or with ?ts")
        .detail("tx_id", "clock_skew_possible");
  }
}
