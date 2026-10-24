/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.sample.heavy.config;

import com.querydsl.jpa.impl.JPAQueryFactory;
import io.argos.sample.heavy.service.DocumentExporter;
import io.argos.sample.heavy.service.FormatCodecs;
import io.argos.sample.heavy.service.InventoryService;
import io.argos.sample.heavy.service.ObjectStorage;
import io.argos.sample.heavy.service.OrderSearch;
import io.argos.sample.heavy.service.ProductSummaries;
import io.argos.sample.heavy.service.ReportMailer;
import io.argos.sample.heavy.service.ReportRenderer;
import io.argos.sample.heavy.service.ReviewService;
import io.argos.sample.heavy.service.SpreadsheetExporter;
import io.argos.sample.heavy.startup.StartupStep;
import io.argos.sample.heavy.web.HeavyController;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import jakarta.persistence.EntityManager;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.function.Supplier;
import org.springdoc.core.customizers.SpringDocCustomizers;
import org.springdoc.core.properties.SpringDocConfigProperties;
import org.springdoc.core.providers.SpringDocProviders;
import org.springdoc.core.service.AbstractRequestService;
import org.springdoc.core.service.GenericResponseService;
import org.springdoc.core.service.OpenAPIService;
import org.springdoc.core.service.OperationService;
import org.springframework.beans.factory.ObjectFactory;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.cloud.function.context.FunctionCatalog;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.hateoas.EntityModel;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * shop-heavy beans: the QueryDSL query factory, springdoc's API description resource, two Spring
 * Cloud Function functions (/fn/uppercase, /fn/orderCount), and the startup steps in the order they
 * run (StartupReport).
 */
@Configuration(proxyBeanMethods = false)
public class HeavyConfiguration {

  @Bean
  public JPAQueryFactory jpaQueryFactory(EntityManager entityManager) {
    return new JPAQueryFactory(entityManager);
  }

  /** Replaces springdoc's own resource (ConditionalOnMissingBean); serves /v3/api-docs. */
  @Bean
  public ApiDescription apiDescription(
      ObjectFactory<OpenAPIService> openApiServices,
      AbstractRequestService requestService,
      GenericResponseService responseService,
      OperationService operationService,
      SpringDocConfigProperties properties,
      SpringDocProviders providers,
      SpringDocCustomizers customizers) {
    return new ApiDescription(
        openApiServices,
        requestService,
        responseService,
        operationService,
        properties,
        providers,
        customizers);
  }

  /** POST /fn/uppercase (Spring Cloud Function web). */
  @Bean
  public Function<String, String> uppercase() {
    return value -> value.toUpperCase(Locale.ROOT);
  }

  /** GET /fn/orderCount: one SQL. */
  @Bean
  public Supplier<Map<String, Object>> orderCount(JdbcTemplate jdbc) {
    return () -> {
      Map<String, Object> result = new LinkedHashMap<>();
      result.put("orders", jdbc.queryForObject("SELECT COUNT(*) FROM orders", Long.class));
      return result;
    };
  }

  // --- startup steps, in order (StartupReport) -------------------------------------------------

  @Bean
  @Order(10)
  public StartupStep jacksonFormatsStep(FormatCodecs formats) {
    return StartupStep.of(
        "jackson-formats",
        "Jackson XML, YAML, CSV, CBOR, Smile; Guava, Joda, JSR-310, Hibernate 6, Blackbird, Kotlin"
            + " modules",
        formats::prepare);
  }

  @Bean
  @Order(20)
  public StartupStep jpaStep(ReviewService reviews) {
    return StartupStep.of(
        "jpa-envers-jcache",
        "Hibernate ORM 6, Envers (audit query), JCache second-level cache on Caffeine",
        reviews::prepare);
  }

  @Bean
  @Order(30)
  public StartupStep querydslStep(OrderSearch search) {
    return StartupStep.of(
        "querydsl",
        "QueryDSL JPA (count + page)",
        () -> {
          Map<String, Object> page = search.byStatus("NEW", 0);
          return Map.of("total", page.get("total"), "rows", page.get("rows"));
        });
  }

  @Bean
  @Order(40)
  public StartupStep springCloudStep(InventoryService inventory, FunctionCatalog functions) {
    return StartupStep.of(
        "spring-cloud",
        "Spring Cloud OpenFeign, LoadBalancer (static discovery), CircuitBreaker Resilience4j,"
            + " Function",
        () -> {
          Map<String, Object> result = new LinkedHashMap<>(inventory.prepare());
          Function<Object, Object> uppercase = functions.lookup(Function.class, "uppercase");
          if (uppercase == null) {
            throw new IllegalStateException("Function 'uppercase' not in the catalog");
          }
          Object applied = uppercase.apply("argos");
          if (!String.valueOf(applied).contains("ARGOS")) {
            throw new IllegalStateException("uppercase returned " + applied);
          }
          result.put("function", "uppercase");
          return result;
        });
  }

  @Bean
  @Order(50)
  public StartupStep awsStep(ObjectStorage storage) {
    return StartupStep.of("aws-s3", "AWS SDK 2 (S3 client, presigner, SigV4)", storage::prepare);
  }

  @Bean
  @Order(60)
  public StartupStep thymeleafStep(ReportRenderer renderer) {
    return StartupStep.of(
        "thymeleaf", "Thymeleaf (template parsed and rendered)", renderer::prepare);
  }

  @Bean
  @Order(70)
  public StartupStep poiStep(SpreadsheetExporter spreadsheets) {
    return StartupStep.of(
        "poi", "Apache POI XSSF, XMLBeans (workbook written and read)", spreadsheets::prepare);
  }

  @Bean
  @Order(75)
  public StartupStep wordStep(DocumentExporter documents) {
    return StartupStep.of(
        "poi-word",
        "Apache POI XWPF, XMLBeans (document written and read), Commons Text, Guava",
        documents::prepare);
  }

  @Bean
  @Order(80)
  public StartupStep mailStep(ReportMailer mailer) {
    return StartupStep.of(
        "mail-crypto",
        "Jakarta Mail (MIME message with attachment), Bouncy Castle (SHA3-256)",
        mailer::prepare);
  }

  @Bean
  @Order(90)
  public StartupStep hateoasStep() {
    return StartupStep.of(
        "hateoas",
        "Spring HATEOAS (links from controller methods)",
        () -> {
          EntityModel<Map<String, Object>> model =
              HeavyController.model(1L, Map.of("id", 1L, "status", "NEW"));
          return Map.of("links", model.getLinks().toList().size());
        });
  }

  @Bean
  @Order(100)
  public StartupStep springdocStep(ApiDescription apiDescription) {
    return StartupStep.of(
        "springdoc",
        "springdoc-openapi, swagger-core (OpenAPI description)",
        apiDescription::build);
  }

  @Bean
  @Order(105)
  public StartupStep prometheusStep(PrometheusMeterRegistry registry) {
    return StartupStep.of(
        "micrometer-prometheus",
        "Micrometer Prometheus registry (a counter, one scrape in the text format)",
        () -> {
          // A meter of the app itself, then one scrape of every registered meter.
          registry.counter("argos.sample.heavy.startup.scrapes").increment();
          String scrape = registry.scrape();
          if (!scrape.contains("argos_sample_heavy_startup_scrapes")) {
            throw new IllegalStateException("Prometheus scrape without the startup counter");
          }
          return Map.of("chars", scrape.length());
        });
  }

  @Bean
  @Order(110)
  public StartupStep cacheStep(ProductSummaries summaries, CacheManager caches) {
    return StartupStep.of(
        "spring-cache",
        "Spring Cache on Caffeine (product summary computed and cached)",
        () -> {
          summaries.summary(1L);
          Cache cache = caches.getCache(ProductSummaries.CACHE);
          if (cache == null || cache.get(1L) == null) {
            throw new IllegalStateException("Product summary was not cached");
          }
          return Map.of("cached", true);
        });
  }
}
