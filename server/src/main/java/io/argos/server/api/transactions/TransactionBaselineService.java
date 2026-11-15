/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.api.transactions;

import io.argos.server.api.query.QuerySources;
import io.argos.server.api.query.UntrustedStrings;
import io.argos.server.config.ArgosProperties;
import io.argos.server.storage.query.DictionaryNames;
import io.argos.server.storage.query.QueryExecutor;
import io.argos.server.storage.query.QueryOptions;
import io.argos.server.storage.query.QueryParams;
import io.argos.server.storage.query.QueryRow;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.springframework.stereotype.Component;

/**
 * The baseline of a transaction (14 §4.6): the service's p50/p95 from {@code service_stat_1m}
 * within +-30 minutes (quantilesDD states merged, C-53), the share of the service's transactions in
 * that window that were faster (raw {@code xlog}), and a normal sample of the same service, the
 * same instance first. Raw parts are null when the window is older than the raw retention.
 */
@Component
public class TransactionBaselineService {

  /** Half the window around the transaction (14 §4.6: +-30 minutes). */
  static final Duration HALF_WINDOW = Duration.ofMinutes(30);

  private final QueryExecutor executor;
  private final DictionaryNames names;
  private final QuerySources sources;
  private final ArgosProperties.Retention retention;

  public TransactionBaselineService(
      QueryExecutor executor,
      DictionaryNames names,
      QuerySources sources,
      ArgosProperties properties) {
    this.executor = executor;
    this.names = names;
    this.sources = sources;
    this.retention = properties.retention();
  }

  /** The baseline of the first row of {@code found}. */
  public TransactionBaseline baseline(TransactionLookup.Found found) {
    QueryRow x = found.first();
    int appId = (int) x.getLong("app_id");
    int instanceId = (int) x.getLong("instance_id");
    long txId = x.getLong("tx_id");
    long service = x.getLong("service_id");
    long elapsed = x.getLong("elapsed_ms");
    String kind = x.getString("kind");
    Instant ts = Instant.ofEpochMilli(x.getLong("ts_ms"));
    Instant from = ts.minus(HALF_WINDOW).truncatedTo(ChronoUnit.MINUTES);
    Instant to = ts.plus(HALF_WINDOW).truncatedTo(ChronoUnit.MINUTES).plus(Duration.ofMinutes(1));

    QueryRow q =
        executor
            .query(
                "transactions/baseline_quantiles",
                QueryParams.create()
                    .uint32("app", appId)
                    .string("kind", kind)
                    .uint64("service", service)
                    .dateTime("from", from)
                    .dateTime("to", to),
                QueryOptions.defaults())
            .single();
    long count = q.getLong("cnt");
    double[] quantiles = q.getDoubleArray("q");
    Double p50 = count > 0 && quantiles.length > 0 ? finite(quantiles[0]) : null;
    Double p95 = count > 0 && quantiles.length > 1 ? finite(quantiles[1]) : null;

    Double percentile = null;
    String sample = null;
    Instant sampleTs = null;
    Boolean sameInstance = null;
    boolean rawAvailable = from.isAfter(sources.now().minus(retention.xlog()));
    if (rawAvailable) {
      QueryParams base =
          QueryParams.create()
              .uint32("app", appId)
              .dateTime64("from", from)
              .dateTime64("to", to)
              .string("kind", kind)
              .uint64("service", service);
      QueryRow rank =
          executor
              .query(
                  "transactions/baseline_rank",
                  base.copy().int64("elapsed", elapsed),
                  QueryOptions.defaults())
              .single();
      long total = rank.getLong("cnt");
      if (total > 0) {
        percentile = 100.0 * rank.getLong("faster") / total;
      }
      long target = p50 == null ? elapsed : Math.round(p50);
      for (QueryRow s :
          executor.query(
              "transactions/baseline_sample",
              base.copy().uint64("tx", txId).uint32("instance", instanceId).int64("target", target),
              QueryOptions.defaults().withMaxResultRows(1))) {
        sample = Ids.text(s.getLong("tx_id"));
        sampleTs = Instant.ofEpochMilli(s.getLong("ts_ms"));
        sameInstance = s.getLong("instance_id") == instanceId;
      }
    }
    String serviceName = names.name(appId, DictionaryNames.Kind.SERVICE, service);
    return new TransactionBaseline(
        Ids.text(txId),
        serviceName == null ? null : UntrustedStrings.name(serviceName).value(),
        Ids.keyOrNull(service),
        from,
        to,
        count,
        p50,
        p95,
        percentile,
        sample,
        sampleTs,
        sameInstance);
  }

  private static Double finite(double v) {
    return Double.isFinite(v) ? v : null;
  }
}
