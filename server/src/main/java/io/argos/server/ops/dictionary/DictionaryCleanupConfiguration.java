/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.ops.dictionary;

import io.argos.server.config.ArgosProperties;
import io.argos.server.ingest.DictionaryResolver;
import io.argos.server.storage.postgres.ops.DictionaryCleanupRepository;
import java.time.Clock;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** The nightly dictionary clean-up ({@link DictionaryCleanupJob}, SEC-13, 17 §9.3). */
@Configuration(proxyBeanMethods = false)
public class DictionaryCleanupConfiguration {

  /**
   * The job, scheduled at {@code argos.ops.dictionary-cleanup.cron} unless {@code
   * argos.ops.dictionary-cleanup.enabled=false}. A context without a clock bean gets the UTC system
   * clock; one without the ingest {@link DictionaryResolver} has no cache to evict from.
   */
  @Bean
  public DictionaryCleanupJob dictionaryCleanupJob(
      ArgosProperties properties,
      DictionaryCleanupRepository repository,
      ObjectProvider<Clock> clock,
      ObjectProvider<DictionaryResolver> resolver) {
    DictionaryResolver cache = resolver.getIfAvailable();
    return new DictionaryCleanupJob(
        properties,
        repository::deleteUnseenSince,
        clock.getIfAvailable(Clock::systemUTC),
        cache == null ? null : cache::forgetStored);
  }
}
