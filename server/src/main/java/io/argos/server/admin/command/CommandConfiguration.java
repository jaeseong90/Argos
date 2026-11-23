/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.admin.command;

import io.argos.server.admin.AdminPush;
import io.argos.server.audit.AuditService;
import io.argos.server.config.ArgosProperties;
import io.argos.server.secrets.EnvelopeCipher;
import io.argos.server.session.api.SessionMessenger;
import io.argos.server.storage.postgres.command.CommandStore;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The beans of the remote commands (WO-26): the {@link CommandRouter} over the session messenger,
 * the command store, the envelope cipher and the {@code admin} topic, with its {@code
 * argos-command} threads ({@link CommandRouterLifecycle}). {@code AdminSessionWiring} connects it
 * to the session stream (results) and the session registry (session end).
 */
@Configuration(proxyBeanMethods = false)
public class CommandConfiguration {

  /** The router's threads, stopped with the context. */
  @Bean(destroyMethod = "close")
  public CommandRouterLifecycle commandRouterLifecycle() {
    return new CommandRouterLifecycle();
  }

  /** The router; its periodic tasks start with it. */
  @Bean
  public CommandRouter commandRouter(
      CommandStore store,
      SessionMessenger messenger,
      EnvelopeCipher cipher,
      AuditService audit,
      AdminPush push,
      Clock clock,
      ArgosProperties properties,
      CommandRouterLifecycle lifecycle,
      MeterRegistry meters) {
    ArgosProperties.Commands c = properties.commands();
    CommandRouter router =
        new CommandRouter(
            store,
            messenger,
            cipher,
            audit,
            push,
            clock,
            new CommandRouter.Settings(
                c.ttl(),
                c.resultTimeout(),
                c.maxInflightPerInstance(),
                c.maxPerMinutePerInstance(),
                c.keepPerInstance(),
                properties.retention().commandResults()),
            lifecycle.worker(),
            meters);
    Gauge.builder("argos.commands.inflight", router, CommandRouter::inflightCount)
        .description("Remote commands queued or sent and not finished")
        .register(meters);
    lifecycle.start(router);
    return router;
  }
}
