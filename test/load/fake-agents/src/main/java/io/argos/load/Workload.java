/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.load;

import io.argos.proto.v1.MetricKind;
import io.argos.proto.v1.MetricSeries;
import io.argos.server.testing.fakeagent.Records;
import java.util.ArrayList;
import java.util.List;

/**
 * The fixed vocabulary of the load (18 §8, §9 distributions): service names (Zipf ranks), the
 * normalized SQL texts and outgoing hosts of the statistics windows and profile steps, the metric
 * series, the error types. Every instance uses the same texts, so the dictionary stays bounded
 * (about {@code services + sql + hosts} entries per instance, far below the 10,000 new entries per
 * hour of SEC-13) and only the {@code dict-flood} attack grows it.
 */
final class Workload {

  /** The database target of every SQL and DBC step. */
  static final String DB = "jdbc:postgresql://db:5432/shop";

  /** The connection pool of every DBC step. */
  static final String POOL = "HikariPool-1";

  /** Exception classes of the failed transactions. */
  static final List<String> ERROR_CLASSES =
      List.of(
          "java.lang.IllegalStateException",
          "java.sql.SQLTransientConnectionException",
          "java.net.SocketTimeoutException",
          "org.springframework.web.client.HttpServerErrorException",
          "java.lang.NullPointerException");

  /** Method names of METHOD steps. */
  static final List<String> METHODS =
      List.of(
          "OrderService#price",
          "OrderService#reserve",
          "StockClient#check",
          "PaymentService#authorize");

  private final List<String> services;
  private final List<String> sql;
  private final List<String> hosts;
  private final List<MetricSeries> series;
  private final Zipf serviceRanks;

  /**
   * The vocabulary for {@code services} services, {@code sqlTexts} SQL texts, {@code hosts}
   * outgoing hosts and {@code metrics} metric series.
   */
  Workload(int services, int sqlTexts, int hosts, int metrics) {
    List<String> s = new ArrayList<>(services);
    for (int i = 0; i < services; i++) {
      s.add((i % 5 == 4 ? "POST" : "GET") + " /api/load/s" + i + "/{id}");
    }
    this.services = List.copyOf(s);
    List<String> q = new ArrayList<>(sqlTexts);
    for (int i = 0; i < Math.max(1, sqlTexts); i++) {
      q.add("SELECT t.id, t.c" + (i % 7) + " FROM load_t" + i + " t WHERE t.id = ? AND t.v > ?");
    }
    this.sql = List.copyOf(q);
    List<String> h = new ArrayList<>(hosts);
    for (int i = 0; i < Math.max(1, hosts); i++) {
      h.add("http://load-svc-" + i + ":8080");
    }
    this.hosts = List.copyOf(h);
    List<MetricSeries> m = new ArrayList<>(metrics);
    for (int i = 0; i < metrics; i++) {
      m.add(Records.series(i, "jvm_load_metric_" + i, MetricKind.GAUGE));
    }
    this.series = List.copyOf(m);
    this.serviceRanks = new Zipf(services, 1.1);
  }

  List<String> services() {
    return services;
  }

  List<String> sql() {
    return sql;
  }

  List<String> hosts() {
    return hosts;
  }

  List<MetricSeries> series() {
    return series;
  }

  Zipf serviceRanks() {
    return serviceRanks;
  }
}
