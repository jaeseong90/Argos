/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.sample.heavy.jpa;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class HeavySchemaFilterProviderTest {

  @Test
  void onlyTheHeavyTablesAreManagedByHibernate() {
    assertThat(HeavySchemaFilterProvider.isHeavy("product_reviews")).isTrue();
    assertThat(HeavySchemaFilterProvider.isHeavy("product_reviews_AUD")).isTrue();
    assertThat(HeavySchemaFilterProvider.isHeavy("REVINFO")).isTrue();
    assertThat(HeavySchemaFilterProvider.isHeavy("REVINFO_SEQ")).isTrue();
    for (String shopTable :
        new String[] {"orders", "order_items", "products", "customers", "audit_log"}) {
      assertThat(HeavySchemaFilterProvider.isHeavy(shopTable)).as(shopTable).isFalse();
    }
  }
}
