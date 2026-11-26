// SPDX-License-Identifier: Apache-2.0
//
// demo-app: the public demo app image argos-demo-app (17 §3.2, §3.9, WO-41). One app, two roles
// (DEMO_ROLE=order|payment: order-api and payment-api of the demo profile), endpoints of the demo
// catalog (common: DemoCatalog), a @Scheduled settlement batch, data in PostgreSQL.
// ADR-0004 C-43 / ADR-0010 C-186 (1): a dedicated build on allow-listed licenses only, not shop-boot3:
// Spring Boot 4 web MVC (embedded Tomcat 11), Spring JDBC with HikariCP, PostgreSQL driver, Log4j 2. No JPA or
// Hibernate, no Logback, no jakarta.annotation-api (Tomcat's tomcat-annotations-api instead),
// no AspectJ, H2, MySQL, MariaDB or Oracle driver.
//   checkLicenses             runtimeClasspath against the allow list (ArgosProject.isDevelopmentOnly
//                             leaves this project out of the development-only set; T-BLD-007)
//   checkDemoAppDependencies  (in check) the forbidden components of DemoImage.FORBIDDEN stay out
//   jibDockerBuild            argos-demo-app:<version> with the agent at
//                             /opt/argos-agent/argos-agent.jar (argos.demo-image, local daemon)
//   integrationTest           both roles against a real PostgreSQL (Testcontainers, the pinned image
//                             of deploy/postgres/image.properties): every catalog entry, its SQL
//                             count, checkout to payment-api, the batch, the password file

import io.argos.buildlogic.demo.CheckDemoAppDependencies
import io.argos.buildlogic.demo.DemoImage
import io.argos.buildlogic.release.notices.registerBundledNotices
import java.util.Properties

plugins {
  id("argos.sample-app")
  id("argos.integration-test")
  id("argos.demo-image")
}

description = "Demo app: order-api and payment-api of the demo profile (public image argos-demo-app)."

sampleApp {
  javaRelease = 17
  // The image runs on the server's Temurin 21 base; the tests use the same JDK.
  runJdk = 21
  bootJar("io.argos.sample.demo.DemoApplication", libs.sample.boot4.loader)
}

demoImage {
  imageName = DemoImage.APP_IMAGE_NAME
  title = DemoImage.APP_IMAGE_NAME
  description = "Argos demo app (order-api and payment-api) with the Argos agent"
  jvmFlags = DemoImage.APP_JVM_FLAGS
  environment = DemoImage.APP_ENVIRONMENT
  ports = listOf(DemoImage.APP_PORT)
  withAgent = true
}

// C-43: Logback and jakarta.annotation-api never reach any classpath of this project (as in
// argos.java-server); Log4j 2 replaces Logback, tomcat-annotations-api carries jakarta.annotation.
configurations.configureEach {
  exclude(group = "org.springframework.boot", module = "spring-boot-starter-logging")
  exclude(group = "ch.qos.logback")
  exclude(group = "jakarta.annotation", module = "jakarta.annotation-api")
}

dependencies {
  // ADR-0010 C-253 (1): the Boot 4 line (sample-boot4, Framework 7, Tomcat 11) of shop-boot4, whose
  // BOM carries the fixed versions of the image scan; the Boot 3.5 BOM had fixable Critical and
  // High findings with no 6.2.x fix for spring-webmvc. The image runs on Java 21, and the agent is
  // verified on Boot 4 by shop-boot4@21.
  implementation(platform(libs.sample.boot4.bom))
  // Framework-neutral catalog, schema and JDBC operations; no runtime dependency of its own.
  implementation(project(":sample-apps:common"))
  // Boot 4 name of the servlet web starter (spring-boot-starter-web is its deprecated alias).
  implementation(libs.sample.starter.webmvc)
  implementation(libs.sample.starter.log4j2)
  implementation(libs.sample.starter.jdbc)
  // jakarta.annotation (@PostConstruct, @Resource, ...) for Spring and Tomcat, Apache-2.0.
  implementation(libs.sample.tomcat.annotations.api)
  runtimeOnly(libs.sample.postgresql)

  "integrationTestImplementation"(libs.testcontainers.core)
  "integrationTestImplementation"(libs.testcontainers.postgresql)
}

// The integration test starts the app in process (both roles) and uses its stack directly.
configurations.named("integrationTestImplementation") { extendsFrom(configurations.implementation.get()) }

configurations.named("integrationTestRuntimeOnly") { extendsFrom(configurations.runtimeOnly.get()) }

val checkDemoAppDependencies =
  tasks.register<CheckDemoAppDependencies>("checkDemoAppDependencies") {
    runtimeRoot = configurations.runtimeClasspath.flatMap { it.incoming.resolutionResult.rootComponent }
  }

tasks.named("check") { dependsOn(checkDemoAppDependencies) }

// Image notices (WO-44, O-L04, C-186 (1)): THIRD-PARTY-NOTICES and argos-deps.txt of the bundled
// libraries, which argos.demo-image puts next to LICENSE and NOTICE in /opt/argos-demo and checks
// (checkDemoImageFiles, T-BLD-003b). Licenses come from checkLicenses; every library with a license
// other than Apache-2.0 (PostgreSQL JDBC, SLF4J) carries its license text in its own jar, so no
// upstream text is kept in this project.
registerBundledNotices("${DemoImage.APP_IMAGE_NAME} image", layout.buildDirectory.dir("demo-image-notices"))

// The PostgreSQL image of Compose and the server tests (deploy/postgres/image.properties).
val postgresImage =
  providers.fileContents(layout.settingsDirectory.file("deploy/postgres/image.properties")).asText.map { text ->
    val properties = Properties()
    properties.load(text.reader())
    properties.getProperty("image")?.trim()
      ?: throw GradleException("deploy/postgres/image.properties has no 'image'")
  }

tasks.named<Test>("integrationTest") {
  inputs.property("postgresImage", postgresImage)
  systemProperty("argos.demo.postgresImage", postgresImage.get())
  val scratch = layout.buildDirectory.dir("tmp/integrationTest-scratch")
  systemProperty("java.io.tmpdir", scratch.get().asFile.absolutePath)
  doFirst {
    scratch.get().asFile.deleteRecursively()
    scratch.get().asFile.mkdirs()
  }
}
