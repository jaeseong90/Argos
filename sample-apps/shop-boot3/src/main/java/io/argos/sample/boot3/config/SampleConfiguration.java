/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.sample.boot3.config;

import io.argos.sample.boot3.ShopBoot3Application;
import io.argos.sample.boot3.http.Hc5Client;
import io.argos.sample.boot3.http.Jdk11Client;
import io.argos.sample.boot3.http.OkHttp4Client;
import io.argos.sample.boot3.http.RestTemplateClient;
import io.argos.sample.boot3.http.WebClientClient;
import io.argos.sample.common.BuildInfo;
import io.argos.sample.common.SampleClock;
import io.argos.sample.common.SampleConfig;
import io.argos.sample.common.catalog.ShopCatalog;
import io.argos.sample.common.datasource.SampleDataSource;
import io.argos.sample.common.datasource.SampleDataSources;
import io.argos.sample.common.db.PoolExhauster;
import io.argos.sample.common.db.ShopJdbc;
import io.argos.sample.common.http.HttpUrlConnectionClient;
import io.argos.sample.common.http.OutboundClient;
import io.argos.sample.common.http.PayService;
import io.argos.sample.common.seed.SeedService;
import io.argos.sample.common.spring.SpringJdbcAccess;
import java.time.Clock;
import java.util.List;
import javax.sql.DataSource;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Wires the framework-neutral core (common) into Spring: switches, data source chain (SAMPLE_DB,
 * SAMPLE_POOL, SAMPLE_ROUTING_DS), JDBC operations, outgoing-call clients, seeds, executors, CORS.
 */
@Configuration(proxyBeanMethods = false)
public class SampleConfiguration implements WebMvcConfigurer {

  @Bean
  public BuildInfo buildInfo() {
    return BuildInfo.load(ShopBoot3Application.class, ShopBoot3Application.APP_NAME);
  }

  @Bean
  public SampleConfig sampleConfig(BuildInfo buildInfo) {
    return SampleConfig.fromEnvironment(buildInfo);
  }

  @Bean
  public Clock sampleClock(SampleConfig config) {
    return SampleClock.of(config);
  }

  @Bean(destroyMethod = "close")
  public SampleDataSource sampleDataSource(SampleConfig config) {
    return SampleDataSources.create(config);
  }

  /**
   * The app's DataSource: the routing chain or the pool itself (JPA and JDBC use it). Closed
   * through {@link SampleDataSource}, not here.
   */
  @Bean(destroyMethod = "")
  @Primary
  public DataSource dataSource(SampleDataSource sampleDataSource) {
    return sampleDataSource.dataSource();
  }

  @Bean
  public ShopJdbc shopJdbc(DataSource dataSource, SampleConfig config, Clock clock) {
    return new ShopJdbc(new SpringJdbcAccess(dataSource), config.db(), clock);
  }

  @Bean
  public PoolExhauster poolExhauster(DataSource dataSource, SampleDataSource sampleDataSource) {
    return new PoolExhauster(new SpringJdbcAccess(dataSource), sampleDataSource.maxPoolSize());
  }

  @Bean(destroyMethod = "close")
  public SampleExecutors sampleExecutors(SampleConfig config) {
    return new SampleExecutors(config);
  }

  /** Clients of GET /api/pay/{id}: the 18 §4.1 shop-boot3 stack. */
  @Bean(destroyMethod = "close")
  public OutboundClients outboundClients(
      SampleConfig config, WebClient.Builder webClientBuilder, RestTemplateBuilder restTemplates) {
    int connect = config.extConnectTimeoutMs();
    int read = config.extTimeoutMs();
    Hc5Client hc5 = new Hc5Client(connect, read);
    List<OutboundClient> clients =
        List.of(
            new HttpUrlConnectionClient(connect, read),
            hc5,
            new OkHttp4Client(connect, read),
            new Jdk11Client(connect, read),
            new WebClientClient(webClientBuilder, connect, read),
            new RestTemplateClient(restTemplates, connect, read));
    return new OutboundClients(clients, hc5);
  }

  @Bean
  public PayService payService(SampleConfig config, OutboundClients clients) {
    return new PayService(config, clients.all());
  }

  @Bean
  public SeedService seedService(ShopJdbc shopJdbc, PayService payService) {
    return new SeedService(shopJdbc, payService);
  }

  /** OPTIONS /api/orders preflight of the catalog answers 200 for the UI dev origin only. */
  @Override
  public void addCorsMappings(CorsRegistry registry) {
    registry
        .addMapping("/api/**")
        .allowedOrigins(ShopCatalog.CORS_ORIGIN)
        .allowedMethods("GET", "POST");
  }

  /**
   * The static noise entries of the catalog (GET /favicon.ico, /static/**) from common's
   * classpath:/static/. Spring Boot's default /** resource handler is off
   * (spring.web.resources.add-mappings=false), so GET /api/not-found-x has no handler (18 §4.2).
   */
  @Override
  public void addResourceHandlers(ResourceHandlerRegistry registry) {
    registry.addResourceHandler("/favicon.ico").addResourceLocations("classpath:/static/");
    registry.addResourceHandler("/static/**").addResourceLocations("classpath:/static/static/");
  }
}
