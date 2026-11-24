/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.cli;

import io.argos.server.audit.Actor;
import io.argos.server.audit.AuditEvent;
import io.argos.server.audit.AuditResult;
import io.argos.server.audit.AuditService;
import io.argos.server.audit.AuditSink;
import io.argos.server.audit.AuditSinks;
import io.argos.server.audit.DefaultAuditService;
import io.argos.server.audit.Target;
import io.argos.server.config.ArgosProperties;
import io.argos.server.storage.postgres.ops.AdminCommandDatabase;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * The audit log of an {@code argos-admin} command (DSN-17-14 "모두 감사 로그", SEC-80): the sinks of the
 * server's {@code argos.audit.sinks} (PostgreSQL {@code audit_logs} as {@code argos.postgres.user},
 * syslog, JSON Lines) and the fallback file {@code argos.audit.fallback-file} when PostgreSQL
 * cannot take the row (16 §11.3). The PostgreSQL connection is opened per record, so a command
 * whose database is down still leaves its row in the fallback file.
 *
 * <p>Actor: {@code system} with id and name {@value #ACTOR} (a local shell on the server host is
 * the administrator, 17 §6.4). A command without an event of its own records {@code
 * ADMIN_COMMAND_EXECUTED} with target type {@value #TARGET_CLI} and the subcommand as target name
 * ({@link #cli}). Values are counts, ids, names and reason codes: never a secret or a token.
 */
final class CliAudit implements AutoCloseable {

  /** Audit actor of the command line. */
  static final String ACTOR = "argos-admin";

  /** {@code target_type} of {@code ADMIN_COMMAND_EXECUTED}. */
  static final String TARGET_CLI = "cli";

  private final DefaultAuditService service;

  private CliAudit(DefaultAuditService service) {
    this.service = service;
  }

  /**
   * The audit service of a command.
   *
   * @param properties the server configuration
   */
  static CliAudit open(ArgosProperties properties) {
    SimpleMeterRegistry registry = new SimpleMeterRegistry();
    AuditSink postgres = AdminCommandDatabase.lazyAuditSink(properties.postgres());
    AuditSinks.Configured configured;
    try {
      configured = DefaultAuditService.configured(properties.audit(), List.of(postgres), registry);
    } catch (IllegalArgumentException e) {
      // An invalid argos.audit.sinks refuses the server's start; the command still records to
      // PostgreSQL (and its fallback file) rather than nothing.
      configured =
          DefaultAuditService.configured(
              new ArgosProperties.Audit(null, null, null, 1), List.of(postgres), registry);
    }
    return new CliAudit(new DefaultAuditService(configured, registry));
  }

  /** The service (for code that records through {@link AuditService}). */
  AuditService service() {
    return service;
  }

  /** The actor of every command. */
  static Actor actor() {
    return new Actor(Actor.Type.SYSTEM, ACTOR, ACTOR, null, null);
  }

  /** The target of {@code ADMIN_COMMAND_EXECUTED}: type {@value #TARGET_CLI}, the subcommand. */
  static Target cli(String subcommand) {
    return new Target(TARGET_CLI, null, subcommand);
  }

  /** Records one event with the command line's actor. */
  void record(
      AuditEvent event,
      Target target,
      AuditResult result,
      @Nullable Map<String, ?> before,
      @Nullable Map<String, ?> after) {
    service.record(event, actor(), target, result, before, after);
  }

  /** Records {@code ADMIN_COMMAND_EXECUTED} for {@code subcommand}. */
  void executed(String subcommand, AuditResult result, @Nullable Map<String, ?> after) {
    record(AuditEvent.ADMIN_COMMAND_EXECUTED, cli(subcommand), result, null, after);
  }

  /** One-shot: opens, records {@code ADMIN_COMMAND_EXECUTED} and closes. */
  static void executed(
      ArgosProperties properties,
      String subcommand,
      AuditResult result,
      @Nullable Map<String, ?> after) {
    try (CliAudit audit = open(properties)) {
      audit.executed(subcommand, result, after);
    }
  }

  @Override
  public void close() {
    service.destroy();
  }
}
