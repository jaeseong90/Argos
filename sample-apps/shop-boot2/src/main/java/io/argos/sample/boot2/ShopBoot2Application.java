/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.sample.boot2;

import io.argos.sample.boot2.config.ServletContainers;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;

/**
 * shop-boot2: the legacy sample app (18 §4.1): Spring Boot 2.7, Framework 5.3, javax Servlet 4.0,
 * Tomcat 9, Hibernate 5, built for Java 8. Everything below {@code io.argos.sample.boot2} is the
 * thin javax web layer over {@code io.argos.sample.common}; shop-war-javax starts this class as its
 * Spring Boot source in a standalone Tomcat 9.
 */
@SpringBootApplication
@EnableAsync
public class ShopBoot2Application {

  /** Name used when build-info.properties is missing (IDE runs). */
  public static final String APP_NAME = "shop-boot2";

  public static void main(String[] args) {
    SpringApplication application = new SpringApplication(ShopBoot2Application.class);
    // SAMPLE_CONTAINER=jetty|undertow (WO-50); Tomcat by default.
    ServletContainers.select(application, System.getenv());
    application.run(args);
  }
}
