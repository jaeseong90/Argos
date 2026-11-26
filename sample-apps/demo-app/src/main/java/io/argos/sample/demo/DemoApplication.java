/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.sample.demo;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import io.argos.sample.common.db.DbKind;
import io.argos.sample.common.db.JdbcAccess;
import io.argos.sample.common.db.ShopJdbc;
import java.net.http.HttpClient;
import java.time.Clock;
import java.time.Duration;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * The public demo app {@code argos-demo-app} (17 §3.9, ADR-0004 C-43, WO-41): Spring Boot web on
 * the embedded Tomcat, Spring JDBC with HikariCP, the PostgreSQL driver and Log4j 2; no JPA, no
 * Logback, no H2. One app, two roles ({@code DEMO_ROLE}): order-api serves the shop reads and the
 * checkout, which calls payment-api. The endpoints are the demo catalog (common: DemoCatalog).
 */
@SpringBootApplication
@EnableScheduling
public class DemoApplication {

  private static final Logger LOG = LoggerFactory.getLogger(DemoApplication.class);

  public static void main(String[] args) {
    SpringApplication.run(DemoApplication.class, args);
  }

  @Bean
  DemoSettings demoSettings(Environment environment) {
    return DemoSettings.from(environment::getProperty);
  }

  /**
   * HikariCP pool on the role's schema. The pool starts without a connection
   * (initializationFailTimeout -1): the database may come up after the app.
   */
  @Bean(destroyMethod = "close")
  HikariDataSource dataSource(DemoSettings settings) {
    HikariConfig config = new HikariConfig();
    config.setPoolName("demo-" + settings.role());
    config.setDriverClassName(DbKind.POSTGRES.driverClass());
    config.setJdbcUrl(settings.dbUrl());
    config.setUsername(settings.dbUser());
    config.setPassword(settings.readDbPassword());
    config.setSchema(settings.schema());
    config.setMaximumPoolSize(10);
    config.setMinimumIdle(2);
    config.setConnectionTimeout(5_000);
    config.setInitializationFailTimeout(-1);
    return new HikariDataSource(config);
  }

  @Bean
  DemoDatabase demoDatabase(DataSource dataSource, DemoSettings settings) {
    return DemoDatabase.of(dataSource, settings);
  }

  @Bean
  ShopJdbc shopJdbc(DataSource dataSource) {
    return new ShopJdbc(JdbcAccess.of(dataSource), DbKind.POSTGRES, Clock.systemUTC());
  }

  /** The client of the checkout call (order-api to payment-api); instrumented by the agent. */
  @Bean
  HttpClient peerHttpClient() {
    return HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(2))
        .followRedirects(HttpClient.Redirect.NEVER)
        .build();
  }

  /** Schema and data after the web server is up (health 503 until then). */
  @Bean
  ApplicationRunner initializeDemoData(DemoDatabase database, DemoSettings settings) {
    return args -> {
      LOG.info(
          "Demo app role={} schema={} database={} peer={}",
          settings.role(),
          settings.schema(),
          settings.dbUrlForLog(),
          settings.peerUrl() == null ? "-" : settings.peerUrl());
      database.initialize();
    };
  }
}
