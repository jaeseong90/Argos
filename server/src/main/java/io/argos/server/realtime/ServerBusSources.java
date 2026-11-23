/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.realtime;

import io.argos.server.api.active.ActiveTransactions;
import io.argos.server.api.apps.InstanceStates;
import io.argos.server.api.query.QueryLimits.TxKind;
import io.argos.server.session.AgentSession;
import io.argos.server.storage.query.DictionaryNames;
import io.argos.server.storage.query.lookup.AppDirectory;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * {@link BusSources} of the server: {@link AppDirectory} and {@link DictionaryNames} (PostgreSQL)
 * and {@link InstanceStates} (session registry and instance state machine).
 */
final class ServerBusSources implements BusSources {

  private final InstanceStates states;
  private final AppDirectory directory;
  private final DictionaryNames names;

  ServerBusSources(InstanceStates states, AppDirectory directory, DictionaryNames names) {
    this.states = states;
    this.directory = directory;
    this.names = names;
  }

  @Override
  public Optional<AppDirectory.AppInfo> app(int appId) {
    return directory.app(appId);
  }

  @Override
  public Map<Long, String> serviceNames(int appId, Collection<Long> ids) {
    Map<Long, String> out = new LinkedHashMap<>();
    names
        .entries(appId, DictionaryNames.Kind.SERVICE, ids)
        .forEach((id, entry) -> out.put(id, entry.text()));
    return out;
  }

  @Override
  public Collection<AgentSession> sessions(int appId) {
    return states.sessions(appId);
  }

  @Override
  public String state(int instanceId) {
    return states.state(instanceId);
  }

  @Override
  public ActiveTransactions active(int appId, TxKind kinds, Set<Integer> instances) {
    return ActivePayloads.build(
        appId, states.sessions(appId), kinds, instances, states::state, names);
  }
}
