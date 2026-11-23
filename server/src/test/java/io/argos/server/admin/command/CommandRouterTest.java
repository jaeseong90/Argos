/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.admin.command;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.google.protobuf.ByteString;
import io.argos.proto.v1.Command;
import io.argos.proto.v1.CommandResult;
import io.argos.proto.v1.CommandStatus;
import io.argos.proto.v1.CommandType;
import io.argos.server.api.error.ApiException;
import io.argos.server.api.error.ErrorCode;
import io.argos.server.audit.Actor;
import io.argos.server.audit.AuditEvent;
import io.argos.server.audit.AuditResult;
import io.argos.server.audit.AuditService;
import io.argos.server.audit.Target;
import io.argos.server.auth.ArgosPrincipal;
import io.argos.server.auth.Role;
import io.argos.server.secrets.EnvelopeCipher;
import io.argos.server.secrets.MasterKey;
import io.argos.server.secrets.MasterKeyRing;
import io.argos.server.session.api.SessionMessenger;
import io.argos.server.storage.postgres.command.CommandStore;
import io.argos.server.testing.auth.MutableClock;
import io.argos.server.testing.fakeagent.CommandReplies;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * {@link CommandRouter} on a moved clock with an in-memory store and session messenger (T-SV-041
 * unit part, T-SEC-21/22/24 server logic): closed list and role split, offline and unknown
 * instances, one command in flight, 10 per minute, argument rules, UUIDv4 ids, TTL expiry without
 * delivery, result timeout, session end, chunk assembly with its 1 MiB bound, sender checks, agent
 * refusals, the encrypted stored result and its audited views.
 */
@Tag("req:SEC-21")
@Tag("req:SEC-22")
@Tag("req:SEC-24")
@Tag("req:F-A12")
class CommandRouterTest {

  private static final int INSTANCE = 12;
  private static final int OTHER = 13;
  private static final String SENTINEL = "ARGOS_SEED_cmdunit";

  private final MutableClock clock = new MutableClock();
  private final MemoryStore store = new MemoryStore();
  private final FakeMessenger messenger = new FakeMessenger();
  private final RecordingAudit audit = new RecordingAudit();
  private final List<String> pushes = new ArrayList<>();
  private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
  private EnvelopeCipher cipher;
  private CommandRouter router;

  private final ArgosPrincipal operator = principal(Role.OPERATOR);
  private final ArgosPrincipal admin = principal(Role.ADMIN);

  @BeforeEach
  void setUp() {
    byte[] key = new byte[MasterKey.LENGTH];
    new SecureRandom().nextBytes(key);
    cipher = new EnvelopeCipher(new MasterKeyRing(MasterKey.of(key), null));
    router = router(CommandRouter.Settings.defaults(), cipher);
    messenger.live.add(INSTANCE);
    messenger.live.add(OTHER);
    store.instances.add(INSTANCE);
    store.instances.add(OTHER);
    store.instances.add(99);
  }

  private CommandRouter router(CommandRouter.Settings settings, EnvelopeCipher c) {
    return new CommandRouter(
        store,
        messenger,
        c,
        audit,
        (changeId, commandId, tMs, data) -> pushes.add(data),
        clock,
        settings,
        Runnable::run,
        meters);
  }

  private static ArgosPrincipal principal(Role role) {
    return new ArgosPrincipal(
        UUID.randomUUID(),
        role.name().toLowerCase(java.util.Locale.ROOT),
        UUID.randomUUID(),
        role,
        Set.of(),
        false);
  }

  private static Actor actor(ArgosPrincipal p) {
    return new Actor(Actor.Type.USER, p.userId().toString(), p.username(), "127.0.0.1", null);
  }

  private CommandRouter.Issued issue(ArgosPrincipal p, int instance, String type) {
    return router.issue(p, actor(p), instance, type, null);
  }

  private static ErrorCode code(Throwable t) {
    return ((ApiException) t).errorCode();
  }

  @Test
  @DisplayName(
      "T-SV-041a a request is queued with a UUIDv4 id, sent on the instance's session as the"
          + " closed-list Command (ttl 60 s, issued_at = server clock) and audited REQUESTED, SENT")
  void issueAndSend() {
    CommandRouter.Issued issued = issue(operator, INSTANCE, "thread_dump");
    assertThat(issued.status()).isEqualTo("queued");
    assertThat(issued.ttlS()).isEqualTo(60);
    assertThat(issued.commandId().version()).isEqualTo(4);
    assertThat(messenger.sent).hasSize(1);
    Sent s = messenger.sent.get(0);
    assertThat(s.instanceId()).isEqualTo(INSTANCE);
    assertThat(s.command().getCommandId()).isEqualTo(issued.commandId().toString());
    assertThat(s.command().getType()).isEqualTo(CommandType.THREAD_DUMP);
    assertThat(s.command().getTtlS()).isEqualTo(60);
    assertThat(s.command().getIssuedAtMs()).isEqualTo(clock.millis());
    CommandStore.CommandRow row = store.find(issued.commandId()).orElseThrow();
    assertThat(row.status()).isEqualTo(CommandStore.SENT);
    assertThat(row.requestedBy()).isEqualTo(operator.userId());
    assertThat(row.expiresAt()).isEqualTo(clock.instant().plusSeconds(60));
    assertThat(audit.events())
        .containsExactly(AuditEvent.COMMAND_REQUESTED, AuditEvent.COMMAND_SENT);
    assertThat(pushes).hasSize(2);
    assertThat(pushes.get(1))
        .contains("\"status\":\"sent\"")
        .contains(issued.commandId().toString());
  }

  @Test
  @DisplayName(
      "T-SEC-21a role split: operator may request thread_dump, thread_stack, self_diagnosis but"
          + " not env_dump or reload_config (403, COMMAND_REJECTED); admin all five; viewer none;"
          + " an unknown type is 400")
  void roleSplit() {
    Map<String, Map<String, String>> args =
        Map.of(
            "thread_dump", Map.of(),
            "thread_stack", Map.of("thread_id", "42"),
            "self_diagnosis", Map.of(),
            "reload_config", Map.of(),
            "env_dump", Map.of());
    Map<Role, Set<String>> allowed =
        Map.of(
            Role.VIEWER, Set.of(),
            Role.OPERATOR, Set.of("thread_dump", "thread_stack", "self_diagnosis"),
            Role.ADMIN, args.keySet());
    for (Role role : List.of(Role.VIEWER, Role.OPERATOR, Role.ADMIN)) {
      ArgosPrincipal p = principal(role);
      for (Map.Entry<String, Map<String, String>> e : args.entrySet()) {
        router =
            router(
                new CommandRouter.Settings(
                    Duration.ofSeconds(60),
                    Duration.ofSeconds(120),
                    100,
                    1000,
                    100,
                    Duration.ofDays(30)),
                cipher);
        if (allowed.get(role).contains(e.getKey())) {
          CommandRouter.Issued issued =
              router.issue(p, actor(p), INSTANCE, e.getKey(), e.getValue());
          assertThat(issued.status()).as(role + " " + e.getKey()).isEqualTo("queued");
        } else {
          assertThatThrownBy(() -> router.issue(p, actor(p), INSTANCE, e.getKey(), e.getValue()))
              .as(role + " " + e.getKey())
              .satisfies(t -> assertThat(code(t)).isEqualTo(ErrorCode.FORBIDDEN));
        }
      }
    }
    assertThat(audit.rows.stream().filter(r -> r.event() == AuditEvent.COMMAND_REJECTED))
        .hasSize(5 + 2)
        .allSatisfy(r -> assertThat(r.result().reason()).isEqualTo("forbidden"));
    assertThatThrownBy(() -> issue(admin, INSTANCE, "heap_dump"))
        .satisfies(t -> assertThat(code(t)).isEqualTo(ErrorCode.INVALID_REQUEST));
    assertThatThrownBy(() -> issue(admin, INSTANCE, null))
        .satisfies(t -> assertThat(code(t)).isEqualTo(ErrorCode.INVALID_REQUEST));
  }

  @Test
  @DisplayName(
      "T-SV-041b no live session → 409 instance_offline (audited), unknown instance → 404;"
          + " nothing is stored or sent")
  void offline() {
    messenger.live.remove(INSTANCE);
    assertThatThrownBy(() -> issue(operator, INSTANCE, "thread_dump"))
        .satisfies(t -> assertThat(code(t)).isEqualTo(ErrorCode.INSTANCE_OFFLINE));
    assertThat(ErrorCode.INSTANCE_OFFLINE.code()).isEqualTo("instance_offline");
    assertThat(ErrorCode.INSTANCE_OFFLINE.status()).isEqualTo(409);
    assertThatThrownBy(() -> issue(operator, 4242, "thread_dump"))
        .satisfies(t -> assertThat(code(t)).isEqualTo(ErrorCode.NOT_FOUND));
    assertThat(store.rows).isEmpty();
    assertThat(messenger.sent).isEmpty();
    assertThat(audit.rows)
        .singleElement()
        .satisfies(r -> assertThat(r.result().reason()).isEqualTo("instance_offline"));
  }

  @Test
  @DisplayName(
      "T-SV-041c one command in flight per instance: a second request is 409 invalid_state until"
          + " the first finishes; another instance is independent")
  void oneInFlight() {
    CommandRouter.Issued first = issue(operator, INSTANCE, "thread_dump");
    assertThatThrownBy(() -> issue(admin, INSTANCE, "env_dump"))
        .satisfies(
            t -> {
              assertThat(code(t)).isEqualTo(ErrorCode.INVALID_STATE);
              assertThat(((ApiException) t).details().get(0).reason())
                  .isEqualTo("command_in_flight");
            });
    assertThat(issue(operator, OTHER, "thread_dump").status()).isEqualTo("queued");
    router.onResult(INSTANCE, CommandReplies.rejected(command(first)).get(0));
    assertThat(issue(operator, INSTANCE, "self_diagnosis").status()).isEqualTo("queued");
  }

  @Test
  @DisplayName(
      "T-SV-041d 10 per instance per minute: the 11th is 429 rate_limited with Retry-After;"
          + " a minute after the first, requests pass again")
  void ratePerMinute() {
    router =
        router(
            new CommandRouter.Settings(
                Duration.ofSeconds(60), Duration.ofSeconds(120), 100, 10, 100, Duration.ofDays(30)),
            cipher);
    for (int i = 0; i < 10; i++) {
      issue(operator, INSTANCE, "thread_dump");
      clock.advance(Duration.ofSeconds(1));
    }
    assertThatThrownBy(() -> issue(operator, INSTANCE, "thread_dump"))
        .satisfies(
            t -> {
              assertThat(code(t)).isEqualTo(ErrorCode.RATE_LIMITED);
              assertThat(((ApiException) t).headers()).containsEntry("Retry-After", "50");
            });
    assertThat(issue(operator, OTHER, "thread_dump").status()).isEqualTo("queued");
    clock.advance(Duration.ofSeconds(50));
    assertThat(issue(operator, INSTANCE, "thread_dump").status()).isEqualTo("queued");
  }

  @Test
  @DisplayName(
      "T-SEC-24b arguments: thread_stack takes exactly one of thread_id, tx_id (digits, ≤ 256 B);"
          + " other commands take none; at most 8; values never reach the audit log")
  void arguments() {
    assertThatThrownBy(
            () -> router.issue(operator, actor(operator), INSTANCE, "thread_stack", null))
        .satisfies(t -> assertThat(code(t)).isEqualTo(ErrorCode.INVALID_REQUEST));
    assertThatThrownBy(
            () ->
                router.issue(
                    operator,
                    actor(operator),
                    INSTANCE,
                    "thread_stack",
                    Map.of("thread_id", "1", "tx_id", "2")))
        .satisfies(t -> assertThat(code(t)).isEqualTo(ErrorCode.INVALID_REQUEST));
    assertThatThrownBy(
            () ->
                router.issue(
                    operator,
                    actor(operator),
                    INSTANCE,
                    "thread_stack",
                    Map.of("class", "java.lang.Runtime")))
        .satisfies(t -> assertThat(code(t)).isEqualTo(ErrorCode.INVALID_REQUEST));
    assertThatThrownBy(
            () ->
                router.issue(
                    operator,
                    actor(operator),
                    INSTANCE,
                    "thread_stack",
                    Map.of("thread_id", "1; rm -rf /")))
        .satisfies(t -> assertThat(code(t)).isEqualTo(ErrorCode.INVALID_REQUEST));
    assertThatThrownBy(
            () ->
                router.issue(
                    operator, actor(operator), INSTANCE, "thread_dump", Map.of("tx_id", "1")))
        .satisfies(t -> assertThat(code(t)).isEqualTo(ErrorCode.INVALID_REQUEST));
    Map<String, String> nine = new LinkedHashMap<>();
    for (int i = 0; i < 9; i++) {
      nine.put("k" + i, "1");
    }
    assertThatThrownBy(
            () -> router.issue(operator, actor(operator), INSTANCE, "thread_stack", nine))
        .satisfies(t -> assertThat(code(t)).isEqualTo(ErrorCode.LIMIT_EXCEEDED));
    assertThat(store.rows).isEmpty();

    CommandRouter.Issued ok =
        router.issue(
            operator,
            actor(operator),
            INSTANCE,
            "thread_stack",
            Map.of("tx_id", "9007199254740993"));
    assertThat(messenger.sent.get(0).command().getArgsMap())
        .containsExactly(Map.entry("tx_id", "9007199254740993"));
    assertThat(store.find(ok.commandId()).orElseThrow().args())
        .containsEntry("tx_id", "9007199254740993");
    assertThat(audit.rows.toString()).doesNotContain("9007199254740993");
  }

  @Test
  @DisplayName(
      "T-SEC-24a a command not sent within its TTL expires (error ttl) and is never delivered,"
          + " even when the session becomes ready afterwards; before the TTL it is retried")
  void ttlNeverDelivered() {
    messenger.ready = false;
    CommandRouter.Issued issued = issue(operator, INSTANCE, "thread_dump");
    assertThat(store.find(issued.commandId()).orElseThrow().status()).isEqualTo("QUEUED");
    clock.advance(Duration.ofSeconds(59));
    router.tick();
    assertThat(store.find(issued.commandId()).orElseThrow().status()).isEqualTo("QUEUED");
    assertThat(messenger.attempts).isEqualTo(2);
    clock.advance(Duration.ofSeconds(1));
    router.tick();
    CommandStore.CommandRow row = store.find(issued.commandId()).orElseThrow();
    assertThat(row.status()).isEqualTo("EXPIRED");
    assertThat(row.errorCode()).isEqualTo("ttl");
    messenger.ready = true;
    router.tick();
    router.send(issued.commandId());
    assertThat(messenger.sent).isEmpty();
    assertThat(audit.events()).contains(AuditEvent.COMMAND_EXPIRED);
    assertThat(router.inflightCount()).isZero();
  }

  @Test
  @DisplayName(
      "T-SEC-24c a sent command without a result for 120 s expires (error timeout); a late"
          + " result is dropped and counted, nothing is stored")
  void resultTimeout() {
    CommandRouter.Issued issued = issue(operator, INSTANCE, "thread_dump");
    clock.advance(Duration.ofSeconds(119));
    router.tick();
    assertThat(store.find(issued.commandId()).orElseThrow().status()).isEqualTo("SENT");
    clock.advance(Duration.ofSeconds(1));
    router.tick();
    CommandStore.CommandRow row = store.find(issued.commandId()).orElseThrow();
    assertThat(row.status()).isEqualTo("EXPIRED");
    assertThat(row.errorCode()).isEqualTo("timeout");
    for (CommandResult r : CommandReplies.threadDumpText(command(issued), SENTINEL)) {
      router.onResult(INSTANCE, r);
    }
    assertThat(store.results).isEmpty();
    assertThat(meters.counter("argos.commands.results.dropped", "reason", "unknown").count())
        .isEqualTo(1.0);
  }

  @Test
  @DisplayName("T-SV-041e the end of the instance's session expires its queued and sent command")
  void sessionEnd() {
    CommandRouter.Issued sent = issue(operator, INSTANCE, "thread_dump");
    messenger.ready = false;
    CommandRouter.Issued queued = issue(operator, OTHER, "thread_dump");
    router.onSessionEnd(INSTANCE, 1);
    router.onSessionEnd(OTHER, 1);
    assertThat(store.find(sent.commandId()).orElseThrow().errorCode()).isEqualTo("session_ended");
    assertThat(store.find(queued.commandId()).orElseThrow().errorCode()).isEqualTo("session_ended");
    assertThat(store.find(queued.commandId()).orElseThrow().status()).isEqualTo("EXPIRED");
  }

  @Test
  @DisplayName(
      "T-SV-041f chunks are assembled in index order whatever their arrival order, then stored"
          + " gzip-compressed and encrypted (no plaintext in the stored bytes); DONE, audited"
          + " COMPLETED; duplicates are ignored")
  void assemblyAndEncryption() {
    CommandRouter.Issued issued = issue(operator, INSTANCE, "self_diagnosis");
    List<CommandResult> chunks = CommandReplies.chunked(command(issued), 600 * 1024, SENTINEL);
    assertThat(chunks).hasSize(3);
    router.onResult(INSTANCE, chunks.get(2));
    router.onResult(INSTANCE, chunks.get(0));
    router.onResult(INSTANCE, chunks.get(0));
    router.onResult(INSTANCE, chunks.get(1));
    CommandStore.CommandRow row = store.find(issued.commandId()).orElseThrow();
    assertThat(row.status()).isEqualTo("DONE");
    CommandStore.StoredResult stored = store.results.get(issued.commandId());
    assertThat(stored.size()).isEqualTo(600 * 1024);
    assertThat(stored.truncated()).isFalse();
    assertThat(stored.envelope()).startsWith("v1:").doesNotContain(SENTINEL);
    byte[] plain =
        CommandRouter.gunzip(
            cipher.decrypt(stored.envelope(), CommandRouter.aad(issued.commandId())));
    byte[] expected = new byte[600 * 1024];
    int at = 0;
    for (CommandResult c : chunks) {
      byte[] b = c.getPayload().toByteArray();
      System.arraycopy(b, 0, expected, at, b.length);
      at += b.length;
    }
    assertThat(plain).isEqualTo(expected);
    // Another row's associated data does not open it (16 §8.3).
    assertThatThrownBy(
            () -> cipher.decrypt(stored.envelope(), CommandRouter.aad(UUID.randomUUID())))
        .isInstanceOf(io.argos.server.secrets.SecretUnavailableException.class);
    assertThat(audit.events()).contains(AuditEvent.COMMAND_COMPLETED);
    assertThat(
            meters.counter("argos.commands.results.dropped", "reason", "duplicate_chunk").count())
        .isEqualTo(1.0);
    assertThat(pushes.get(pushes.size() - 1)).contains("\"status\":\"done\"");
  }

  @Test
  @DisplayName(
      "T-SV-041g the assembly is bounded: more than 1 MiB is cut and marked truncated, the agent's"
          + " truncated flag is kept, more than 64 chunks or an index outside the count fail"
          + " (invalid_result)")
  void assemblyBounds() {
    CommandRouter.Issued big = issue(operator, INSTANCE, "self_diagnosis");
    byte[] payload = new byte[5 * CommandReplies.CHUNK_BYTES];
    List<CommandResult> chunks =
        CommandReplies.chunks(command(big), CommandReplies.TEXT, payload, false, 0);
    assertThat(chunks).hasSize(5);
    chunks.forEach(c -> router.onResult(INSTANCE, c));
    CommandStore.StoredResult stored = store.results.get(big.commandId());
    assertThat(stored.size()).isEqualTo(CommandRouter.MAX_RESULT_BYTES);
    assertThat(stored.truncated()).isTrue();

    CommandRouter.Issued cut = issue(operator, INSTANCE, "thread_dump");
    CommandReplies.truncatedAtLimit(command(cut), SENTINEL)
        .forEach(c -> router.onResult(INSTANCE, c));
    assertThat(store.results.get(cut.commandId()).truncated()).isTrue();
    assertThat(store.results.get(cut.commandId()).size())
        .isEqualTo(CommandReplies.MAX_RESULT_BYTES);

    CommandRouter.Issued many = issue(operator, INSTANCE, "thread_dump");
    router.onResult(
        INSTANCE,
        CommandResult.newBuilder()
            .setCommandId(many.commandId().toString())
            .setStatus(CommandStatus.OK)
            .setChunkIndex(0)
            .setChunkCount(65)
            .setPayload(ByteString.copyFromUtf8("x"))
            .build());
    assertThat(store.find(many.commandId()).orElseThrow().errorCode()).isEqualTo("invalid_result");

    CommandRouter.Issued outside = issue(operator, INSTANCE, "thread_dump");
    router.onResult(
        INSTANCE,
        CommandResult.newBuilder()
            .setCommandId(outside.commandId().toString())
            .setStatus(CommandStatus.OK)
            .setChunkIndex(2)
            .setChunkCount(2)
            .build());
    assertThat(store.find(outside.commandId()).orElseThrow().status()).isEqualTo("FAILED");
  }

  @Test
  @DisplayName(
      "T-SV-041h a result counts only from the instance the command was sent to; unknown and"
          + " malformed command ids are dropped and counted")
  void senderChecks() {
    CommandRouter.Issued issued = issue(operator, INSTANCE, "thread_dump");
    for (CommandResult r : CommandReplies.threadDumpText(command(issued), SENTINEL)) {
      router.onResult(OTHER, r);
    }
    assertThat(store.find(issued.commandId()).orElseThrow().status()).isEqualTo("SENT");
    router.onResult(
        INSTANCE,
        CommandResult.newBuilder()
            .setCommandId(UUID.randomUUID().toString())
            .setStatus(CommandStatus.OK)
            .build());
    router.onResult(
        INSTANCE,
        CommandResult.newBuilder().setCommandId("../../etc").setStatus(CommandStatus.OK).build());
    assertThat(meters.counter("argos.commands.results.dropped", "reason", "wrong_instance").count())
        .isEqualTo(1.0);
    assertThat(meters.counter("argos.commands.results.dropped", "reason", "unknown").count())
        .isEqualTo(2.0);
    assertThat(store.results).isEmpty();
  }

  @Test
  @DisplayName(
      "T-SEC-24d agent refusals: COOLDOWN → failed/cooldown and REJECTED → failed/rejected, both"
          + " audited COMMAND_REJECTED; FAILED → agent_failed; EXPIRED → expired/agent_expired")
  void agentRefusals() {
    CommandRouter.Issued cooldown = issue(operator, INSTANCE, "thread_dump");
    router.onResult(INSTANCE, CommandReplies.cooldown(command(cooldown)).get(0));
    assertThat(store.find(cooldown.commandId()).orElseThrow())
        .satisfies(
            r -> {
              assertThat(r.status()).isEqualTo("FAILED");
              assertThat(r.errorCode()).isEqualTo("cooldown");
            });
    CommandRouter.Issued rejected = issue(operator, INSTANCE, "thread_dump");
    router.onResult(INSTANCE, CommandReplies.rejected(command(rejected)).get(0));
    assertThat(store.find(rejected.commandId()).orElseThrow().errorCode()).isEqualTo("rejected");
    assertThat(
            audit.rows.stream()
                .filter(r -> r.event() == AuditEvent.COMMAND_REJECTED)
                .map(r -> r.result().reason()))
        .containsExactly("cooldown", "rejected");
    CommandRouter.Issued failed = issue(operator, INSTANCE, "thread_dump");
    router.onResult(INSTANCE, status(failed, CommandStatus.FAILED));
    assertThat(store.find(failed.commandId()).orElseThrow().errorCode()).isEqualTo("agent_failed");
    CommandRouter.Issued expired = issue(operator, INSTANCE, "thread_dump");
    router.onResult(INSTANCE, status(expired, CommandStatus.EXPIRED));
    assertThat(store.find(expired.commandId()).orElseThrow().status()).isEqualTo("EXPIRED");
    assertThat(store.find(expired.commandId()).orElseThrow().errorCode())
        .isEqualTo("agent_expired");
  }

  @Test
  @DisplayName(
      "T-SEC-22a without a master key a command is refused before anything is stored or sent"
          + " (409 secret_unavailable)")
  void noMasterKey() {
    router =
        router(
            CommandRouter.Settings.defaults(), new EnvelopeCipher(new MasterKeyRing(null, null)));
    assertThatThrownBy(() -> issue(operator, INSTANCE, "thread_dump"))
        .satisfies(t -> assertThat(code(t)).isEqualTo(ErrorCode.SECRET_UNAVAILABLE));
    assertThat(store.rows).isEmpty();
    assertThat(messenger.sent).isEmpty();
  }

  @Test
  @DisplayName(
      "T-SEC-50a the result is for the requester or an administrator (403 not_owner otherwise);"
          + " env_dump results for administrators only; views are audited RESULT_VIEWED and"
          + " SENSITIVE_VIEW (thread_dump, env_dump); a thread dump is parsed, format=text is raw")
  void resultViews() {
    CommandRouter.Issued dump = issue(operator, INSTANCE, "thread_dump");
    CommandReplies.threadDumpJson(command(dump), SENTINEL)
        .forEach(r -> router.onResult(INSTANCE, r));

    ArgosPrincipal otherOperator = principal(Role.OPERATOR);
    assertThatThrownBy(
            () -> router.result(otherOperator, actor(otherOperator), dump.commandId(), null))
        .satisfies(t -> assertThat(code(t)).isEqualTo(ErrorCode.NOT_OWNER));
    assertThatThrownBy(() -> router.find(otherOperator, dump.commandId()))
        .satisfies(t -> assertThat(code(t)).isEqualTo(ErrorCode.NOT_OWNER));
    assertThatThrownBy(() -> router.result(operator, actor(operator), UUID.randomUUID(), null))
        .satisfies(t -> assertThat(code(t)).isEqualTo(ErrorCode.NOT_FOUND));

    audit.rows.clear();
    CommandRouter.ResultView json =
        router.result(operator, actor(operator), dump.commandId(), null);
    assertThat(json.threads()).isNotNull();
    assertThat(json.threads().threads()).hasSize(2);
    assertThat(json.threads().threads().get(0).frames().get(0)).contains(SENTINEL);
    assertThat(json.threads().threads().get(0).txId()).isEqualTo("18446744073709551615");
    assertThat(json.threads().deadlocks())
        .singleElement()
        .satisfies(d -> assertThat(d.threadIds()).containsExactly(1L, 2L));
    assertThat(audit.events())
        .containsExactly(AuditEvent.COMMAND_RESULT_VIEWED, AuditEvent.SENSITIVE_VIEW);
    assertThat(audit.rows.get(1).after()).containsEntry("type", "thread_dump");

    CommandRouter.ResultView text = router.result(admin, actor(admin), dump.commandId(), "text");
    assertThat(text.threads()).isNull();
    assertThat(text.text()).isEqualTo(CommandReplies.threadDumpJsonPayload(SENTINEL));
    assertThatThrownBy(() -> router.result(admin, actor(admin), dump.commandId(), "html"))
        .satisfies(t -> assertThat(code(t)).isEqualTo(ErrorCode.INVALID_REQUEST));

    CommandRouter.Issued env = issue(admin, INSTANCE, "env_dump");
    router.onResult(
        INSTANCE,
        CommandReplies.ok(
                command(env),
                CommandReplies.TEXT,
                "PATH=/usr/bin\nDB_PASSWORD=****(len=8)\n".getBytes(StandardCharsets.UTF_8))
            .get(0));
    audit.rows.clear();
    assertThat(router.result(admin, actor(admin), env.commandId(), null).text())
        .contains("DB_PASSWORD=****(len=8)");
    assertThat(audit.rows.get(1).after()).containsEntry("type", "env_dump");

    CommandRouter.Issued pending = issue(operator, INSTANCE, "thread_dump");
    assertThatThrownBy(() -> router.result(operator, actor(operator), pending.commandId(), null))
        .satisfies(t -> assertThat(code(t)).isEqualTo(ErrorCode.INVALID_STATE));
  }

  @Test
  @DisplayName(
      "the text thread dump parser reads names, ids, states, virtual threads, frames, locks and"
          + " waits; the JSON parser bounds frames at 64")
  void threadDumpParsers() {
    ThreadDump text =
        ThreadDump.parse(
            "text/plain",
            CommandReplies.threadDumpTextPayload(SENTINEL).getBytes(StandardCharsets.UTF_8));
    assertThat(text.threads()).hasSize(3);
    ThreadDump.ThreadInfo main = text.threads().get(0);
    assertThat(main.name()).isEqualTo("main-" + SENTINEL);
    assertThat(main.id()).isEqualTo(1L);
    assertThat(main.state()).isEqualTo("RUNNABLE");
    assertThat(main.frames()).hasSize(2);
    assertThat(main.locksHeld()).hasSize(1);
    assertThat(text.threads().get(1).waitingOn()).contains("0x00000000c0010000");
    assertThat(text.threads().get(2).virtual()).isTrue();
    StringBuilder json = new StringBuilder("{\"threads\":[{\"id\":1,\"name\":\"t\",\"frames\":[");
    for (int i = 0; i < 100; i++) {
      json.append(i == 0 ? "" : ",").append("\"f").append(i).append('"');
    }
    json.append("]}]}");
    ThreadDump bounded =
        ThreadDump.parse("application/json", json.toString().getBytes(StandardCharsets.UTF_8));
    assertThat(bounded.threads().get(0).frames()).hasSize(ThreadDump.MAX_FRAMES);
    assertThat(bounded.cut()).isTrue();
    assertThat(
            ThreadDump.parse("application/json", "not json".getBytes(StandardCharsets.UTF_8))
                .threads())
        .isEmpty();
  }

  @Test
  @DisplayName("the retention deletes commands older than 30 days and beyond 100 per instance")
  void retention() {
    router.retention();
    assertThat(store.deletedBefore).isEqualTo(clock.instant().minus(Duration.ofDays(30)));
    assertThat(store.trimmedTo).isEqualTo(100);
  }

  // --- Helpers ---------------------------------------------------------------------------------

  private Command command(CommandRouter.Issued issued) {
    return messenger.sent.stream()
        .map(Sent::command)
        .filter(c -> c.getCommandId().equals(issued.commandId().toString()))
        .findFirst()
        .orElseThrow();
  }

  private static CommandResult status(CommandRouter.Issued issued, CommandStatus status) {
    return CommandResult.newBuilder()
        .setCommandId(issued.commandId().toString())
        .setStatus(status)
        .setChunkCount(1)
        .build();
  }

  record Sent(int instanceId, Command command) {}

  static final class FakeMessenger implements SessionMessenger {
    final Set<Integer> live = new HashSet<>();
    final List<Sent> sent = new ArrayList<>();
    boolean ready = true;
    int attempts;

    @Override
    public boolean requestDictionary(
        int appId, int instanceId, io.argos.proto.v1.DictionaryRequest request) {
      return false;
    }

    @Override
    public boolean close(int instanceId, io.argos.proto.v1.SessionClose close) {
      return false;
    }

    @Override
    public boolean hasLiveSession(int instanceId) {
      return live.contains(instanceId);
    }

    @Override
    public boolean sendCommand(int instanceId, Command command) {
      attempts++;
      if (!ready || !live.contains(instanceId)) {
        return false;
      }
      sent.add(new Sent(instanceId, command));
      return true;
    }
  }

  record AuditRow(
      AuditEvent event,
      Actor actor,
      Target target,
      AuditResult result,
      Map<String, Object> after) {}

  static final class RecordingAudit implements AuditService {
    final List<AuditRow> rows = new ArrayList<>();

    @Override
    public void record(
        AuditEvent event,
        Actor actor,
        Target target,
        AuditResult result,
        Map<String, ?> before,
        Map<String, ?> after) {
      rows.add(
          new AuditRow(
              event,
              actor,
              target,
              result,
              after == null ? Map.of() : new LinkedHashMap<String, Object>(after)));
    }

    List<AuditEvent> events() {
      return rows.stream().map(AuditRow::event).toList();
    }
  }

  /** {@link CommandStore} in memory with the repository's conditional transitions. */
  static final class MemoryStore implements CommandStore {
    final Map<UUID, CommandRow> rows = new ConcurrentHashMap<>();
    final Map<UUID, StoredResult> results = new ConcurrentHashMap<>();
    final Set<Integer> instances = new HashSet<>();
    Instant deletedBefore;
    int trimmedTo;

    @Override
    public void insert(NewCommand c) {
      rows.put(
          c.id(),
          new CommandRow(
              c.id(),
              c.instanceId(),
              c.type(),
              c.args(),
              c.requestedBy(),
              c.requestedAt(),
              c.expiresAt(),
              QUEUED,
              null,
              null,
              null,
              null,
              null,
              null,
              null));
    }

    @Override
    public boolean markSent(UUID id, Instant sentAt) {
      CommandRow r = rows.get(id);
      if (r == null || !QUEUED.equals(r.status())) {
        return false;
      }
      rows.put(id, with(r, SENT, sentAt, null, null, null));
      return true;
    }

    @Override
    public boolean finish(
        UUID id, String status, String errorCode, Instant at, Integer durationMs) {
      CommandRow r = rows.get(id);
      if (r == null || !(QUEUED.equals(r.status()) || SENT.equals(r.status()))) {
        return false;
      }
      rows.put(id, with(r, status, r.sentAt(), at, durationMs, errorCode));
      return true;
    }

    @Override
    public boolean complete(UUID id, Instant at, Integer durationMs, StoredResult result) {
      if (!finish(id, DONE, null, at, durationMs)) {
        return false;
      }
      results.put(id, result);
      CommandRow r = rows.get(id);
      rows.put(
          id,
          new CommandRow(
              r.id(),
              r.instanceId(),
              r.type(),
              r.args(),
              r.requestedBy(),
              r.requestedAt(),
              r.expiresAt(),
              r.status(),
              r.sentAt(),
              r.finishedAt(),
              r.durationMs(),
              r.errorCode(),
              result.size(),
              result.truncated(),
              result.contentType()));
      return true;
    }

    private static CommandRow with(
        CommandRow r,
        String status,
        Instant sentAt,
        Instant finishedAt,
        Integer duration,
        String error) {
      return new CommandRow(
          r.id(),
          r.instanceId(),
          r.type(),
          r.args(),
          r.requestedBy(),
          r.requestedAt(),
          r.expiresAt(),
          status,
          sentAt,
          finishedAt,
          duration,
          error,
          null,
          null,
          null);
    }

    @Override
    public Optional<CommandRow> find(UUID id) {
      return Optional.ofNullable(rows.get(id));
    }

    @Override
    public List<CommandRow> listByInstance(int instanceId, int limit) {
      return rows.values().stream().filter(r -> r.instanceId() == instanceId).limit(limit).toList();
    }

    @Override
    public Optional<StoredResult> result(UUID id) {
      return Optional.ofNullable(results.get(id));
    }

    @Override
    public List<Expired> expireOverdue(Instant now, Instant sentBefore) {
      return List.of();
    }

    @Override
    public int deleteOlderThan(Instant cutoff) {
      deletedBefore = cutoff;
      return 0;
    }

    @Override
    public int trimPerInstance(int keep) {
      trimmedTo = keep;
      return 0;
    }

    @Override
    public boolean instanceExists(int instanceId) {
      return instances.contains(instanceId);
    }
  }
}
