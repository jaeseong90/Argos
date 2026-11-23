/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.admin;

import io.argos.server.session.HelloService;
import io.argos.server.session.InMemorySessionRegistry;
import io.argos.server.session.SessionHandler;
import io.argos.server.session.api.CommandResultSink;
import io.argos.server.session.api.ConfigApplyResultSink;
import io.argos.server.session.api.HelloConfigSource;
import io.argos.server.session.api.SessionEndListener;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.stereotype.Component;

/**
 * Connects the administration services to the agent sessions once every singleton exists and before
 * the collector listener starts (WO-26): the unique {@link CommandResultSink} bean ({@code
 * CommandRouter}) and the unique {@link ConfigApplyResultSink} bean ({@code ConfigChangeService})
 * receive the session stream's results, every {@link SessionEndListener} bean the end of each
 * session, and the unique {@link HelloConfigSource} bean ({@code ConfigChangeService}) fills the
 * {@code ServerHello.config} of the {@link HelloService}. A missing bean (a context without the
 * administration services) leaves its part unwired.
 */
@Component
public class AdminSessionWiring implements SmartInitializingSingleton {

  private final ObjectProvider<SessionHandler> handler;
  private final ObjectProvider<InMemorySessionRegistry> registry;
  private final ObjectProvider<CommandResultSink> commandResults;
  private final ObjectProvider<ConfigApplyResultSink> configApplyResults;
  private final ObjectProvider<SessionEndListener> endListeners;
  private final ObjectProvider<HelloService> hellos;
  private final ObjectProvider<HelloConfigSource> helloConfig;

  public AdminSessionWiring(
      ObjectProvider<SessionHandler> handler,
      ObjectProvider<InMemorySessionRegistry> registry,
      ObjectProvider<CommandResultSink> commandResults,
      ObjectProvider<ConfigApplyResultSink> configApplyResults,
      ObjectProvider<SessionEndListener> endListeners,
      ObjectProvider<HelloService> hellos,
      ObjectProvider<HelloConfigSource> helloConfig) {
    this.handler = handler;
    this.registry = registry;
    this.commandResults = commandResults;
    this.configApplyResults = configApplyResults;
    this.endListeners = endListeners;
    this.hellos = hellos;
    this.helloConfig = helloConfig;
  }

  @Override
  public void afterSingletonsInstantiated() {
    SessionHandler h = handler.getIfAvailable();
    if (h != null) {
      CommandResultSink c = commandResults.getIfUnique();
      if (c != null) {
        h.commandResults(c);
      }
      ConfigApplyResultSink a = configApplyResults.getIfUnique();
      if (a != null) {
        h.configApplyResults(a);
      }
    }
    HelloService hello = hellos.getIfAvailable();
    HelloConfigSource source = helloConfig.getIfUnique();
    if (hello != null && source != null) {
      hello.helloConfig(source);
    }
    InMemorySessionRegistry r = registry.getIfAvailable();
    if (r != null) {
      endListeners.orderedStream().forEach(r::addEndListener);
    }
  }
}
