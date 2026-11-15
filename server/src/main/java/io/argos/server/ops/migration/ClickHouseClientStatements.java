/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.ops.migration;

import com.clickhouse.client.api.Client;
import com.clickhouse.client.api.command.CommandResponse;
import com.clickhouse.client.api.command.CommandSettings;
import com.clickhouse.client.api.query.GenericRecord;
import com.clickhouse.client.api.query.QuerySettings;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * {@link ClickHouseStatements} on a ClickHouse client-v2 {@link Client} of the migration account.
 * Every statement carries {@code max_execution_time} = the statement timeout (12 §9.3: 10 minutes),
 * which the migrator profile allows (its own limit is 3600 s), and the client waits at most one
 * minute longer.
 */
final class ClickHouseClientStatements implements ClickHouseStatements {

  private final Client client;
  private final Duration timeout;

  ClickHouseClientStatements(Client client, Duration timeout) {
    this.client = client;
    this.timeout = timeout;
  }

  @Override
  public void execute(String statement) throws MigrationException {
    CommandSettings settings = new CommandSettings();
    settings.serverSetting("max_execution_time", Long.toString(timeout.toSeconds()));
    CommandResponse response;
    try {
      response = await(client.execute(statement, settings));
    } catch (RuntimeException e) {
      throw failure(e);
    }
    close(response);
  }

  /** Closes a command response (its body is empty; the statement has completed). */
  private static void close(CommandResponse response) throws MigrationException {
    try {
      response.close();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new MigrationException("Interrupted while closing a ClickHouse response", e);
    } catch (Exception e) {
      throw failure(e);
    }
  }

  @Override
  public List<String> queryColumn(String query) throws MigrationException {
    QuerySettings settings = new QuerySettings();
    settings.serverSetting("max_execution_time", Long.toString(timeout.toSeconds()));
    List<String> values = new ArrayList<>();
    try {
      for (GenericRecord row : client.queryAll(query, settings)) {
        values.add(row.getString(1));
      }
    } catch (RuntimeException e) {
      throw failure(e);
    }
    return values;
  }

  private <T> T await(java.util.concurrent.Future<T> future) throws MigrationException {
    try {
      return future.get(timeout.plusMinutes(1).toMillis(), TimeUnit.MILLISECONDS);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new MigrationException("Interrupted while waiting for ClickHouse", e);
    } catch (ExecutionException e) {
      throw failure(e.getCause() == null ? e : e.getCause());
    } catch (TimeoutException e) {
      future.cancel(true);
      throw new MigrationException(
          "ClickHouse statement did not finish within "
              + timeout.plusMinutes(1).toMinutes()
              + " min",
          e);
    }
  }

  private static MigrationException failure(Throwable e) {
    Throwable cause = e;
    for (int depth = 0;
        depth < 8 && cause instanceof CompletionException && cause.getCause() != null;
        depth++) {
      cause = cause.getCause();
    }
    return new MigrationException("ClickHouse: " + MigrationException.oneLine(cause, 600), cause);
  }
}
