/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.sample.batch.config;

import static org.assertj.core.api.Assertions.assertThat;

import io.argos.sample.common.db.DbKind;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

/**
 * Every database has a batch schema script. Running the H2 script twice on a real database is
 * BatchSchemaH2Test (integrationTest: slow, 18 §2.2).
 */
class BatchSchemaTest {

  @Test
  void everyDatabaseHasAScript() {
    for (DbKind db : new DbKind[] {DbKind.H2, DbKind.POSTGRES, DbKind.MYSQL}) {
      assertThat(new ClassPathResource(BatchSchema.scriptOf(db)).exists()).as(db.id()).isTrue();
    }
  }
}
