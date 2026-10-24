/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.sample.heavy.service;

import io.argos.sample.common.http.OutboundOutcome;
import io.argos.sample.heavy.client.ExtStubInventoryClient;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.cloud.client.ServiceInstance;
import org.springframework.cloud.client.circuitbreaker.CircuitBreakerFactory;
import org.springframework.cloud.client.discovery.DiscoveryClient;
import org.springframework.cloud.loadbalancer.support.LoadBalancerClientFactory;
import org.springframework.stereotype.Service;

/**
 * Inventory lookup at ext-stub: OpenFeign client, load-balanced through Spring Cloud LoadBalancer
 * (static discovery), guarded by a Resilience4j circuit breaker with a fallback answer.
 */
@Service
public class InventoryService {

  /** Service id of ext-stub in discovery, the load balancer and the circuit breaker. */
  public static final String SERVICE_ID = "ext-stub";

  private final ExtStubInventoryClient client;
  private final CircuitBreakerFactory<?, ?> circuitBreakers;
  private final LoadBalancerClientFactory loadBalancers;
  private final DiscoveryClient discovery;

  public InventoryService(
      ExtStubInventoryClient client,
      CircuitBreakerFactory<?, ?> circuitBreakers,
      LoadBalancerClientFactory loadBalancers,
      DiscoveryClient discovery) {
    this.client = client;
    this.circuitBreakers = circuitBreakers;
    this.loadBalancers = loadBalancers;
    this.discovery = discovery;
  }

  /** One call to ext-stub; a failure gives the fallback answer with its classification. */
  public Map<String, Object> inventory(long productId) {
    return circuitBreakers
        .create(SERVICE_ID)
        .run(
            () -> {
              Map<String, Object> upstream = client.inventory(productId);
              Map<String, Object> result = new LinkedHashMap<>();
              result.put("productId", productId);
              result.put("source", SERVICE_ID);
              result.put("outcome", OutboundOutcome.OK.name());
              result.put("upstreamPath", upstream.get("path"));
              return result;
            },
            failure -> {
              Map<String, Object> result = new LinkedHashMap<>();
              result.put("productId", productId);
              result.put("source", "fallback");
              result.put("outcome", OutboundOutcome.classify(failure).name());
              result.put("error", failure.getClass().getSimpleName());
              return result;
            });
  }

  /**
   * Startup: resolves the service through discovery, creates the load balancer's child context for
   * it, runs a local supplier through a circuit breaker (no call to ext-stub is made).
   */
  public Map<String, Object> prepare() {
    List<ServiceInstance> instances = discovery.getInstances(SERVICE_ID);
    if (instances.isEmpty()) {
      throw new IllegalStateException("No discovery instance for " + SERVICE_ID);
    }
    Object loadBalancer = loadBalancers.getInstance(SERVICE_ID);
    if (loadBalancer == null) {
      throw new IllegalStateException("No load balancer for " + SERVICE_ID);
    }
    // One guarded run of a local supplier: the circuit breaker, its time limiter and executor.
    Object guarded =
        circuitBreakers.create(SERVICE_ID + "-startup").run(() -> "ok", t -> "fallback");
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("circuitBreaker", guarded);
    result.put("instances", instances.size());
    result.put("uri", instances.get(0).getUri().toString());
    result.put("loadBalancer", loadBalancer.getClass().getSimpleName());
    result.put("feignClient", client.getClass().getSimpleName().startsWith("$Proxy"));
    return result;
  }
}
