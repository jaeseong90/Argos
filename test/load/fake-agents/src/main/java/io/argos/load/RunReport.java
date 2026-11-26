/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.load;

import io.argos.server.testing.load.ClickHouseProbe;
import io.argos.server.testing.load.Jcmd;
import io.argos.server.testing.load.JfrPinning;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Everything the report of one run shows ({@link ReportWriter}). Filled step by step by the runner;
 * a field the run did not reach stays null (or -1) and the report says {@code n/a}.
 */
final class RunReport {

  /**
   * Rows the load apps have in ClickHouse at the end (argos_migrator, no query limits).
   *
   * @param xlog {@code count()} of {@code xlog}
   * @param xlogDistinct {@code uniqExact(tx_id)} of {@code xlog}
   * @param serviceStat1m {@code sum(count)} of {@code service_stat_1m}
   * @param profileSteps rows of {@code profile_step}
   * @param sqlStatRaw rows of {@code sql_stat_raw}
   * @param extStatRaw rows of {@code ext_stat_raw}
   * @param metric rows of {@code metric}
   * @param xlogByApp {@code count()} of {@code xlog} by app id
   */
  record Stored(
      long xlog,
      long xlogDistinct,
      long serviceStat1m,
      long profileSteps,
      long sqlStatRaw,
      long extStatRaw,
      long metric,
      Map<Integer, Long> xlogByApp) {

    /** Copies the map. */
    Stored {
      xlogByApp = Map.copyOf(xlogByApp);
    }
  }

  LoadOptions options;
  String commit = "unknown";
  String machine = "";
  String java = "";
  String stack = "";
  List<String> serverJvmOptions = List.of();
  Instant started;
  Instant windowStart;
  Instant windowEnd;
  Instant loadEnd;
  Instant finished;
  int instancesConnected;
  int channels;
  List<Long> skewsMs = List.of();
  List<LoadApp> apps = List.of();
  LoadCounters.Snapshot atWindowStart;
  LoadCounters.Snapshot atWindowEnd;
  LoadCounters.Snapshot atEnd;
  Stored stored;
  long drainSeconds = -1;
  ResourceSampler.Summary sampler;
  String rssSource = "";
  long rssPeak = -1;
  long rssSampledMax = -1;
  long userCalls;
  long userErrors;
  double userP95Millis = Double.NaN;
  Map<String, double[]> userEndpoints = new LinkedHashMap<>();
  Jcmd.NativeMemory nativeMemory;
  Jcmd.ClassHistogram classHistogram;
  JfrPinning.Summary pinning;
  ClickHouseProbe.Settings clickHouseSettings;
  long clickHouseMemoryLimitErrors = -1;
  List<Criterion> criteria = List.of();
  List<String> generatorFailures = List.of();
  int generatorFailureCount;
  List<String> notes = new ArrayList<>();
  List<ReportSection> sections = new ArrayList<>();
  List<String> files = new ArrayList<>();
}
