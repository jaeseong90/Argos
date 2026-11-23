/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.admin;

import io.argos.server.realtime.RealtimeBus;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Shared beans of the administration services (WO-26). */
@Configuration(proxyBeanMethods = false)
public class AdminConfiguration {

  /**
   * The {@code admin} topic over the realtime bus ({@link RealtimeBus#publishAdmin}); drops
   * everything in a context without the bus.
   */
  @Bean
  public AdminPush adminPush(ObjectProvider<RealtimeBus> bus) {
    return (changeId, commandId, tMs, dataJson) -> {
      RealtimeBus b = bus.getIfAvailable();
      if (b != null) {
        b.publishAdmin(changeId, commandId, tMs, dataJson);
      }
    };
  }
}
