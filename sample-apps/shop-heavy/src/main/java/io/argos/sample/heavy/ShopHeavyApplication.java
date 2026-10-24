/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.sample.heavy;

import io.argos.sample.boot3.ShopBoot3Application;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cloud.openfeign.EnableFeignClients;

/**
 * shop-heavy (18 §4.1): shop-boot3 (its whole web layer and catalog) plus a large enterprise
 * dependency set that is really initialized at startup, the startup cost baseline of N-04 and N-27.
 * Both classes are primary sources: {@link ShopBoot3Application} scans {@code
 * io.argos.sample.boot3}, this class scans {@code io.argos.sample.heavy}. What loads at startup and
 * why: startup.StartupReport and shop-heavy/README.md.
 */
@SpringBootApplication
@EnableFeignClients
@EnableCaching
public class ShopHeavyApplication {

  /** Name used when build-info.properties is missing (IDE runs). */
  public static final String APP_NAME = "shop-heavy";

  public static void main(String[] args) {
    SpringApplication.run(
        new Class<?>[] {ShopBoot3Application.class, ShopHeavyApplication.class}, args);
  }
}
