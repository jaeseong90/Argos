/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.session;

import java.time.Instant;
import java.util.Set;

/**
 * The PostgreSQL side of the instance retention job (12 §4.3, {@link InstanceRetentionJob}):
 * implemented in {@code io.argos.server.storage.postgres.instance} (SRV-01: JDBC only in storage).
 * An instance is <i>silent since</i> its last heartbeat ({@code last_seen_at}), or its first
 * registration ({@code first_seen_at}) when it never sent one.
 *
 * <p>Every method may throw a Spring {@code DataAccessException}.
 */
public interface InstanceRetentionStore {

  /**
   * Archives the instances that are not archived, are silent since {@code silentSince} or earlier
   * and are not in {@code live}: {@code archived_at = now}, {@code status = 'DOWN'}, no WARN flags.
   *
   * @return instances archived
   */
  int archiveSilentSince(Instant silentSince, Set<Integer> live, Instant now);

  /**
   * Deletes the archived instances silent since {@code silentSince} or earlier and not in {@code
   * live}. An instance that a {@code commands} row still references (its foreign key has no
   * cascade; commands are WO-26's) is kept until that row is gone. Their {@code events} rows stay
   * (no foreign key: the history of an application keeps its instances' starts, stops and state
   * changes); their ClickHouse rows age out by TTL (12 §4.3).
   *
   * @return instances deleted
   */
  int purgeSilentSince(Instant silentSince, Set<Integer> live);
}
