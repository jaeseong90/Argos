/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.sample.heavy.jpa;

import java.util.Locale;
import org.hibernate.boot.model.relational.Namespace;
import org.hibernate.boot.model.relational.Sequence;
import org.hibernate.mapping.Table;
import org.hibernate.tool.schema.spi.SchemaFilter;
import org.hibernate.tool.schema.spi.SchemaFilterProvider;

/**
 * Limits Hibernate's schema update (spring.jpa.hibernate.ddl-auto=update) to shop-heavy's own
 * tables: the reviews, their Envers audit table and the revision table with its sequence. The shop
 * tables of common (orders, order_items, products, ...) are never altered, not even with foreign
 * keys for shop-boot3's associations.
 */
public class HeavySchemaFilterProvider implements SchemaFilterProvider {

  private static final SchemaFilter HEAVY_ONLY =
      new SchemaFilter() {
        @Override
        public boolean includeNamespace(Namespace namespace) {
          return true;
        }

        @Override
        public boolean includeTable(Table table) {
          return isHeavy(table.getName());
        }

        @Override
        public boolean includeSequence(Sequence sequence) {
          return isHeavy(sequence.getName().getSequenceName().getText());
        }
      };

  /** Tables and sequences managed by Hibernate in shop-heavy. */
  static boolean isHeavy(String name) {
    String lower = name.toLowerCase(Locale.ROOT);
    return lower.startsWith("product_reviews") || lower.startsWith("revinfo");
  }

  @Override
  public SchemaFilter getCreateFilter() {
    return HEAVY_ONLY;
  }

  @Override
  public SchemaFilter getDropFilter() {
    return HEAVY_ONLY;
  }

  @Override
  public SchemaFilter getTruncatorFilter() {
    return HEAVY_ONLY;
  }

  @Override
  public SchemaFilter getMigrateFilter() {
    return HEAVY_ONLY;
  }

  @Override
  public SchemaFilter getValidateFilter() {
    return HEAVY_ONLY;
  }
}
