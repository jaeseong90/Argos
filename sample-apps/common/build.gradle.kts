// SPDX-License-Identifier: Apache-2.0
//
// Framework-neutral core shared by every sample app (18 §4.2-§4.4, WO-01): endpoint catalog
// (ShopCatalog, BatchCatalog), environment switches (SAMPLE_DB, SAMPLE_VT, SAMPLE_ROUTING_DS,
// SAMPLE_POOL, SAMPLE_CUSTOM_EXECUTOR, -Dsample.fixed-clock), schema and data, JDBC operations with
// exact SQL counts, pool and routing data sources, outgoing calls to ext-stub, PII/XSS seeds with
// the ARGOS_SEED sentinel. Compiled for Java 8 so that the javax apps (Boot 2.7, Tomcat 9) and the
// jakarta apps (Boot 3.x/4.x, Tomcat 10.1/11) share it. No servlet, JPA or web API here: the
// per-app web layers stay thin.

plugins {
  id("argos.sample-app")
  // Tests over 1 s (@Tag("slow")) live in src/integrationTest (18 §2.2).
  id("argos.integration-test")
}

description = "Sample apps: shared endpoint catalog, data access, seeds and switches (Java 8)."

sampleApp {
  javaRelease = 8
  runJdk = 8
}

dependencies {
  // Pools and Spring JDBC are provided by each app (its Spring Boot BOM picks the version). The
  // Boot 2.7 BOM gives the oldest API generation (Java 8), which later versions keep.
  compileOnly(platform(libs.sample.boot2.bom))
  compileOnly(libs.sample.hikari)
  compileOnly(libs.sample.tomcat.jdbc)
  compileOnly(libs.sample.dbcp2)
  compileOnly(libs.sample.spring.jdbc)

  testImplementation(platform(libs.sample.boot2.bom))
  testImplementation(libs.sample.h2)
  testImplementation(libs.sample.hikari)
  testImplementation(libs.sample.tomcat.jdbc)
  testImplementation(libs.sample.dbcp2)
  testImplementation(libs.sample.spring.jdbc)
  // AbstractRoutingDataSource needs spring-context (JNDI lookup) at run time; apps have it.
  testRuntimeOnly(libs.sample.spring.context)
}

// The slow tests use the same database, pools and Spring JDBC as the unit tests.
configurations.named("integrationTestImplementation") {
  extendsFrom(configurations.testImplementation.get())
}

configurations.named("integrationTestRuntimeOnly") {
  extendsFrom(configurations.testRuntimeOnly.get())
}
