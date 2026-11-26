// SPDX-License-Identifier: Apache-2.0
package io.argos.buildlogic.demo

import io.argos.buildlogic.ArgosProject
import io.argos.buildlogic.server.ServerImage
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

/** The demo images of 17 §3.2/§3.9 (WO-41) and their license rules (ADR-0003 C-33, ADR-0004 C-43). */
@Tag("req:N-16")
class DemoImageTest {

  @Test
  @DisplayName("T-BLD-007 demo-app and load are checked by checkLicenses; other sample apps stay dev-only")
  fun publishedSampleAppsAreShipped() {
    assertThat(ArgosProject.isDevelopmentOnly(":sample-apps:demo-app")).isFalse()
    assertThat(ArgosProject.isDevelopmentOnly(":sample-apps:load")).isFalse()
    listOf(":sample-apps:shop-boot3", ":sample-apps:common", ":sample-apps:smoke", ":test:bench", ":agent:agent-testing")
      .forEach { assertThat(ArgosProject.isDevelopmentOnly(it)).`as`(it).isTrue() }
    assertThat(ArgosProject.isDevelopmentOnly(":server")).isFalse()
  }

  @Test
  @DisplayName("T-NFR-16d demo images: server base image and user, agent at /opt/argos-agent, -javaagent first")
  fun imageRules() {
    assertThat(DemoImage.BASE_IMAGE).isEqualTo(ServerImage.BASE_IMAGE).matches(".*@sha256:[0-9a-f]{64}")
    assertThat(DemoImage.USER).isEqualTo("10001:10001")
    assertThat(DemoImage.AGENT_JAR).isEqualTo("/opt/argos-agent/argos-agent.jar")
    assertThat(DemoImage.APP_JVM_FLAGS.first()).isEqualTo("-javaagent:/opt/argos-agent/argos-agent.jar")
    assertThat(DemoImage.APP_JVM_FLAGS).contains("-Xmx256m")
    assertThat(DemoImage.LOAD_ENVIRONMENT)
      .containsEntry("TARGET_URL", "http://sample-app-a:8080")
      .containsEntry("RATE", "50")
      .containsEntry("MIX", "demo")
    // Images carry no secret and no Argos server setting: those come from Compose.
    val env = DemoImage.APP_ENVIRONMENT + DemoImage.LOAD_ENVIRONMENT
    assertThat(env.keys.filter { Regex("(?i).*(token|password|secret|server).*").matches(it) }).isEmpty()
  }

  @Test
  @DisplayName("T-BLD-007 demo app: shop apps, Hibernate, Logback, H2, MySQL, MariaDB, ojdbc, annotation-api, AspectJ rejected")
  fun forbiddenComponents() {
    val forbidden =
      listOf(
        "project :sample-apps:shop-boot3",
        "org.hibernate.orm:hibernate-core",
        "org.hibernate:hibernate-core",
        "ch.qos.logback:logback-classic",
        "org.springframework.boot:spring-boot-starter-logging",
        "com.h2database:h2",
        "com.mysql:mysql-connector-j",
        "mysql:mysql-connector-java",
        "org.mariadb.jdbc:mariadb-java-client",
        "com.oracle.database.jdbc:ojdbc11",
        "jakarta.annotation:jakarta.annotation-api",
        "org.aspectj:aspectjweaver",
      )
    val allowed =
      listOf(
        "project :sample-apps:common",
        "org.springframework.boot:spring-boot-starter-web",
        "org.springframework.boot:spring-boot-starter-log4j2",
        "org.apache.logging.log4j:log4j-core",
        "org.postgresql:postgresql",
        "com.zaxxer:HikariCP",
        "org.apache.tomcat.embed:tomcat-embed-core",
      )
    assertThat(DemoImage.forbidden(forbidden + allowed)).containsExactlyElementsOf(forbidden)
  }

  @Test
  @DisplayName("labels: the server label set with the image's own title and description")
  fun labels() {
    val labels = DemoImage.labels("argos-demo-app", "Argos demo app", "1.2.3", "abc", "https://x", "EPOCH")
    assertThat(labels)
      .containsEntry("org.opencontainers.image.title", "argos-demo-app")
      .containsEntry("org.opencontainers.image.description", "Argos demo app")
      .containsEntry("org.opencontainers.image.licenses", "Apache-2.0")
      .containsEntry("org.opencontainers.image.version", "1.2.3")
      .containsEntry("org.opencontainers.image.revision", "abc")
      .containsKey("org.opencontainers.image.base.digest")
  }
}
