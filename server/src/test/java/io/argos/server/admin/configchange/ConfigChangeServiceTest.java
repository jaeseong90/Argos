/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.admin.configchange;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.argos.proto.v1.ConfigApplyResult;
import io.argos.proto.v1.ConfigPush;
import io.argos.proto.v1.RejectCode;
import io.argos.proto.v1.RejectedKey;
import io.argos.server.admin.configchange.ConfigChangeService.DiffEntry;
import io.argos.server.admin.configchange.ConfigChangeService.InstanceKeys;
import io.argos.server.admin.configchange.ConfigChangeService.TargetState;
import io.argos.server.api.error.ApiException;
import io.argos.server.api.error.ErrorBody;
import io.argos.server.api.error.ErrorCode;
import io.argos.server.api.query.CursorCodec;
import io.argos.server.audit.Actor;
import io.argos.server.audit.AuditEvent;
import io.argos.server.audit.AuditService;
import io.argos.server.auth.ArgosPrincipal;
import io.argos.server.auth.Role;
import io.argos.server.session.AgentSession;
import io.argos.server.session.SessionRegistry;
import io.argos.server.session.api.SessionMessenger;
import io.argos.server.storage.postgres.app.EventWriteRepository;
import io.argos.server.storage.postgres.configchange.ConfigChangeRepository;
import io.argos.server.storage.postgres.configchange.ConfigChangeRepository.AppConfigValue;
import io.argos.server.storage.postgres.configchange.ConfigChangeRepository.AppRow;
import io.argos.server.storage.postgres.configchange.ConfigChangeRepository.ChangeRow;
import io.argos.server.storage.postgres.configchange.ConfigChangeRepository.InstanceRow;
import io.argos.server.storage.postgres.configchange.ConfigChangeRepository.ResultRow;
import io.argos.server.testing.auth.MutableClock;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * Configuration change control without a database (T-SV-042 partial checks, SEC-23 server side):
 * the server-side key schema, the pre-validation against the targets' allow lists, the diff, the
 * revert value set, and, over a mocked repository and sessions, the push of only allowed keys, the
 * merge of the agent's answer and the {@code ServerHello.config} filter. The database path is
 * {@code ConfigChangeIT}.
 */
@Tag("req:F-S29")
@Tag("req:SEC-23")
@Tag("req:DSN-12-09")
class ConfigChangeServiceTest {

  private static final int APP = 7;

  private final MutableClock clock = new MutableClock();
  private final ConfigChangeRepository repository = mock(ConfigChangeRepository.class);
  private final SessionRegistry sessions = mock(SessionRegistry.class);
  private final SessionMessenger messenger = mock(SessionMessenger.class);
  private final AuditService audit = mock(AuditService.class);
  private final List<String> pushes = new ArrayList<>();
  private ConfigChangeService service;
  private final ArgosPrincipal admin = principal(Role.ADMIN);

  @BeforeEach
  void setUp() {
    service =
        new ConfigChangeService(
            repository,
            sessions,
            messenger,
            mock(EventWriteRepository.class),
            audit,
            (changeId, commandId, tMs, data) -> pushes.add(data),
            new CursorCodec(),
            clock,
            Runnable::run,
            new SimpleMeterRegistry());
    when(repository.app(APP)).thenReturn(Optional.of(new AppRow(APP, "shop", "prod")));
  }

  private static ArgosPrincipal principal(Role role) {
    return new ArgosPrincipal(UUID.randomUUID(), "u", UUID.randomUUID(), role, Set.of(), false);
  }

  private static Actor actor(ArgosPrincipal p) {
    return new Actor(Actor.Type.USER, p.userId().toString(), p.username(), "127.0.0.1", null);
  }

  private static TargetState target(int id, boolean online, String... allow) {
    return new TargetState(
        new InstanceRow(id, APP, "i" + id, List.of(allow)),
        AllowPatterns.of(List.of(allow)),
        online);
  }

  private static List<ErrorBody.Detail> details(Throwable t) {
    return ((ApiException) t).details();
  }

  private static ErrorCode code(Throwable t) {
    return ((ApiException) t).errorCode();
  }

  @Test
  @DisplayName(
      "T-SV-042a the server-side schema of docs/agent-config.md: types, ranges, enums, lists and"
          + " bytes normalized to the agent's form; server.*, remote-config.*, app.*, log.dir,"
          + " agent.disabled and Remote '-' keys immutable; unknown keys refused")
  void schema() {
    assertThat(AgentKeySchema.check("sampling.profile.normal-rate", "0.250").value())
        .isEqualTo("0.25");
    assertThat(AgentKeySchema.check("sampling.profile.normal-rate", "1.5").refusal())
        .isEqualTo(AgentKeySchema.Refusal.INVALID_VALUE);
    assertThat(AgentKeySchema.check("sampling.profile.max-steps", "2.5").refusal())
        .isEqualTo(AgentKeySchema.Refusal.INVALID_VALUE);
    assertThat(AgentKeySchema.check(AgentKeySchema.canonical("argos.enabled"), "FALSE").value())
        .isEqualTo("false");
    assertThat(AgentKeySchema.check("log.level", "debug").value()).isEqualTo("DEBUG");
    assertThat(AgentKeySchema.check("log.level", "verbose").refusal())
        .isEqualTo(AgentKeySchema.Refusal.INVALID_VALUE);
    assertThat(AgentKeySchema.check("instrumentation.disabled", "jdbc, executor").value())
        .isEqualTo("[\"jdbc\", \"executor\"]");
    assertThat(AgentKeySchema.check("instrumentation.disabled", "[\"jdbc\", 'a,b']").value())
        .isEqualTo("[\"jdbc\", \"a,b\"]");
    assertThat(
            AgentKeySchema.check(
                    "instrumentation.ignore-methods", "a,b,c,d,e,f,g,h,i,j,k,l,m,n,o,p,q")
                .refusal())
        .as("more than 16 items")
        .isEqualTo(AgentKeySchema.Refusal.INVALID_VALUE);
    assertThat(AgentKeySchema.check("buffer.max-bytes", "32MB").value()).isEqualTo("33554432");
    assertThat(AgentKeySchema.check("buffer.max-bytes", "512").refusal())
        .isEqualTo(AgentKeySchema.Refusal.INVALID_VALUE);
    assertThat(
            AgentKeySchema.check(
                    "sampling.profile.slow-threshold-ms.services.GET /orders/{id}", "500")
                .value())
        .isEqualTo("500");
    for (String immutable :
        List.of(
            "server.address",
            "server.token",
            "remote-config.allow",
            "app.name",
            "log.dir",
            "agent.disabled",
            "log.max-files",
            "debug.transform-log",
            "server.unknown-but-immutable")) {
      assertThat(AgentKeySchema.check(immutable, "x").refusal())
          .as(immutable)
          .isEqualTo(AgentKeySchema.Refusal.IMMUTABLE);
    }
    assertThat(AgentKeySchema.check("sampling.unknown", "1").refusal())
        .isEqualTo(AgentKeySchema.Refusal.UNKNOWN_KEY);
    assertThat(AgentKeySchema.remotableKeys())
        .contains("enabled", "log.level", "capture.sql-parameters")
        .doesNotContain("server.address", "log.dir", "remote-config.allow");
  }

  @Test
  @DisplayName(
      "T-SV-042b the allow-list globs of 16 §5.5: * within a segment, ** for the rest, argos."
          + " prefix ignored on keys and patterns")
  void allowPatterns() {
    AllowPatterns p =
        AllowPatterns.of(List.of("sampling.**", "instrumentation.ignore-*", "argos.enabled"));
    assertThat(p.matches("sampling.profile.normal-rate")).isTrue();
    assertThat(p.matches("instrumentation.ignore-paths")).isTrue();
    assertThat(p.matches("instrumentation.disabled")).isFalse();
    assertThat(p.matches("enabled")).isTrue();
    assertThat(p.matches("argos.enabled")).isTrue();
    AllowPatterns single = AllowPatterns.of(List.of("sampling.*", "log.level"));
    assertThat(single.matches("sampling.profile.normal-rate"))
        .as("* matches one segment only")
        .isFalse();
    assertThat(single.matches("log.level")).isTrue();
  }

  @Test
  @DisplayName(
      "T-SV-042c pre-validation against the targets' remote_config_allow: a key every target"
          + " allows passes, a key some targets refuse is listed per instance, a key no target"
          + " allows is 422 remote_key_not_allowed naming the key and every target; removals are"
          + " not checked (never pushed)")
  void intersection() {
    TargetState a = target(1, true, "sampling.**", "log.level");
    TargetState b = target(2, false, "log.level");
    Map<String, String> values = new LinkedHashMap<>();
    values.put("log.level", "DEBUG");
    values.put("sampling.profile.normal-rate", "0.5");
    values.put("capture.stack-depth", null);
    List<InstanceKeys> rejected = ConfigChangeService.checkAllowed(values, List.of(a, b));
    assertThat(rejected)
        .containsExactly(new InstanceKeys(2, List.of("sampling.profile.normal-rate")));

    Map<String, String> none = Map.of("capture.sql-parameters", "full");
    assertThatThrownBy(() -> ConfigChangeService.checkAllowed(none, List.of(a, b)))
        .satisfies(
            t -> {
              assertThat(code(t)).isEqualTo(ErrorCode.REMOTE_KEY_NOT_ALLOWED);
              assertThat(details(t))
                  .extracting(ErrorBody.Detail::field)
                  .containsExactly(
                      "changes.capture.sql-parameters",
                      "targets.instance_ids.1",
                      "targets.instance_ids.2");
            });
    assertThat(ConfigChangeService.checkAllowed(none, List.of())).isEmpty();
  }

  @Test
  @DisplayName(
      "T-SV-042d a reason is required (<= 1 KiB); values are checked against the schema in one"
          + " 400 (unknown, immutable, invalid value); the mask pattern lists only grow against the"
          + " server-held list (reduce_masking)")
  void reasonAndValues() {
    assertThatThrownBy(() -> ConfigChangeService.checkReason(" "))
        .satisfies(t -> assertThat(details(t).get(0).reason()).isEqualTo("required"));
    assertThatThrownBy(() -> ConfigChangeService.checkReason("x".repeat(1025)))
        .satisfies(t -> assertThat(details(t).get(0).reason()).isEqualTo("too_long"));
    assertThat(ConfigChangeService.checkReason(" incident 42 ")).isEqualTo("incident 42");

    Map<String, Object> raw = new LinkedHashMap<>();
    raw.put("argos.log.level", "warn");
    raw.put("sampling.profile.max-steps", 200);
    raw.put("sampling.profile.normal-rate", 0.25);
    raw.put("transaction.slow-stack.enabled", false);
    raw.put("instrumentation.ignore-paths", List.of("/health", "/ready"));
    raw.put("capture.stack-depth", null);
    Map<String, String> values = ConfigChangeService.values(raw);
    Map<String, String> checked = ConfigChangeService.checkValues(values, Map.of());
    assertThat(checked)
        .containsEntry("log.level", "WARN")
        .containsEntry("sampling.profile.max-steps", "200")
        .containsEntry("sampling.profile.normal-rate", "0.25")
        .containsEntry("transaction.slow-stack.enabled", "false")
        .containsEntry("instrumentation.ignore-paths", "[\"/health\", \"/ready\"]")
        .containsEntry("capture.stack-depth", null);

    Map<String, Object> twice = new LinkedHashMap<>();
    twice.put("log.level", "INFO");
    twice.put("argos.log.level", "INFO");
    assertThatThrownBy(() -> ConfigChangeService.values(twice))
        .satisfies(t -> assertThat(details(t).get(0).reason()).isEqualTo("duplicate"));

    Map<String, String> bad = new LinkedHashMap<>();
    bad.put("server.address", "evil:9100");
    bad.put("nope.key", "1");
    bad.put("capture.stack-depth", "999");
    assertThatThrownBy(() -> ConfigChangeService.checkValues(bad, Map.of()))
        .satisfies(
            t ->
                assertThat(details(t))
                    .extracting(d -> d.field() + "=" + d.reason())
                    .containsExactly(
                        "changes.server.address=immutable",
                        "changes.nope.key=unknown_key",
                        "changes.capture.stack-depth=invalid_value"));

    Map<String, AppConfigValue> held =
        Map.of(
            "capture.mask-patterns",
            new AppConfigValue(
                "capture.mask-patterns", "[\"a\", \"b\"]", null, null, Instant.EPOCH));
    assertThatThrownBy(
            () -> ConfigChangeService.checkValues(Map.of("capture.mask-patterns", "[\"a\"]"), held))
        .satisfies(t -> assertThat(details(t).get(0).reason()).isEqualTo("reduce_masking"));
    assertThat(ConfigChangeService.checkValues(Map.of("capture.mask-patterns", "a, b, c"), held))
        .containsEntry("capture.mask-patterns", "[\"a\", \"b\", \"c\"]");
  }

  @Test
  @DisplayName(
      "T-SV-042e the diff compares the server-held values; a revert takes, for every key of the"
          + " reverted change, the value held before it (null: removed, not pushed)")
  void diffAndRevert() {
    Map<String, String> after = new LinkedHashMap<>();
    after.put("log.level", "DEBUG");
    after.put("capture.stack-depth", "10");
    after.put("metrics.interval-seconds", null);
    List<DiffEntry> diff =
        ConfigChangeService.diff(
            Map.of("log.level", "INFO", "metrics.interval-seconds", "5"), after);
    assertThat(diff)
        .containsExactly(
            new DiffEntry("capture.stack-depth", null, "10"),
            new DiffEntry("log.level", "INFO", "DEBUG"),
            new DiffEntry("metrics.interval-seconds", "5", null));

    ChangeRow row = change(UUID.randomUUID(), Map.of("log.level", "INFO"), after, "applied", null);
    Map<String, String> revert = ConfigChangeService.revertValues(row);
    assertThat(revert)
        .containsEntry("log.level", "INFO")
        .containsEntry("capture.stack-depth", null)
        .containsEntry("metrics.interval-seconds", null)
        .hasSize(3);
  }

  @Test
  @DisplayName(
      "T-SV-042f/T-SEC-23a apply pushes to each live target only the keys its allow list names"
          + " (the others recorded rejected by the server, never sent), a target without a session"
          + " is pending; the agent's answer is merged (partial: applied + agent and server"
          + " rejections) and audited")
  void applyPushesOnlyAllowedKeys() {
    UUID id = UUID.randomUUID();
    Map<String, String> after = new LinkedHashMap<>();
    after.put("log.level", "DEBUG");
    after.put("sampling.profile.normal-rate", "0.5");
    ChangeRow row = change(id, Map.of(), after, ConfigChangeRepository.PREVIEWED, null);
    Map<String, ResultRow> rows = new HashMap<>();
    when(repository.find(id)).thenReturn(Optional.of(row));
    when(repository.markApplying(eq(id), any(), any())).thenReturn(true);
    when(repository.instancesOf(APP))
        .thenReturn(
            List.of(
                new InstanceRow(1, APP, "a", List.of()),
                new InstanceRow(2, APP, "b", List.of()),
                new InstanceRow(3, APP, "c", List.of("log.level"))));
    AgentSession a = session(1, "sampling.**", "log.level");
    AgentSession b = session(2, "log.level");
    when(sessions.find(1)).thenReturn(Optional.of(a));
    when(sessions.find(2)).thenReturn(Optional.of(b));
    when(sessions.find(3)).thenReturn(Optional.empty());
    when(messenger.pushConfig(anyInt(), any())).thenReturn(true);
    org.mockito.Mockito.doAnswer(
            inv -> {
              ResultRow r = inv.getArgument(0);
              rows.put(r.changeId() + "/" + r.instanceId(), r);
              return null;
            })
        .when(repository)
        .upsertResult(any());
    when(repository.result(eq(id), anyInt()))
        .thenAnswer(inv -> Optional.ofNullable(rows.get(id + "/" + inv.getArgument(1))));

    service.apply(admin, actor(admin), id);

    ArgumentCaptor<ConfigPush> pushed = ArgumentCaptor.forClass(ConfigPush.class);
    verify(messenger).pushConfig(eq(1), pushed.capture());
    assertThat(pushed.getValue().getValuesMap())
        .containsOnlyKeys("log.level", "sampling.profile.normal-rate");
    assertThat(pushed.getValue().getChangeId()).isEqualTo(id.toString());
    verify(messenger).pushConfig(eq(2), pushed.capture());
    assertThat(pushed.getValue().getValuesMap()).containsOnlyKeys("log.level");
    verify(messenger, org.mockito.Mockito.never()).pushConfig(eq(3), any());
    assertThat(rows.get(id + "/2").rejectedKeys()).containsExactly("sampling.profile.normal-rate");
    assertThat(rows.get(id + "/2").awaiting()).isTrue();
    assertThat(rows.get(id + "/3").status()).isEqualTo(ConfigChangeRepository.PENDING);
    assertThat(rows.get(id + "/3").pushedAt()).isNull();
    verify(audit).record(eq(AuditEvent.CONFIG_PUSH), any(), any(), any(), any(), any());

    ConfigApplyResult answer =
        ConfigApplyResult.newBuilder()
            .setChangeId(id.toString())
            .addAppliedKeys("log.level")
            .setEffectiveConfigHash("00ff00ff00ff00ff")
            .build();
    service.onResult(2, answer);
    ResultRow two = rows.get(id + "/2");
    assertThat(two.status()).isEqualTo(ConfigChangeRepository.RESULT_PARTIAL);
    assertThat(two.appliedKeys()).containsExactly("log.level");
    assertThat(two.rejectedJson()).contains("\"by\":\"server\"");

    ConfigApplyResult refused =
        ConfigApplyResult.newBuilder()
            .setChangeId(id.toString())
            .addAppliedKeys("log.level")
            .addRejected(
                RejectedKey.newBuilder()
                    .setKey("sampling.profile.normal-rate")
                    .setCode(RejectCode.NOT_ALLOWED))
            .build();
    service.onResult(1, refused);
    ResultRow one = rows.get(id + "/1");
    assertThat(one.status()).isEqualTo(ConfigChangeRepository.RESULT_PARTIAL);
    assertThat(one.rejectedKeys()).containsExactly("sampling.profile.normal-rate");
    assertThat(one.rejectedJson()).contains("\"code\":\"not_allowed\"", "\"by\":\"agent\"");
    verify(repository, atLeastOnce()).completeIfDone(id);
    assertThat(pushes).anyMatch(p -> p.contains("\"result\":\"partial\""));

    // An answer for a change this instance was not sent is dropped.
    service.onResult(9, answer);
    assertThat(rows).doesNotContainKey(id + "/9");
  }

  @Test
  @DisplayName(
      "T-SV-042g ServerHello.config carries the server-held values whose keys the Hello's allow"
          + " list names (never an immutable key) and resolves the instance's pending results")
  void helloConfig() {
    UUID id = UUID.randomUUID();
    Map<String, AppConfigValue> held = new LinkedHashMap<>();
    held.put("log.level", new AppConfigValue("log.level", "DEBUG", id, null, Instant.EPOCH));
    held.put(
        "capture.sql-parameters",
        new AppConfigValue("capture.sql-parameters", "full", id, null, Instant.EPOCH));
    held.put(
        "server.address", new AppConfigValue("server.address", "x:1", id, null, Instant.EPOCH));
    when(repository.appConfig(APP)).thenReturn(held);
    Map<String, String> after = new LinkedHashMap<>();
    after.put("log.level", "DEBUG");
    after.put("capture.sql-parameters", "full");
    when(repository.find(id)).thenReturn(Optional.of(change(id, Map.of(), after, "applied", null)));
    ResultRow pending =
        new ResultRow(
            id,
            4,
            ConfigChangeRepository.PENDING,
            List.of(),
            List.of(),
            null,
            null,
            null,
            null,
            null,
            null);
    when(repository.undeliveredOf(4)).thenReturn(List.of(pending));

    Map<String, String> config = service.configFor(APP, 4, List.of("log.level", "server.*"));
    assertThat(config).containsOnly(Map.entry("log.level", "DEBUG"));

    ArgumentCaptor<ResultRow> stored = ArgumentCaptor.forClass(ResultRow.class);
    verify(repository).upsertResult(stored.capture());
    assertThat(stored.getValue().status()).isEqualTo(ConfigChangeRepository.RESULT_PARTIAL);
    assertThat(stored.getValue().appliedKeys()).containsExactly("log.level");
    assertThat(stored.getValue().rejectedKeys()).containsExactly("capture.sql-parameters");
  }

  @Test
  @DisplayName(
      "T-SV-042h proposals and the kill switch are for administrators only; a config change of"
          + " the kill switch key is refused (its own audited path)")
  void adminOnly() {
    ArgosPrincipal operator = principal(Role.OPERATOR);
    assertThatThrownBy(
            () ->
                service.propose(
                    operator, actor(operator), APP, "r", Map.of("log.level", "INFO"), null))
        .satisfies(t -> assertThat(code(t)).isEqualTo(ErrorCode.FORBIDDEN));
    assertThatThrownBy(
            () ->
                new KillSwitchService(service)
                    .set(operator, actor(operator), APP, false, null, "r"))
        .satisfies(t -> assertThat(code(t)).isEqualTo(ErrorCode.FORBIDDEN));
    assertThatThrownBy(
            () ->
                service.propose(
                    admin, actor(admin), APP, "r", Map.of("argos.enabled", false), null))
        .satisfies(t -> assertThat(details(t).get(0).reason()).isEqualTo("use_kill_switch"));
  }

  @Test
  @DisplayName(
      "T-SV-042i drift: a live instance whose configuration hash differs from the app's majority")
  void hashDrift() {
    AgentSession a = session(1, "log.level");
    AgentSession b = session(2, "log.level");
    AgentSession c = session(3, "log.level");
    when(a.configHash()).thenReturn("aaaaaaaaaaaaaaaa");
    when(b.configHash()).thenReturn("aaaaaaaaaaaaaaaa");
    when(c.configHash()).thenReturn("bbbbbbbbbbbbbbbb");
    assertThat(ConfigChangeService.hashDrift(List.of(a, b, c)))
        .containsExactly(new ConfigChangeService.Drift(3, null, "bbbbbbbbbbbbbbbb", "config_hash"));
  }

  @Test
  @DisplayName("T-SV-042j secret-like values are masked when shown or audited")
  void masking() {
    assertThat(ConfigValueMasking.mask("some.secret-token", "abc")).isEqualTo("****");
    assertThat(ConfigValueMasking.mask("log.level", "DEBUG")).isEqualTo("DEBUG");
    assertThat(ConfigValueMasking.mask("capture.request-id-header", "argos_" + "A".repeat(43)))
        .isEqualTo("argos_****");
    assertThat(ConfigValueMasking.mask("x", null)).isNull();
  }

  private static AgentSession session(int instanceId, String... allow) {
    AgentSession s = mock(AgentSession.class);
    when(s.instanceId()).thenReturn(instanceId);
    when(s.appId()).thenReturn(APP);
    when(s.remoteConfigAllow()).thenReturn(List.of(allow));
    return s;
  }

  private ChangeRow change(
      UUID id,
      Map<String, String> before,
      Map<String, String> after,
      String status,
      UUID revertOf) {
    return new ChangeRow(
        id,
        APP,
        ConfigChangeService.KIND_CONFIG,
        status,
        true,
        List.of(),
        "reason",
        before,
        after,
        null,
        admin.userId(),
        clock.instant(),
        null,
        null,
        revertOf);
  }
}
