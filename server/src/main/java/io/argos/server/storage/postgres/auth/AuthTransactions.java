/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.storage.postgres.auth;

import java.util.function.Supplier;
import javax.sql.DataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.stereotype.Component;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * One PostgreSQL transaction around several repository calls of the authentication code (READ
 * COMMITTED; row locks with {@code SELECT ... FOR UPDATE} where a decision depends on the current
 * row). The repositories of this package use the same runtime data source, so their statements join
 * the transaction.
 *
 * <p>Callers publish events, write audit records and update caches only after {@link
 * #inTransaction} has returned, i.e. after the commit.
 */
@Component
public class AuthTransactions {

  /** Seconds a transaction may take before PostgreSQL work is rolled back. */
  static final int TIMEOUT_SECONDS = 15;

  private final TransactionTemplate template;

  public AuthTransactions(DataSource dataSource) {
    TransactionTemplate t = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
    t.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    t.setTimeout(TIMEOUT_SECONDS);
    this.template = t;
  }

  /** Runs {@code work} in one transaction and returns its result after the commit. */
  public <T> T inTransaction(Supplier<T> work) {
    return template.execute(status -> work.get());
  }

  /** Runs {@code work} in one transaction. */
  public void run(Runnable work) {
    template.executeWithoutResult(status -> work.run());
  }
}
