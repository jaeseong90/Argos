/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.testing.collector;

/** The unary data calls of {@code AgentService} (11 §3). */
public enum BatchKind {
  TRANSACTIONS,
  PROFILES,
  METRICS,
  STATS,
  ACTIVE_SNAPSHOT
}
