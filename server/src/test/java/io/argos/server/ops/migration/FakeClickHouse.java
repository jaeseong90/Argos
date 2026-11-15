/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.ops.migration;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * In-memory stand-ins of ClickHouse and PostgreSQL for unit tests of the runner's orchestration
 * (the integration tests use real databases).
 */
final class FakeClickHouse implements ClickHouseStatements {

  final List<String> executed = new ArrayList<>();
  final List<String> queries = new ArrayList<>();
  Function<String, List<String>> answers =
      q -> q.startsWith("SELECT DISTINCT") ? List.of() : List.of("1");
  String failOn;

  @Override
  public void execute(String statement) throws MigrationException {
    if (failOn != null && statement.contains(failOn)) {
      throw new MigrationException("ClickHouse: Code: 60. DB::Exception: simulated failure");
    }
    executed.add(statement);
  }

  @Override
  public List<String> queryColumn(String query) {
    queries.add(query);
    return answers.apply(query);
  }

  /** An in-memory {@link MigrationStore}. */
  static final class Store implements MigrationStore {
    final Map<String, HistoryEntry> rows = new LinkedHashMap<>();
    final Map<String, String> rollupAlias = new LinkedHashMap<>();
    int locks;
    int unlocks;

    @Override
    public void lock() {
      locks++;
    }

    @Override
    public void unlock() {
      unlocks++;
    }

    @Override
    public List<HistoryEntry> history() {
      return new ArrayList<>(rows.values());
    }

    @Override
    public void record(
        String version,
        String description,
        String checksum,
        long durationMs,
        boolean success,
        String error) {
      rows.put(
          version,
          new HistoryEntry(version, description, checksum, null, (int) durationMs, success, error));
    }

    @Override
    public int updateChecksum(String version, String checksum) {
      int n = 0;
      for (Map.Entry<String, HistoryEntry> e : rows.entrySet()) {
        if (e.getKey().equals(version) || e.getKey().startsWith(version + "/")) {
          HistoryEntry h = e.getValue();
          e.setValue(
              new HistoryEntry(
                  h.version(),
                  h.description(),
                  checksum,
                  h.appliedAt(),
                  h.durationMs(),
                  h.success(),
                  h.error()));
          n++;
        }
      }
      return n;
    }

    @Override
    public void setRollupAlias(String alias, String table) {
      rollupAlias.put(alias, table);
    }
  }
}
