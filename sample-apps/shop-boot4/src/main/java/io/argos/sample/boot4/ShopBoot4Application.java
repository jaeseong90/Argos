/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.sample.boot4;

import io.argos.sample.boot4.config.ServletContainers;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;

/**
 * shop-boot4: the latest combination of 18 §4.1 (Spring Boot 4.x, Framework 7.x, jakarta Servlet
 * 6.1, Tomcat 11, Hibernate 7, Jackson 3). Everything below {@code io.argos.sample.boot4} is the
 * thin web layer over {@code io.argos.sample.common}, the same as shop-boot3 apart from the Boot 4
 * package moves (RestTemplateBuilder in {@code org.springframework.boot.restclient}) and properties
 * ({@code spring.web.error.*}).
 */
@SpringBootApplication
@EnableAsync
public class ShopBoot4Application {

  /** Name used when build-info.properties is missing (IDE runs). */
  public static final String APP_NAME = "shop-boot4";

  public static void main(String[] args) {
    SpringApplication application = new SpringApplication(ShopBoot4Application.class);
    // SAMPLE_CONTAINER=jetty (WO-50); Tomcat by default.
    ServletContainers.select(application, System.getenv());
    application.run(args);
  }
}
