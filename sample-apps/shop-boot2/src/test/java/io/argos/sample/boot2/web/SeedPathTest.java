/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.sample.boot2.web;

import static org.assertj.core.api.Assertions.assertThat;

import io.argos.sample.common.catalog.ShopCatalog;
import io.argos.sample.common.seed.Seeds;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The XSS and CSV seeds travel in the request path (18 §4.3): the catalog encodes them, the app
 * decodes the rest of the path, with or without the context path of the WAR (F-A36).
 */
class SeedPathTest {

  @Test
  void everyPathSeedRoundTripsAtTheRootAndUnderAContextPath() {
    List<String> payloads = new ArrayList<>(Seeds.XSS);
    payloads.addAll(Seeds.CSV);
    for (String payload : payloads) {
      String encoded = ShopCatalog.pathKeepingSlashes(payload);
      assertThat(SeedController.pathRemainder("/seed/xss/" + encoded, "", "/seed/xss/"))
          .isEqualTo(payload);
      assertThat(
              SeedController.pathRemainder(
                  "/shop-war-javax/seed/csv/" + encoded, "/shop-war-javax", "/seed/csv/"))
          .isEqualTo(payload);
    }
  }

  @Test
  void plusStaysLiteralInAPath() {
    assertThat(SeedController.pathRemainder("/seed/csv/+cmd%7C1", "", "/seed/csv/"))
        .isEqualTo("+cmd|1");
  }
}
