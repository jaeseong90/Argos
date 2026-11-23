/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.alert;

import io.argos.server.alert.api.NotificationSink;
import io.argos.server.alert.engine.AlertEngine;
import io.argos.server.alert.metric.AlertSampleIntake;
import io.argos.server.alert.templates.DefaultTemplates;
import io.argos.server.alert.templates.TemplateService;
import io.argos.server.config.ArgosProperties;
import io.argos.server.realtime.RealtimeBus;
import io.argos.server.session.SessionRegistry;
import io.argos.server.storage.postgres.alert.rule.AlertEventRepository;
import io.argos.server.storage.postgres.alert.rule.AlertQueryRepository;
import io.argos.server.storage.postgres.alert.rule.AlertRuleRepository;
import io.argos.server.storage.postgres.alert.rule.AlertSetupRepository;
import io.argos.server.storage.postgres.alert.rule.AlertStateRepository;
import io.argos.server.storage.postgres.alert.rule.MuteRepository;
import io.argos.server.storage.query.DictionaryNames;
import io.argos.server.storage.query.QueryExecutor;
import io.argos.server.storage.query.lookup.AppDirectory;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The alert engine's beans (12 §5, WO-25): the ingest sample port, the default templates, the
 * engine and the rule and mute services the REST API calls. The delivery side ({@link
 * NotificationSink}) and the channels are separate beans (lane B).
 */
@Configuration(proxyBeanMethods = false)
public class AlertConfiguration {

  /** The ingest end of the alert windows: the only {@code AlertSampleSink} bean. */
  @Bean
  public AlertSampleIntake alertSampleIntake(Clock clock, MeterRegistry meters) {
    return new AlertSampleIntake(clock, meters);
  }

  /** The 14 default templates of 01 Appendix A. */
  @Bean
  public DefaultTemplates defaultTemplates() {
    return DefaultTemplates.standard();
  }

  /** Rules from templates (recommended rules, server rules, batch rule). */
  @Bean
  public TemplateService alertTemplateService(
      AlertRuleRepository rules, AlertSetupRepository setup, DefaultTemplates templates) {
    return new TemplateService(rules, setup, templates);
  }

  /** The engine ({@code argos-alert-eval}). */
  @Bean
  public AlertEngine alertEngine(
      ArgosProperties properties,
      Clock clock,
      MeterRegistry meters,
      AlertRuleRepository rules,
      AlertStateRepository states,
      AlertEventRepository events,
      MuteRepository mutes,
      AppDirectory apps,
      DictionaryNames names,
      SessionRegistry sessions,
      ObjectProvider<QueryExecutor> queries,
      AlertSampleIntake intake,
      TemplateService templates,
      ObjectProvider<NotificationSink> sinks,
      ObjectProvider<RealtimeBus> bus) {
    return new AlertEngine(
        properties,
        clock,
        meters,
        rules,
        states,
        events,
        mutes,
        apps,
        names,
        sessions,
        queries.getIfAvailable(),
        intake,
        templates,
        sinks.orderedStream().toList(),
        bus);
  }

  /** Rule management (REST, step A2). */
  @Bean
  public AlertRuleService alertRuleService(
      AlertRuleRepository rules, TemplateService templates, AlertEngine engine) {
    return new AlertRuleService(rules, templates, engine);
  }

  /** One-off mutes (REST, step A2). */
  @Bean
  public MuteService muteService(MuteRepository mutes, AlertRuleRepository rules, Clock clock) {
    return new MuteService(mutes, rules, clock);
  }

  /** The read side: active alerts, event history, statistics, failed deliveries (REST). */
  @Bean
  public AlertStatusService alertStatusService(
      AlertEngine engine, AlertQueryRepository queries, Clock clock, ArgosProperties properties) {
    return new AlertStatusService(
        engine, queries, clock, properties.web() == null ? null : properties.web().publicUrl());
  }

  /** Rule previews over the minute rollups (REST, heavy bucket). */
  @Bean
  public RulePreviewService rulePreviewService(ObjectProvider<QueryExecutor> queries, Clock clock) {
    return new RulePreviewService(queries::getIfAvailable, clock);
  }
}
