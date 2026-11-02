/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.core.export;

import io.argos.agent.core.config.AgentConfig;
import io.argos.agent.core.trace.Dictionary;

/** What an {@link ExporterFactory} may use: configuration, dictionary and agent identity. */
public final class ExporterContext {
  private final AgentConfig config;
  private final Dictionary dictionary;
  private final AgentInfo agent;

  public ExporterContext(AgentConfig config, Dictionary dictionary, AgentInfo agent) {
    this.config = config;
    this.dictionary = dictionary;
    this.agent = agent;
  }

  public AgentConfig config() {
    return config;
  }

  /** Resolves name ids of transactions and steps to their texts. */
  public Dictionary dictionary() {
    return dictionary;
  }

  public AgentInfo agent() {
    return agent;
  }
}
