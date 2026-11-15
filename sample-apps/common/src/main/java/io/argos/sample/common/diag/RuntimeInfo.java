/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.sample.common.diag;

import io.argos.sample.common.SampleConfig;
import io.argos.sample.common.datasource.SampleDataSource;
import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Body of GET /api/runtime: switches as configured and as observed (virtualThreads is whether the
 * request thread itself is virtual, 18 §4.4), data source chain, outgoing-call clients
 * (capabilities the smoke runner uses to select catalog entries) and JVM facts.
 */
public final class RuntimeInfo {

  private RuntimeInfo() {}

  /** Facts of the web layer that common cannot know. */
  public static final class WebLayer {
    final String framework;
    final String servletApi;
    final String server;
    final String contextPath;

    /**
     * @param framework e.g. "Spring Boot 3.5.16"
     * @param servletApi "javax" or "jakarta"
     * @param server servlet container, e.g. ServletContext.getServerInfo()
     * @param contextPath context path ("" for the root)
     */
    public WebLayer(String framework, String servletApi, String server, String contextPath) {
      this.framework = framework;
      this.servletApi = servletApi;
      this.server = server;
      this.contextPath = contextPath;
    }
  }

  public static Map<String, Object> describe(
      SampleConfig config,
      SampleDataSource dataSource,
      List<String> clients,
      boolean customExecutorActive,
      Clock clock,
      WebLayer web) {
    Map<String, Object> info = new LinkedHashMap<>();
    info.put("app", config.appName());
    info.put("version", config.appVersion());
    info.put("framework", web.framework);
    info.put("servletApi", web.servletApi);
    info.put("server", web.server);
    info.put("contextPath", web.contextPath);
    info.put("virtualThreads", JavaRuntime.currentThreadIsVirtual());
    info.put("virtualThreadsRequested", config.virtualThreads());
    info.put("requestThread", Thread.currentThread().toString());
    info.put("db", dataSource.db().id());
    info.put("pool", dataSource.poolKind().id());
    info.put("poolMaxSize", dataSource.maxPoolSize());
    info.put("routingDataSource", dataSource.routing());
    // Connections routed so far: > 0 shows that requests really went through the routing level.
    info.put("routingLookups", dataSource.routingLookups());
    info.put("driverWrapper", dataSource.driverWrapper());
    // Connections opened through the wrapper: more than 0 shows that the pool really uses it.
    info.put("driverWrapperConnections", dataSource.driverWrapperConnections());
    info.put("secondPool", dataSource.secondPool());
    info.put("dataSourceChain", String.join(" -> ", dataSource.chain()));
    info.put("customExecutor", customExecutorActive);
    info.put("fixedClock", config.fixedClock() == null ? null : config.fixedClock().toString());
    info.put("businessTime", clock.instant().toString());
    info.put("extUrl", config.extUrl());
    info.put("clients", clients);
    List<String> capabilities = new ArrayList<>();
    for (String client : clients) {
      capabilities.add("client:" + client);
    }
    info.put("capabilities", capabilities);
    info.putAll(JavaRuntime.describe());
    return info;
  }
}
