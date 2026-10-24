/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.sample.batch;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * shop-batch: the non-HTTP entry points of F-A24 on one shared DataSource (18 §4.1): three
 * {@code @Scheduled} methods (fixed rate, cron, long running), two Quartz jobs (JDBC job store), a
 * Spring Batch job (Tasklet step + chunk step of 5,000), plus a few HTTP endpoints on the same
 * database (S-09, D-20). Catalog: common {@code BatchCatalog}.
 */
@SpringBootApplication
@EnableScheduling
public class ShopBatchApplication {

  /** Name used when build-info.properties is missing (IDE runs). */
  public static final String APP_NAME = "shop-batch";

  public static void main(String[] args) {
    SpringApplication.run(ShopBatchApplication.class, args);
  }
}
