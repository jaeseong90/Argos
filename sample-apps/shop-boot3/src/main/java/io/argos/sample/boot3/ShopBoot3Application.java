/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.sample.boot3;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;

/**
 * shop-boot3: the reference sample app (18 §4.1). Everything below {@code io.argos.sample.boot3} is
 * the thin web layer over {@code io.argos.sample.common}; shop-heavy and shop-war-jakarta start
 * this class as their Spring Boot source.
 */
@SpringBootApplication
@EnableAsync
public class ShopBoot3Application {

  /** Name used when build-info.properties is missing (IDE runs). */
  public static final String APP_NAME = "shop-boot3";

  public static void main(String[] args) {
    SpringApplication.run(ShopBoot3Application.class, args);
  }
}
