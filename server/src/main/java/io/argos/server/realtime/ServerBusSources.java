/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.realtime;

import io.argos.server.api.active.ActiveTransactions;
import io.argos.server.api.apps.InstanceStates;
import io.argos.server.api.query.QueryLimits.TxKind;
import io.argos.server.session.AgentSession;
import io.argos.server.storage.postgres.catalog.CatalogRepository;
import io.argos.server.storage.query.DictionaryNames;
import io.argos.server.storage.query.lookup.AppDirectory;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * {@link BusSources} of the server: {@link AppDirectory} and {@link DictionaryNames} (PostgreSQL),
 * {@link InstanceStates} (session registry and instance state machine) and, for the {@code
 * overview} status, the instances that are not archived of {@link CatalogRepository} (the REST
 * basis, ADR-0010 C-218 (3)).
 */
final class ServerBusSources implements BusSources {

  private final InstanceStates states;
  private final AppDirectory directory;
  private final DictionaryNames names;
  private final CatalogRepository catalog;

  ServerBusSources(
      InstanceStates states,
      AppDirectory directory,
      DictionaryNames names,
      CatalogRepository catalog) {
    this.states = states;
    this.directory = directory;
    this.names = names;
    this.catalog = catalog;
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

  /**
   * The same computation as {@code OverviewService} and {@code AppsService}: the instance ids that
   * are not archived of the applications in one query, summarized by {@link
   * InstanceStates#summarize(Collection)}. A PostgreSQL failure propagates (the bus falls back).
   */
  @Override
  public Map<Integer, InstanceStates.Summary> summaries(Collection<Integer> appIds) {
    if (catalog == null) {
      return null;
    }
    Map<Integer, List<Integer>> ids =
        catalog.instanceIdsByApp(appIds, InstanceStates.SUMMARY_INSTANCES_MAX);
    Map<Integer, InstanceStates.Summary> out = new LinkedHashMap<>();
    for (int app : appIds) {
      out.put(app, states.summarize(ids.getOrDefault(app, List.of())));
    }
    return out;
  }

  @Override
  public ActiveTransactions active(int appId, TxKind kinds, Set<Integer> instances) {
    return ActivePayloads.build(
        appId, states.sessions(appId), kinds, instances, states::state, names);
  }
}
