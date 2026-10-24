/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.sample.boot2.config;

import io.argos.sample.boot2.ShopBoot2Application;
import io.argos.sample.boot2.http.Hc4Client;
import io.argos.sample.boot2.http.OkHttp3Client;
import io.argos.sample.boot2.http.RestTemplateClient;
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
import java.util.Arrays;
import java.util.List;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Wires the framework-neutral core (common) into Spring Boot 2.7: switches, data source chain
 * (SAMPLE_DB, SAMPLE_POOL, SAMPLE_ROUTING_DS), JDBC operations, outgoing-call clients of the
 * shop-boot2 stack (HttpURLConnection, Apache HC4, OkHttp 3, RestTemplate), seeds, executors, CORS.
 * SAMPLE_VT is read but not applicable (Boot 2.7, Java 8): the app logs that and keeps platform
 * threads.
 */
@Configuration(proxyBeanMethods = false)
public class SampleConfiguration implements WebMvcConfigurer {

  private static final Logger log = LoggerFactory.getLogger(SampleConfiguration.class);

  @Bean
  public BuildInfo buildInfo() {
    return BuildInfo.load(ShopBoot2Application.class, ShopBoot2Application.APP_NAME);
  }

  @Bean
  public SampleConfig sampleConfig(BuildInfo buildInfo) {
    SampleConfig config = SampleConfig.fromEnvironment(buildInfo);
    if (config.virtualThreads()) {
      log.warn("{} ({})", StackInfo.VIRTUAL_THREADS_NOTE, config.appName());
    }
    return config;
  }

  @Bean
  public Clock sampleClock(SampleConfig config) {
    return SampleClock.of(config);
  }

  @Bean(destroyMethod = "close")
  public SampleDataSource sampleDataSource(SampleConfig config) {
    return SampleDataSources.create(config);
  }

  /** Destroyed before {@link #sampleDataSource} (it depends on it): H2 SHUTDOWN, then the pool. */
  @Bean(destroyMethod = "close")
  public EmbeddedDatabaseShutdown embeddedDatabaseShutdown(
      SampleDataSource sampleDataSource, SampleConfig config) {
    return new EmbeddedDatabaseShutdown(sampleDataSource, config);
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

  /** Clients of GET /api/pay/{id}: the 18 §4.1 shop-boot2 stack. */
  @Bean(destroyMethod = "close")
  public OutboundClients outboundClients(SampleConfig config, RestTemplateBuilder restTemplates) {
    int connect = config.extConnectTimeoutMs();
    int read = config.extTimeoutMs();
    Hc4Client hc4 = new Hc4Client(connect, read);
    OkHttp3Client okHttp = new OkHttp3Client(connect, read);
    RestTemplateClient restTemplate = new RestTemplateClient(restTemplates, connect, read);
    List<OutboundClient> clients =
        Arrays.<OutboundClient>asList(
            new HttpUrlConnectionClient(connect, read), hc4, okHttp, restTemplate);
    // Every pooled client is closed with the context (WAR undeploy leaves no pool behind).
    return new OutboundClients(clients, hc4, okHttp, restTemplate);
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
