/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.sample.common.catalog;

import java.util.Collections;
import java.util.List;

/** Catalog lookup by name ({@code sampleApp.catalog} in the app's build file). */
public final class Catalogs {

  private Catalogs() {}

  /** "shop" (ShopCatalog), "batch" (BatchCatalog) or "none" (empty). */
  public static List<CatalogEntry> byName(String name) {
    switch (name) {
      case "shop":
        return ShopCatalog.entries();
      case "batch":
        return BatchCatalog.entries();
      case "none":
        return Collections.emptyList();
      default:
        throw new IllegalArgumentException("Unknown catalog '" + name + "' (shop, batch, none)");
    }
  }
}
