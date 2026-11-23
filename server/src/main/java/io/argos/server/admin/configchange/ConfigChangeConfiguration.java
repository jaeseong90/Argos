/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.admin.configchange;

import io.argos.server.admin.AdminPush;
import io.argos.server.api.query.CursorCodec;
import io.argos.server.audit.AuditService;
import io.argos.server.session.SessionRegistry;
import io.argos.server.session.api.SessionMessenger;
import io.argos.server.storage.postgres.app.EventWriteRepository;
import io.argos.server.storage.postgres.configchange.ConfigChangeRepository;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The beans of configuration change control and the kill switch (WO-26): {@link
 * ConfigChangeService} over the configuration change repository, the session registry and
 * messenger, with its {@code argos-config} threads ({@link ConfigChangeLifecycle}); {@code
 * AdminSessionWiring} connects it to the session stream ({@code ConfigApplyResult}), the session
 * registry (session end) and the Hello ({@code ServerHello.config}).
 */
@Configuration(proxyBeanMethods = false)
public class ConfigChangeConfiguration {

  /** The service's threads, stopped with the context. */
  @Bean(destroyMethod = "close")
  public ConfigChangeLifecycle configChangeLifecycle() {
    return new ConfigChangeLifecycle();
  }

  /** The service; its result timeout starts with it. */
  @Bean
  public ConfigChangeService configChangeService(
      ConfigChangeRepository repository,
      SessionRegistry sessions,
      SessionMessenger messenger,
      EventWriteRepository events,
      AuditService audit,
      AdminPush push,
      CursorCodec cursors,
      Clock clock,
      ConfigChangeLifecycle lifecycle,
      MeterRegistry meters) {
    ConfigChangeService service =
        new ConfigChangeService(
            repository,
            sessions,
            messenger,
            events,
            audit,
            push,
            cursors,
            clock,
            lifecycle.worker(),
            meters);
    lifecycle.start(service);
    return service;
  }

  /** The kill switch over the service. */
  @Bean
  public KillSwitchService killSwitchService(ConfigChangeService changes) {
    return new KillSwitchService(changes);
  }
}
