/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.storage.clickhouse;

import com.clickhouse.client.api.Client;
import com.clickhouse.client.api.query.QueryResponse;
import com.clickhouse.client.api.query.QuerySettings;
import io.argos.server.config.ArgosProperties;
import io.argos.server.storage.clickhouse.ClickHouseClients.Account;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;

/**
 * Health contributor {@code clickhouse}, member of the readiness group ({@code application.yml}; 17
 * §3.5: readiness needs the PostgreSQL and ClickHouse connections and the migrations): UP when
 * {@code SELECT 1} as the reader account ({@code argos_reader}) answers within {@link #TIMEOUT},
 * DOWN otherwise. The health response carries no details (no URL, account or error), and the WARN
 * line of a change to DOWN names the failure, never a secret.
 *
 * <p>Nothing happens before the first check: the client is created then (and reused), so the bean
 * neither reads the password file nor connects while the context starts. Checks run only when a
 * probe calls the management port, which opens after the start sequence of DSN-12-15; this
 * indicator therefore never delays or blocks the start.
 */
@Component(ClickHouseHealthIndicator.BEAN_NAME)
public class ClickHouseHealthIndicator implements HealthIndicator, DisposableBean {

  /** Bean name; Spring Boot names the contributor {@code clickhouse} after it. */
  public static final String BEAN_NAME = "clickhouseHealthIndicator";

  /** Upper bound of one check (connect, query and answer). */
  static final Duration TIMEOUT = Duration.ofSeconds(2);

  /** Upper bound of the failure text in the log line. */
  private static final int MAX_FAILURE_LENGTH = 300;

  private static final Logger LOG = LoggerFactory.getLogger(ClickHouseHealthIndicator.class);

  private final ArgosProperties.ClickHouse clickhouse;
  private final Object lock = new Object();
  private final AtomicReference<Boolean> lastUp = new AtomicReference<>();
  private Client client; // guarded by lock
  private boolean closed; // guarded by lock

  public ClickHouseHealthIndicator(ArgosProperties properties) {
    this.clickhouse = properties.clickhouse();
  }

  @Override
  public Health health() {
    String failure = check();
    boolean up = failure == null;
    Boolean previous = lastUp.getAndSet(up);
    if (!up && !Boolean.FALSE.equals(previous)) {
      LOG.warn("ClickHouse readiness check failed, readiness is DOWN: {}", failure);
    } else if (up && Boolean.FALSE.equals(previous)) {
      LOG.info("ClickHouse answers again, readiness check is UP");
    }
    return up ? Health.up().build() : Health.down().build();
  }

  /** Null when {@code SELECT 1} answered in time, otherwise the reason (one line, no secret). */
  String check() {
    CompletableFuture<QueryResponse> answer;
    try {
      QuerySettings settings = new QuerySettings().setMaxExecutionTime((int) TIMEOUT.toSeconds());
      answer = client().query("SELECT 1", settings);
    } catch (RuntimeException e) {
      return describe(e);
    }
    QueryResponse response = null;
    try {
      response = answer.get(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
      return null;
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return "interrupted";
    } catch (TimeoutException e) {
      answer.cancel(true);
      return "no answer within " + TIMEOUT.toSeconds() + " s";
    } catch (ExecutionException e) {
      return describe(e.getCause() == null ? e : e.getCause());
    } finally {
      close(response);
    }
  }

  private Client client() {
    synchronized (lock) {
      if (closed) {
        throw new IllegalStateException("The server is shutting down");
      }
      if (client == null) {
        client = ClickHouseClients.open(clickhouse, Account.READER, TIMEOUT, TIMEOUT);
      }
      return client;
    }
  }

  private static void close(QueryResponse response) {
    if (response == null) {
      return;
    }
    try {
      response.close();
    } catch (Exception e) {
      LOG.debug("Closing a ClickHouse health response failed: {}", e.getClass().getName());
    }
  }

  private static String describe(Throwable failure) {
    String message = failure.getMessage();
    String text =
        failure.getClass().getSimpleName()
            + (message == null ? "" : ": " + message.lines().findFirst().orElse(""));
    return text.length() <= MAX_FAILURE_LENGTH ? text : text.substring(0, MAX_FAILURE_LENGTH);
  }

  @Override
  public void destroy() {
    synchronized (lock) {
      closed = true;
      if (client != null) {
        client.close();
        client = null;
      }
    }
  }
}
