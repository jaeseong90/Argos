/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.sample.heavy.client;

import java.util.Map;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

/**
 * OpenFeign client of ext-stub's echo endpoint. No URL: the service id {@code ext-stub} goes
 * through Spring Cloud LoadBalancer, whose static discovery list (application.properties,
 * spring.cloud.discovery.client.simple) points at SAMPLE_EXT_URL.
 */
@FeignClient(name = "ext-stub", contextId = "extStubInventory")
public interface ExtStubInventoryClient {

  @GetMapping("/inventory/{productId}")
  Map<String, Object> inventory(@PathVariable("productId") long productId);
}
