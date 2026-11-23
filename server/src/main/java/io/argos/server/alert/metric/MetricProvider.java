/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.alert.metric;

/**
 * One source of alert metric values (12 §5.2): {@link WindowProvider}, {@link CounterProvider},
 * {@link RegistryProvider}, {@link ClickHouseProvider}, {@link SelfProvider}. Every provider is
 * read from the alert engine's thread only ({@code argos-alert-eval}).
 */
public interface MetricProvider {

  /** The value of {@code request}: a value, no data, or unavailable for an unsupported metric. */
  MetricValue read(MetricRequest request);
}
