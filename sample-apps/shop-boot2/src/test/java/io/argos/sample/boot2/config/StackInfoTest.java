/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.sample.boot2.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import org.apache.catalina.util.ServerInfo;
import org.junit.jupiter.api.Test;

/**
 * The class path really is the 18 §4.1 shop-boot2 stack. The Spring Boot 2.7 BOM manages OkHttp 4,
 * so a lost version pin would silently turn the "OkHttp 3" app into an OkHttp 4 app. RestTemplate
 * on Apache HttpClient 4 is checked by RestTemplateStackTest (integrationTest: slow, 18 §2.2).
 */
class StackInfoTest {

  @Test
  void librariesAreTheLegacyStack() {
    Map<String, Object> libraries = StackInfo.libraries();
    assertThat(String.valueOf(libraries.get("springBoot"))).startsWith("2.7.");
    assertThat(String.valueOf(libraries.get("springFramework"))).startsWith("5.3.");
    assertThat(String.valueOf(libraries.get("hibernate"))).startsWith("5.");
    assertThat(String.valueOf(libraries.get("httpclient4"))).startsWith("4.5.");
    assertThat(String.valueOf(libraries.get("okhttp"))).startsWith("3.");
  }

  @Test
  void servletApiIsJavax4OnTomcat9() {
    assertThat(ServerInfo.getServerNumber()).startsWith("9.");
    // PushBuilder is new in Servlet 4.0 (javax); Servlet 5+ is jakarta.
    assertThatCode(() -> Class.forName("javax.servlet.http.PushBuilder"))
        .doesNotThrowAnyException();
    assertThatThrownBy(() -> Class.forName("jakarta.servlet.Servlet"))
        .isInstanceOf(ClassNotFoundException.class);
  }
}
