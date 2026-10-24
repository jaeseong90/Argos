/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.sample.boot2.config;

import java.util.LinkedHashMap;
import java.util.Map;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.util.VersionInfo;
import org.springframework.boot.SpringBootVersion;
import org.springframework.core.SpringVersion;

/**
 * Versions of the 18 §4.1 shop-boot2 stack as the running class path has them (GET /api/runtime
 * {@code libraries}). The Spring Boot 2.7 BOM manages OkHttp 4, so the OkHttp 3 of the stack is
 * pinned by the build; this view shows what really runs (also inside the WAR of shop-war-javax).
 */
public final class StackInfo {

  /** Virtual threads are not applicable to this app (SAMPLE_VT, 18 §4.4: Boot 3.2+ only). */
  public static final String VIRTUAL_THREADS_NOTE =
      "SAMPLE_VT is not applicable: Spring Boot 2.7 has no virtual-thread support and the app is"
          + " built for Java 8; requests run on platform threads";

  private StackInfo() {}

  public static Map<String, Object> libraries() {
    Map<String, Object> libraries = new LinkedHashMap<>();
    libraries.put("springBoot", SpringBootVersion.getVersion());
    libraries.put("springFramework", SpringVersion.getVersion());
    libraries.put("hibernate", org.hibernate.Version.getVersionString());
    libraries.put("httpclient4", httpClient4Version());
    libraries.put("okhttp", okHttpVersion());
    return libraries;
  }

  /** e.g. "3.14.9" (from "okhttp/3.14.9"). */
  static String okHttpVersion() {
    String agent = okhttp3.internal.Version.userAgent();
    return agent.startsWith("okhttp/") ? agent.substring("okhttp/".length()) : agent;
  }

  static String httpClient4Version() {
    VersionInfo info =
        VersionInfo.loadVersionInfo("org.apache.http.client", HttpClients.class.getClassLoader());
    return info == null ? "unknown" : info.getRelease();
  }
}
