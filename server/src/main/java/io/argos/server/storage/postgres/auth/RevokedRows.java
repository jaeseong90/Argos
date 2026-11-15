/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.storage.postgres.auth;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * The rows of one session family that a revocation changed: one revoked session (16 §7.4).
 *
 * @param familyId the login
 * @param userId owner
 * @param sid the family's current row (not rotated), or its newest row when every row was rotated
 * @param sessionIds every row of the family revoked by this statement (the current row and the
 *     rotated ones: an access token of a rotated row may still be within its 15 minutes)
 */
public record RevokedRows(UUID familyId, UUID userId, UUID sid, Set<UUID> sessionIds) {

  public RevokedRows {
    sessionIds = Set.copyOf(sessionIds);
  }

  /**
   * One revoked row as {@code UPDATE ... RETURNING} reports it.
   *
   * @param id row id
   * @param familyId family
   * @param userId owner
   * @param issuedAt creation of the row
   * @param current whether the row was not rotated (the family's current row)
   */
  record Row(UUID id, UUID familyId, UUID userId, Instant issuedAt, boolean current) {}

  /** Groups revoked rows by family, in the order of their first row. */
  static List<RevokedRows> group(List<Row> rows) {
    Map<UUID, List<Row>> byFamily = new LinkedHashMap<>();
    for (Row row : rows) {
      byFamily.computeIfAbsent(row.familyId(), f -> new ArrayList<>()).add(row);
    }
    List<RevokedRows> out = new ArrayList<>();
    byFamily.forEach(
        (family, members) -> {
          Row current =
              members.stream()
                  .filter(Row::current)
                  .findFirst()
                  .orElseGet(
                      () ->
                          members.stream().max(Comparator.comparing(Row::issuedAt)).orElseThrow());
          Set<UUID> ids = new LinkedHashSet<>();
          members.forEach(m -> ids.add(m.id()));
          out.add(new RevokedRows(family, current.userId(), current.id(), ids));
        });
    return out;
  }
}
