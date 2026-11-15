/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.servertest.timeseries;

import io.argos.server.config.ArgosProperties;
import io.argos.server.storage.query.QueryExecutor;
import io.argos.server.storage.query.QueryOptions;
import io.argos.server.storage.query.QueryParams;
import io.argos.server.storage.query.QueryResult;
import io.argos.server.storage.query.QueryRow;
import io.argos.server.storage.query.ReaderQueries;
import io.argos.server.storage.query.ReaderQuery;
import io.argos.server.storage.query.SegmentedQuery;
import io.argos.server.storage.query.TimeSegment;
import java.util.List;
import java.util.function.Consumer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

/**
 * Test-only configuration of {@code TimeseriesLimitsIT} (T-API-004): the server's reader with the
 * smallest read limit ClickHouse accepts from {@code argos_reader} ({@code max_rows_to_read = 1},
 * within the profile's constraints), so that every query of a real endpoint fails in ClickHouse
 * with a limit error (code 158) and the server answers 504 {@code query_timeout}, as it does for an
 * expired {@code max_execution_time}. Outside {@code io.argos.server}, so no other test's component
 * scan picks it up; added as an extra primary source.
 */
@Configuration(proxyBeanMethods = false)
public class LimitedReaderConfiguration {

  /** The limited executor; wins over the server's own by {@code @Primary}. */
  @Bean
  @Primary
  QueryExecutor limitedQueryExecutor(ArgosProperties properties, ReaderQueries queries) {
    return new QueryExecutor(properties.clickhouse(), queries) {
      @Override
      public QueryResult query(ReaderQuery query, QueryParams params, QueryOptions options) {
        return super.query(query, params, options.withMaxRowsToRead(1));
      }

      @Override
      public QueryResult query(
          SegmentedQuery query,
          List<TimeSegment> segments,
          QueryParams params,
          QueryOptions options) {
        return super.query(query, segments, params, options.withMaxRowsToRead(1));
      }

      @Override
      public String forEach(
          ReaderQuery query, QueryParams params, QueryOptions options, Consumer<QueryRow> sink) {
        return super.forEach(query, params, options.withMaxRowsToRead(1), sink);
      }
    };
  }
}
