/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.servertest.query;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.argos.server.api.openapi.ArgosCsv;
import io.argos.server.api.openapi.ArgosLimits;
import io.argos.server.api.openapi.ArgosSource;
import io.argos.server.api.openapi.ArgosUntrusted;
import io.argos.server.api.query.QueryLimits;
import io.argos.server.api.query.QuerySources;
import io.argos.server.api.query.ResponseEnvelope;
import io.argos.server.api.query.SourcePlan;
import io.argos.server.api.query.UntrustedStrings;
import io.argos.server.auth.RequiresRole;
import io.argos.server.auth.Role;
import io.argos.server.config.ArgosProperties;
import io.argos.server.storage.query.QueryExecutor;
import io.argos.server.storage.query.QueryOptions;
import io.argos.server.storage.query.QueryParams;
import io.argos.server.storage.query.ReaderQueries;
import io.argos.server.storage.query.RollupAliases;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Test-only endpoints of {@code QueryInfrastructureIT}, added to the real server as an extra
 * primary source; outside {@code io.argos.server}, so the server's component scan never picks them
 * up in other tests. They use the query infrastructure the way the WO-23 endpoints do: the source
 * plan, the envelope, collected strings, the executor with the endpoint's {@code @ArgosLimits}.
 */
@RestController
@RequestMapping("/api/v1/test-query")
public class QueryProbeEndpoints implements DisposableBean {

  /**
   * A response item with collected strings.
   *
   * @param service a service name (collected)
   * @param frames stack frames (collected)
   * @param headers request headers (collected)
   * @param serviceTruncated whether the name was cut
   * @param id a server value
   */
  public record Probe(
      @ArgosUntrusted String service,
      @ArgosUntrusted List<String> frames,
      @ArgosUntrusted Map<String, String> headers,
      @JsonProperty("service_truncated") boolean serviceTruncated,
      String id) {}

  private final QuerySources sources;
  private final QueryExecutor executor;

  public QueryProbeEndpoints(
      QuerySources sources, ArgosProperties properties, RollupAliases aliases) {
    this.sources = sources;
    this.executor =
        new QueryExecutor(
            properties.clickhouse(),
            new ReaderQueries(
                ReaderQueries.load("sqltest", QueryProbeEndpoints.class.getClassLoader()),
                properties.clickhouse().database(),
                properties.clickhouse().tablePrefix(),
                aliases::physical));
  }

  /** The envelope of a planned range with collected strings in its data. */
  @GetMapping("/envelope")
  @RequiresRole(Role.PUBLIC)
  @ArgosCsv
  @ArgosLimits(rangeMax = "1y", rowsMax = 1000, timeoutS = 10)
  @ArgosSource({"xlog", "app_stat_1m", "app_stat_1h"})
  public ResponseEnvelope<List<Probe>> envelope(
      @RequestParam("from") String from,
      @RequestParam("to") String to,
      @RequestParam(value = "step", required = false) String step,
      HttpServletRequest request) {
    Instant f = QueryLimits.time("from", from);
    Instant t = QueryLimits.time("to", to);
    SourcePlan plan =
        sources.plan(f, t, step, QueryLimits.SERIES_RANGE_MAX, sources.transactions(), request);
    UntrustedStrings.Text name =
        UntrustedStrings.name("<script>alert('x')</script>\u0007" + "n".repeat(3000));
    Probe probe =
        new Probe(
            name.value(),
            List.of("com.shop.A.b(A.java:1)\u0000", "<img src=x onerror=alert(1)>"),
            Map.of("X-Request-Id", "r\u001b[31m"),
            name.truncated(),
            "kept");
    return ResponseEnvelope.builder(List.of(probe)).plan(plan).nextCursor(null).build();
  }

  /** A statement longer than the endpoint's 1 s limit: 504 query_timeout. */
  @GetMapping("/slow")
  @RequiresRole(Role.PUBLIC)
  @ArgosLimits(timeoutS = 1)
  public ResponseEnvelope<Long> slow() {
    long sum =
        executor
            .query(
                "numbers_heavy",
                QueryParams.create().uint64("n", 1_900_000_000L),
                QueryOptions.defaults())
            .single()
            .getLong("s");
    return ResponseEnvelope.of(sum);
  }

  @Override
  public void destroy() {
    executor.destroy();
  }
}
