/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.testing.metric;

import static org.assertj.core.api.Assertions.assertThat;

import io.argos.agent.testing.collector.BatchKind;
import io.argos.agent.testing.collector.FakeCollector;
import io.argos.agent.testing.collector.ReceivedBatch;
import io.argos.proto.v1.MetricBatch;
import io.argos.proto.v1.MetricKind;
import io.argos.proto.v1.MetricSample;
import io.argos.proto.v1.MetricSeries;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Shared helpers of the WO-16 metric suites (package {@code metric}). */
final class MetricRuns {
  private MetricRuns() {}

  /** One received sample with its series. */
  record Point(String series, MetricKind kind, long tsMs, double value, double delta) {
    String base() {
      int brace = series.indexOf('{');
      return brace < 0 ? series : series.substring(0, brace);
    }

    /** The value of label {@code key} (simple values only: the suites' labels need no escapes). */
    String label(String key) {
      String marker = key + "=\"";
      int start = series.indexOf(marker);
      if (start < 0) {
        return null;
      }
      start += marker.length();
      int end = series.indexOf('"', start);
      return end < 0 ? null : series.substring(start, end);
    }
  }

  static String required(String name) {
    String value = System.getProperty(name);
    assertThat(value)
        .as("system property %s (set by agent-testing integrationTest)", name)
        .isNotBlank();
    return value;
  }

  static Path releaseJar() {
    return Path.of(required("argos.test.releaseAgentJar"));
  }

  /** Every sample of the metric batches received so far, in arrival order. */
  static List<Point> points(FakeCollector collector) {
    List<Point> out = new ArrayList<>();
    for (ReceivedBatch batch : collector.batches(BatchKind.METRICS)) {
      if (batch.body() instanceof MetricBatch metrics) {
        out.addAll(points(metrics));
      }
    }
    return out;
  }

  static List<Point> points(MetricBatch metrics) {
    Map<Integer, MetricSeries> series = new HashMap<>();
    for (MetricSeries s : metrics.getSeriesList()) {
      series.put(s.getIndex(), s);
    }
    List<Point> out = new ArrayList<>();
    for (MetricSample sample : metrics.getSamplesList()) {
      MetricSeries s = series.get(sample.getSeriesIndex());
      if (s != null) {
        out.add(
            new Point(
                s.getName(), s.getKind(), sample.getTsMs(), sample.getValue(), sample.getDelta()));
      }
    }
    return out;
  }

  /** The last sample of every series. */
  static Map<String, Point> latest(List<Point> points) {
    Map<String, Point> out = new LinkedHashMap<>();
    for (Point p : points) {
      out.put(p.series(), p);
    }
    return out;
  }

  /** The metric batches received so far. */
  static List<MetricBatch> batches(FakeCollector collector) {
    List<MetricBatch> out = new ArrayList<>();
    for (ReceivedBatch batch : collector.batches(BatchKind.METRICS)) {
      if (batch.body() instanceof MetricBatch metrics) {
        out.add(metrics);
      }
    }
    return out;
  }
}
