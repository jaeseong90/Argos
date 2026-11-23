/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.realtime;

import io.argos.server.api.active.ActiveTransactions;
import io.argos.server.api.query.QueryLimits.TxKind;
import io.argos.server.session.AgentSession;
import io.argos.server.storage.query.lookup.AppDirectory;
import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * What the bus reads besides the ingest port: applications, service names, live sessions and
 * instance states. The server's implementation is {@link ServerBusSources}; unit tests give their
 * own.
 */
interface BusSources {

  /** An application (env, slow threshold), empty when unknown or unreadable. */
  Optional<AppDirectory.AppInfo> app(int appId);

  /** The stored names of service keys (raw texts of the keys that have one). */
  Map<Long, String> serviceNames(int appId, Collection<Long> ids);

  /** The live sessions of an application. */
  Collection<AgentSession> sessions(int appId);

  /** The state of an instance ({@code UP}, {@code STALE}, {@code DOWN}, {@code WARN}). */
  String state(int instanceId);

  /** The {@code active} payload of an application for one subscription (14 §4.9). */
  ActiveTransactions active(int appId, TxKind kinds, Set<Integer> instances);
}
