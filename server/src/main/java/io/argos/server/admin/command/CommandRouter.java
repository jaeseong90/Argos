/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.admin.command;

import io.argos.proto.v1.Command;
import io.argos.proto.v1.CommandResult;
import io.argos.server.admin.AdminPush;
import io.argos.server.api.error.ApiException;
import io.argos.server.api.error.ErrorCode;
import io.argos.server.audit.Actor;
import io.argos.server.audit.AuditEvent;
import io.argos.server.audit.AuditResult;
import io.argos.server.audit.AuditService;
import io.argos.server.audit.Target;
import io.argos.server.auth.ArgosPrincipal;
import io.argos.server.auth.ObjectAccessPolicy;
import io.argos.server.auth.Role;
import io.argos.server.secrets.EnvelopeCipher;
import io.argos.server.secrets.SecretField;
import io.argos.server.secrets.SecretUnavailableException;
import io.argos.server.session.api.CommandResultSink;
import io.argos.server.session.api.SessionEndListener;
import io.argos.server.session.api.SessionMessenger;
import io.argos.server.storage.postgres.command.CommandStore;
import io.argos.server.storage.postgres.command.CommandStore.CommandRow;
import io.argos.server.storage.postgres.command.CommandStore.StoredResult;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Routes remote commands to agents and stores their results (12 §6.1, 16 §5.2, §5.3, 11 §8.2,
 * ADR-0010 C-103; WO-26).
 *
 * <p><b>Request</b> ({@link #issue}): the closed list {@link CommandKind} (400 otherwise) → the
 * role (operator: thread dump, thread stack, self diagnosis; administrator: config reload,
 * environment dump; 403) → the arguments ({@code thread_id} or {@code tx_id} of a thread stack
 * only, at most {@value #MAX_ARGS}, values at most {@value #MAX_ARG_BYTES} bytes of digits; 400) →
 * a master key for the result ({@code 409 secret_unavailable} before anything is sent) → a live
 * session ({@code 404} unknown instance, {@code 409 instance_offline}) → one command in flight per
 * instance ({@code argos.commands.max-inflight-per-instance}, {@code 409 invalid_state}) → at most
 * {@code argos.commands.max-per-minute-per-instance} per instance per minute ({@code 429} with
 * {@code Retry-After}) → a {@code commands} row ({@code QUEUED}, UUIDv4) → audit {@code
 * COMMAND_REQUESTED} → the {@code Command} on the instance's session ({@code SENT}, audit {@code
 * COMMAND_SENT}). A server refusal is audited {@code COMMAND_REJECTED} with its code.
 *
 * <p><b>Results</b> ({@link #onResult}): only for a command in flight and only from the instance it
 * was sent to (anything else is dropped and counted); {@code OK} chunks are assembled per command
 * (at most {@value #MAX_CHUNKS} chunks and {@value #MAX_RESULT_BYTES} bytes, cut and marked
 * truncated beyond), then gzip-compressed, encrypted with the {@link EnvelopeCipher} (associated
 * data {@code command_results:body_enc:<id>}) and stored ({@code DONE}, audit {@code
 * COMMAND_COMPLETED}). {@code REJECTED} and {@code COOLDOWN} make the command {@code FAILED} with
 * error {@code rejected} / {@code cooldown} (audit {@code COMMAND_REJECTED}); {@code FAILED} →
 * {@code agent_failed}; {@code EXPIRED} → {@code EXPIRED} / {@code agent_expired}.
 *
 * <p><b>Expiry</b> (WO-26 assumption on 12 §6.1 and 16 §5.2): a command not sent within {@code
 * argos.commands.ttl} (60 s) expires with error {@code ttl} and is never delivered (SEC-24); a sent
 * command without a complete result within {@code argos.commands.result-timeout} (120 s) expires
 * with error {@code timeout}; the end of the instance's session expires its queued and sent
 * commands ({@code session_ended}). Checked every second ({@link #tick}) on the injected clock;
 * rows this node no longer tracks (a restart) are expired by the same rules in the database.
 * Retention ({@link #retention}): {@code argos.retention.command-results} (30 days) and the newest
 * {@code argos.commands.keep-per-instance} (100) commands per instance.
 *
 * <p><b>Threads.</b> Requests run on the caller's thread; results, sends, expiry and retention run
 * on the single {@code argos-command} worker ({@code worker}), so the transitions of one command
 * happen in order (a result is handled after the send it answers). The in-memory state (commands in
 * flight, the per-instance minute windows) is bounded by the instances with a live session and
 * guarded by one lock.
 *
 * <p><b>Secrecy.</b> Result payloads and argument values are never logged, audited or pushed; the
 * plaintext of a result exists only while it is assembled and while a view is built.
 */
public class CommandRouter implements CommandResultSink, SessionEndListener {

  /** Arguments of a command at most (11 §8.2). */
  public static final int MAX_ARGS = 8;

  /** UTF-8 bytes of an argument value at most (11 §8.2). */
  public static final int MAX_ARG_BYTES = 256;

  /** Bytes of an assembled result at most (11 §8.2). */
  public static final int MAX_RESULT_BYTES = 1024 * 1024;

  /** Chunks of one result at most (the agent sends four of 256 KiB at most). */
  public static final int MAX_CHUNKS = 64;

  /** Commands of an instance listed at most ({@code GET /instances/{instanceId}/commands}). */
  public static final int MAX_LIST = 100;

  /** The argument names (THREAD_STACK, 11 §8.2). */
  static final List<String> ARG_NAMES = List.of("thread_id", "tx_id");

  private static final Pattern DIGITS = Pattern.compile("[0-9]{1,20}");
  private static final Pattern UUID_V4 =
      Pattern.compile("[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}");
  private static final long MINUTE_MS = 60_000;
  private static final String TEXT = "text/plain";
  private static final String JSON_TYPE = "application/json";
  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final Logger LOG = LoggerFactory.getLogger(CommandRouter.class);

  /**
   * The settings ({@code argos.commands.*}, {@code argos.retention.command-results}).
   *
   * @param ttl not sent within this long: expired, never delivered
   * @param resultTimeout sent without a complete result for this long: expired ({@code timeout})
   * @param maxInflight commands in flight per instance at most
   * @param maxPerMinute commands requested per instance per minute at most
   * @param keepPerInstance commands kept per instance at most
   * @param retention commands (and results) kept this long
   */
  public record Settings(
      Duration ttl,
      Duration resultTimeout,
      int maxInflight,
      int maxPerMinute,
      int keepPerInstance,
      Duration retention) {

    /** The defaults of 12 §6.1, 16 §5.2, §5.3. */
    public static Settings defaults() {
      return new Settings(
          Duration.ofSeconds(60), Duration.ofSeconds(120), 1, 10, 100, Duration.ofDays(30));
    }
  }

  /** The answer of {@link #issue}: {@code 202 {command_id, status: queued, ttl_s}}. */
  public record Issued(UUID commandId, String status, int ttlS) {}

  /**
   * A result for viewing ({@code GET /commands/{commandId}/result}).
   *
   * @param format {@code json} (parsed threads for a thread dump or stack, the text otherwise) or
   *     {@code text}
   * @param threads the parsed dump of a thread command in {@code json} format, else null
   * @param text the result as text (UTF-8) when not parsed, else null
   */
  public record ResultView(
      CommandRow command,
      CommandKind kind,
      String format,
      String contentType,
      boolean truncated,
      int sizeBytes,
      @Nullable ThreadDump threads,
      @Nullable String text) {}

  private final CommandStore store;
  private final SessionMessenger messenger;
  private final EnvelopeCipher cipher;
  private final AuditService audit;
  private final AdminPush push;
  private final Clock clock;
  private final Settings settings;
  private final Executor worker;
  private final MeterRegistry meters;

  private final Object lock = new Object();
  private final Map<UUID, InFlight> inflight = new HashMap<>();
  private final Map<Integer, Deque<Long>> recent = new HashMap<>();

  public CommandRouter(
      CommandStore store,
      SessionMessenger messenger,
      EnvelopeCipher cipher,
      AuditService audit,
      AdminPush push,
      Clock clock,
      Settings settings,
      Executor worker,
      MeterRegistry meters) {
    this.store = Objects.requireNonNull(store, "store");
    this.messenger = Objects.requireNonNull(messenger, "messenger");
    this.cipher = Objects.requireNonNull(cipher, "cipher");
    this.audit = Objects.requireNonNull(audit, "audit");
    this.push = Objects.requireNonNull(push, "push");
    this.clock = Objects.requireNonNull(clock, "clock");
    this.settings = Objects.requireNonNull(settings, "settings");
    this.worker = Objects.requireNonNull(worker, "worker");
    this.meters = Objects.requireNonNull(meters, "meters");
  }

  // --- Request ---------------------------------------------------------------------------------

  /**
   * Requests a command (14 §4.10 {@code POST /instances/{instanceId}/commands}).
   *
   * @param principal the requesting user
   * @param actor the audit actor of the request
   * @param instanceId the target instance
   * @param type the API name ({@code thread_dump}, ...)
   * @param args the arguments, null for none
   * @throws ApiException 400, 403, 404, 409, 429 as the class comment says
   */
  public Issued issue(
      ArgosPrincipal principal,
      Actor actor,
      int instanceId,
      @Nullable String type,
      @Nullable Map<String, String> args) {
    CommandKind kind =
        CommandKind.of(type)
            .orElseThrow(
                () ->
                    new ApiException(ErrorCode.INVALID_REQUEST, "The command type is not supported")
                        .detail("type", "unsupported_value"));
    if (!principal.hasRole(kind.role())) {
      refused(actor, instanceId, kind, "forbidden");
      throw new ApiException(ErrorCode.FORBIDDEN, "This command requires the administrator role");
    }
    Map<String, String> checked = checkArgs(kind, args);
    if (!cipher.available()) {
      refused(actor, instanceId, kind, "secret_unavailable");
      throw new ApiException(
          ErrorCode.SECRET_UNAVAILABLE, "Command results cannot be stored without a master key");
    }
    if (!messenger.hasLiveSession(instanceId)) {
      if (!store.instanceExists(instanceId)) {
        throw new ApiException(ErrorCode.NOT_FOUND);
      }
      refused(actor, instanceId, kind, "instance_offline");
      throw new ApiException(ErrorCode.INSTANCE_OFFLINE);
    }
    long now = clock.millis();
    UUID id = UUID.randomUUID();
    InFlight command =
        new InFlight(
            id,
            instanceId,
            kind,
            checked,
            principal.userId(),
            now,
            now + settings.ttl().toMillis());
    String refusal = null;
    long retryAfterS = 0;
    synchronized (lock) {
      if (inflightOf(instanceId) >= settings.maxInflight()) {
        refusal = "in_flight";
      } else {
        Deque<Long> window = recent.computeIfAbsent(instanceId, k -> new ArrayDeque<>());
        prune(window, now);
        if (window.size() >= settings.maxPerMinute()) {
          refusal = "rate_limited";
          retryAfterS = Math.max(1, (window.peekFirst() + MINUTE_MS - now + 999) / 1000);
        } else {
          window.addLast(now);
          inflight.put(id, command);
        }
      }
    }
    if ("in_flight".equals(refusal)) {
      refused(actor, instanceId, kind, refusal);
      throw new ApiException(
              ErrorCode.INVALID_STATE, "Another command of this instance is still running")
          .detail("instance_id", "command_in_flight");
    }
    if (refusal != null) {
      refused(actor, instanceId, kind, refusal);
      throw new ApiException(ErrorCode.RATE_LIMITED, "Too many commands for this instance")
          .header("Retry-After", Long.toString(retryAfterS));
    }
    try {
      store.insert(
          new CommandStore.NewCommand(
              id,
              instanceId,
              kind.wire(),
              checked,
              principal.userId(),
              Instant.ofEpochMilli(now),
              Instant.ofEpochMilli(command.expiresAtMs)));
    } catch (RuntimeException e) {
      synchronized (lock) {
        inflight.remove(id);
      }
      LOG.warn("A command row could not be stored: {}", e.getClass().getSimpleName());
      throw new ApiException(ErrorCode.STORAGE_UNAVAILABLE);
    }
    Map<String, Object> after = new LinkedHashMap<>();
    after.put("instance_id", instanceId);
    after.put("type", kind.wire());
    after.put("args", List.copyOf(checked.keySet()));
    audit.record(
        AuditEvent.COMMAND_REQUESTED, actor, target(id, kind), AuditResult.success(), null, after);
    publish(command, "queued", null, now);
    try {
      worker.execute(() -> send(id));
    } catch (RejectedExecutionException e) {
      // Stays queued: the next tick sends it while its TTL lasts.
    }
    return new Issued(id, "queued", ttlSeconds());
  }

  /**
   * The checked arguments: a thread stack takes exactly one of {@code thread_id}, {@code tx_id}.
   */
  static Map<String, String> checkArgs(CommandKind kind, @Nullable Map<String, String> args) {
    Map<String, String> in = args == null ? Map.of() : args;
    if (in.size() > MAX_ARGS) {
      throw new ApiException(ErrorCode.LIMIT_EXCEEDED, "Too many command arguments")
          .detail("args", "too_many");
    }
    if (kind != CommandKind.THREAD_STACK) {
      if (!in.isEmpty()) {
        throw new ApiException(ErrorCode.INVALID_REQUEST, "This command takes no arguments")
            .detail("args", "not_allowed");
      }
      return Map.of();
    }
    Map<String, String> out = new TreeMap<>();
    for (Map.Entry<String, String> e : in.entrySet()) {
      String key = e.getKey();
      String value = e.getValue();
      if (key == null || !ARG_NAMES.contains(key)) {
        throw new ApiException(ErrorCode.INVALID_REQUEST, "Unknown command argument")
            .detail("args", "unknown_argument");
      }
      if (value == null
          || value.getBytes(StandardCharsets.UTF_8).length > MAX_ARG_BYTES
          || !DIGITS.matcher(value).matches()) {
        throw new ApiException(ErrorCode.INVALID_REQUEST, "A command argument is invalid")
            .detail("args." + key, "invalid_format");
      }
      out.put(key, value);
    }
    if (out.size() != 1) {
      throw new ApiException(
              ErrorCode.INVALID_REQUEST, "A thread stack takes exactly one of thread_id, tx_id")
          .detail("args", "one_of_thread_id_tx_id");
    }
    return Map.copyOf(out);
  }

  // --- Reads ---------------------------------------------------------------------------------

  /**
   * A command for its requester or an administrator (14 §4.10 {@code GET /commands/{commandId}},
   * SEC-50).
   *
   * @throws ApiException 404 when it does not exist, 403 {@code not_owner} for anybody else
   */
  public CommandRow find(ArgosPrincipal principal, UUID id) {
    CommandRow row = store.find(id).orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
    ObjectAccessPolicy.requireOwnerOrAdmin(principal, row.requestedBy());
    return row;
  }

  /** The newest commands of an instance (at most {@value #MAX_LIST}). */
  public List<CommandRow> list(int instanceId, int limit) {
    return store.listByInstance(instanceId, Math.max(1, Math.min(MAX_LIST, limit)));
  }

  /**
   * The decrypted result of a finished command for its requester or an administrator; the result of
   * an administrator command for administrators only (16 §5.1). Audited {@code
   * COMMAND_RESULT_VIEWED}, and {@code SENSITIVE_VIEW} for environment and thread dumps (16 §5.7).
   *
   * @param format {@code json} (default) or {@code text}
   * @throws ApiException 400 unknown format, 403, 404, 409 {@code invalid_state} when the command
   *     has no result, 409 {@code secret_unavailable} when the key of the result is not loaded
   */
  public ResultView result(
      ArgosPrincipal principal, Actor actor, UUID id, @Nullable String format) {
    String f = format == null || format.isBlank() ? "json" : format.strip();
    if (!f.equals("json") && !f.equals("text")) {
      throw new ApiException(ErrorCode.INVALID_REQUEST, "format is json or text")
          .detail("format", "unsupported_value");
    }
    CommandRow row = find(principal, id);
    CommandKind kind =
        CommandKind.of(row.type()).orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
    if (kind.role() == Role.ADMIN && !principal.hasRole(Role.ADMIN)) {
      throw new ApiException(ErrorCode.FORBIDDEN, "This result is for administrators only");
    }
    if (!CommandStore.DONE.equals(row.status())) {
      throw new ApiException(ErrorCode.INVALID_STATE, "The command has no result")
          .detail("status", row.status().toLowerCase(Locale.ROOT));
    }
    StoredResult stored = store.result(id).orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
    byte[] plain;
    try {
      plain = gunzip(cipher.decrypt(stored.envelope(), aad(id)));
    } catch (SecretUnavailableException e) {
      throw new ApiException(ErrorCode.SECRET_UNAVAILABLE);
    }
    Map<String, Object> after = new LinkedHashMap<>();
    after.put("instance_id", row.instanceId());
    after.put("type", kind.wire());
    after.put("format", f);
    audit.record(
        AuditEvent.COMMAND_RESULT_VIEWED,
        actor,
        target(id, kind),
        AuditResult.success(),
        null,
        after);
    if (kind.sensitiveView() != null) {
      Map<String, Object> sensitive = new LinkedHashMap<>();
      sensitive.put("type", kind.sensitiveView());
      sensitive.put("command_id", id.toString());
      sensitive.put("instance_id", row.instanceId());
      audit.record(
          AuditEvent.SENSITIVE_VIEW,
          actor,
          target(id, kind),
          AuditResult.success(),
          null,
          sensitive);
    }
    try {
      if (f.equals("json") && kind.threads()) {
        return new ResultView(
            row,
            kind,
            f,
            stored.contentType(),
            stored.truncated(),
            stored.size(),
            ThreadDump.parse(stored.contentType(), plain),
            null);
      }
      return new ResultView(
          row,
          kind,
          f,
          stored.contentType(),
          stored.truncated(),
          stored.size(),
          null,
          new String(plain, StandardCharsets.UTF_8));
    } finally {
      Arrays.fill(plain, (byte) 0);
    }
  }

  // --- Session events (gRPC and registry threads) ----------------------------------------------

  @Override
  public void onResult(int instanceId, CommandResult result) {
    String raw = result.getCommandId();
    if (!UUID_V4.matcher(raw).matches()) {
      dropped("unknown");
      return;
    }
    UUID id = UUID.fromString(raw);
    InFlight command;
    synchronized (lock) {
      command = inflight.get(id);
    }
    if (command == null) {
      dropped("unknown");
      return;
    }
    if (command.instanceId != instanceId) {
      dropped("wrong_instance");
      return;
    }
    try {
      worker.execute(() -> handle(command, result));
    } catch (RejectedExecutionException e) {
      dropped("overloaded");
    }
  }

  @Override
  public void onSessionEnd(int instanceId, int appId) {
    try {
      worker.execute(() -> sessionEnded(instanceId));
    } catch (RejectedExecutionException e) {
      // The next tick expires the commands of an instance without a live session.
    }
  }

  // --- Worker ----------------------------------------------------------------------------------

  /** Sends a queued command if its session is ready (worker). */
  void send(UUID id) {
    InFlight command;
    synchronized (lock) {
      command = inflight.get(id);
      if (command == null || command.sentAtMs != 0) {
        return;
      }
    }
    long now = clock.millis();
    if (now >= command.expiresAtMs) {
      expire(command, "ttl", now);
      return;
    }
    Command message =
        Command.newBuilder()
            .setCommandId(id.toString())
            .setType(command.kind.proto())
            .setIssuedAtMs(command.issuedAtMs)
            .setTtlS(ttlSeconds())
            .putAllArgs(command.args)
            .build();
    if (!messenger.sendCommand(command.instanceId, message)) {
      return;
    }
    synchronized (lock) {
      if (inflight.get(id) != command) {
        return;
      }
      command.sentAtMs = now;
    }
    try {
      store.markSent(id, Instant.ofEpochMilli(now));
    } catch (RuntimeException e) {
      LOG.warn("A command could not be marked sent: {}", e.getClass().getSimpleName());
    }
    audit.record(
        AuditEvent.COMMAND_SENT,
        Actor.system(),
        target(id, command.kind),
        AuditResult.success(),
        null,
        Map.of("instance_id", command.instanceId));
    publish(command, "sent", null, now);
  }

  /** One {@code CommandResult} of a command in flight (worker). */
  void handle(InFlight command, CommandResult result) {
    synchronized (lock) {
      if (inflight.get(command.id) != command) {
        dropped("late");
        return;
      }
    }
    long now = clock.millis();
    switch (result.getStatus()) {
      case OK -> chunk(command, result, now);
      case REJECTED ->
          end(command, CommandStore.FAILED, "rejected", AuditEvent.COMMAND_REJECTED, now);
      case COOLDOWN ->
          end(command, CommandStore.FAILED, "cooldown", AuditEvent.COMMAND_REJECTED, now);
      case FAILED ->
          end(command, CommandStore.FAILED, "agent_failed", AuditEvent.COMMAND_COMPLETED, now);
      case EXPIRED ->
          end(command, CommandStore.EXPIRED, "agent_expired", AuditEvent.COMMAND_EXPIRED, now);
      case COMMAND_STATUS_UNSPECIFIED, UNRECOGNIZED ->
          end(command, CommandStore.FAILED, "invalid_result", AuditEvent.COMMAND_COMPLETED, now);
    }
  }

  private void chunk(InFlight command, CommandResult result, long now) {
    int count = result.getChunkCount() == 0 ? 1 : result.getChunkCount();
    int index = result.getChunkIndex();
    Assembly a = command.assembly;
    if (count > MAX_CHUNKS || index >= count || (a != null && a.count != count)) {
      end(command, CommandStore.FAILED, "invalid_result", AuditEvent.COMMAND_COMPLETED, now);
      return;
    }
    if (a == null) {
      a = new Assembly(count);
      command.assembly = a;
    }
    if (a.chunks[index] != null) {
      dropped("duplicate_chunk");
      return;
    }
    byte[] bytes = result.getPayload().toByteArray();
    int room = MAX_RESULT_BYTES - a.bytes;
    if (bytes.length > room) {
      bytes = Arrays.copyOf(bytes, Math.max(0, room));
      a.truncated = true;
    }
    a.chunks[index] = bytes;
    a.bytes += bytes.length;
    a.received++;
    a.truncated |= result.getTruncated();
    if (a.contentType == null && !result.getContentType().isEmpty()) {
      a.contentType = result.getContentType().startsWith(JSON_TYPE) ? JSON_TYPE : TEXT;
    }
    a.durationMs = Math.max(a.durationMs, result.getDurationMs());
    if (a.received == a.count) {
      complete(command, a, now);
    }
  }

  private void complete(InFlight command, Assembly a, long now) {
    if (!remove(command)) {
      return;
    }
    byte[] plain = new byte[a.bytes];
    int at = 0;
    for (byte[] c : a.chunks) {
      System.arraycopy(c, 0, plain, at, c.length);
      Arrays.fill(c, (byte) 0);
      at += c.length;
    }
    String envelope;
    try {
      envelope = cipher.encrypt(gzip(plain), aad(command.id));
    } catch (SecretUnavailableException e) {
      finish(command, CommandStore.FAILED, "invalid_result", AuditEvent.COMMAND_COMPLETED, now);
      return;
    } finally {
      Arrays.fill(plain, (byte) 0);
    }
    int duration =
        a.durationMs > 0
            ? a.durationMs
            : (int) Math.min(Integer.MAX_VALUE, Math.max(0, now - command.sentAtMs));
    boolean stored;
    try {
      stored =
          store.complete(
              command.id,
              Instant.ofEpochMilli(now),
              duration,
              new StoredResult(
                  envelope, a.bytes, a.truncated, a.contentType == null ? TEXT : a.contentType));
    } catch (RuntimeException e) {
      LOG.warn("A command result could not be stored: {}", e.getClass().getSimpleName());
      stored = false;
    }
    if (!stored) {
      return;
    }
    finished(command.kind, CommandStore.DONE);
    Map<String, Object> after = new LinkedHashMap<>();
    after.put("instance_id", command.instanceId);
    after.put("size", a.bytes);
    after.put("truncated", a.truncated);
    audit.record(
        AuditEvent.COMMAND_COMPLETED,
        Actor.system(),
        target(command.id, command.kind),
        AuditResult.success(),
        null,
        after);
    publish(command, "done", null, now);
  }

  /** Ends a command in flight without a result (worker). */
  private void end(InFlight command, String status, String code, AuditEvent event, long now) {
    if (remove(command)) {
      finish(command, status, code, event, now);
    }
  }

  private void expire(InFlight command, String code, long now) {
    end(command, CommandStore.EXPIRED, code, AuditEvent.COMMAND_EXPIRED, now);
  }

  private void finish(InFlight command, String status, String code, AuditEvent event, long now) {
    Integer duration =
        command.sentAtMs == 0
            ? null
            : (int) Math.min(Integer.MAX_VALUE, Math.max(0, now - command.sentAtMs));
    try {
      if (!store.finish(command.id, status, code, Instant.ofEpochMilli(now), duration)) {
        return;
      }
    } catch (RuntimeException e) {
      LOG.warn("A command could not be finished: {}", e.getClass().getSimpleName());
      return;
    }
    finished(command.kind, status);
    AuditResult outcome =
        switch (event) {
          case COMMAND_REJECTED -> AuditResult.rejected(code);
          case COMMAND_EXPIRED -> AuditResult.success(code);
          default -> AuditResult.failure(code);
        };
    audit.record(
        event,
        Actor.system(),
        target(command.id, command.kind),
        outcome,
        null,
        Map.of("instance_id", command.instanceId));
    publish(command, status.toLowerCase(Locale.ROOT), code, now);
  }

  /** Expires the commands of an instance whose session ended (worker). */
  void sessionEnded(int instanceId) {
    long now = clock.millis();
    for (InFlight c : snapshot()) {
      if (c.instanceId == instanceId) {
        expire(c, "session_ended", now);
      }
    }
  }

  /**
   * The periodic check (every second on the worker, {@link CommandRouterLifecycle}): sends queued
   * commands, expires those past their TTL, without a session or without a result in time, then
   * expires the database rows this node does not track. Public for tests that move a clock.
   */
  public void tick() {
    long now = clock.millis();
    for (InFlight c : snapshot()) {
      if (c.sentAtMs == 0) {
        if (now >= c.expiresAtMs) {
          expire(c, "ttl", now);
        } else if (!messenger.hasLiveSession(c.instanceId)) {
          expire(c, "session_ended", now);
        } else {
          send(c.id);
        }
      } else if (now - c.sentAtMs >= settings.resultTimeout().toMillis()) {
        expire(c, "timeout", now);
      }
    }
    synchronized (lock) {
      Iterator<Map.Entry<Integer, Deque<Long>>> it = recent.entrySet().iterator();
      while (it.hasNext()) {
        Deque<Long> w = it.next().getValue();
        prune(w, now);
        if (w.isEmpty()) {
          it.remove();
        }
      }
    }
    List<CommandStore.Expired> expired;
    try {
      expired =
          store.expireOverdue(
              Instant.ofEpochMilli(now),
              Instant.ofEpochMilli(now - settings.resultTimeout().toMillis()));
    } catch (RuntimeException e) {
      LOG.debug("The command expiry sweep failed: {}", e.getClass().getSimpleName());
      return;
    }
    for (CommandStore.Expired e : expired) {
      synchronized (lock) {
        inflight.remove(e.id());
      }
      CommandKind kind = CommandKind.of(e.type()).orElse(CommandKind.THREAD_DUMP);
      audit.record(
          AuditEvent.COMMAND_EXPIRED,
          Actor.system(),
          target(e.id(), kind),
          AuditResult.success(e.errorCode()),
          null,
          Map.of("instance_id", e.instanceId()));
      publishRaw(e.id(), e.instanceId(), kind, "expired", e.errorCode(), now);
    }
  }

  /** The retention (hourly): 30 days and the newest commands per instance. */
  public void retention() {
    Instant now = clock.instant();
    try {
      int old = store.deleteOlderThan(now.minus(settings.retention()));
      int trimmed = store.trimPerInstance(settings.keepPerInstance());
      if (old + trimmed > 0) {
        LOG.info("Deleted {} expired and {} surplus commands with their results", old, trimmed);
      }
    } catch (RuntimeException e) {
      LOG.warn("The command retention failed: {}", e.getClass().getSimpleName());
    }
  }

  /** Commands in flight now (tests, the meter). */
  public int inflightCount() {
    synchronized (lock) {
      return inflight.size();
    }
  }

  // --- Helpers ---------------------------------------------------------------------------------

  private int ttlSeconds() {
    return (int) Math.max(1, settings.ttl().toSeconds());
  }

  private int inflightOf(int instanceId) {
    int n = 0;
    for (InFlight c : inflight.values()) {
      if (c.instanceId == instanceId) {
        n++;
      }
    }
    return n;
  }

  private static void prune(Deque<Long> window, long now) {
    while (!window.isEmpty() && window.peekFirst() <= now - MINUTE_MS) {
      window.pollFirst();
    }
  }

  private List<InFlight> snapshot() {
    synchronized (lock) {
      return new ArrayList<>(inflight.values());
    }
  }

  private boolean remove(InFlight command) {
    synchronized (lock) {
      return inflight.remove(command.id, command);
    }
  }

  private void refused(Actor actor, int instanceId, CommandKind kind, String code) {
    Map<String, Object> after = new LinkedHashMap<>();
    after.put("instance_id", instanceId);
    after.put("type", kind.wire());
    audit.record(
        AuditEvent.COMMAND_REJECTED,
        actor,
        new Target("instance", Integer.toString(instanceId), null),
        AuditResult.rejected(code),
        null,
        after);
    meters.counter("argos.commands.refused", "type", kind.wire(), "reason", code).increment();
  }

  private void finished(CommandKind kind, String status) {
    meters
        .counter(
            "argos.commands.finished",
            "type",
            kind.wire(),
            "status",
            status.toLowerCase(Locale.ROOT))
        .increment();
  }

  private void dropped(String reason) {
    Counter c = meters.counter("argos.commands.results.dropped", "reason", reason);
    c.increment();
  }

  private static Target target(UUID id, CommandKind kind) {
    return new Target("command", id.toString(), kind.wire());
  }

  /** The associated data of a result (16 §8.3, §5.3). */
  static String aad(UUID id) {
    return SecretField.COMMAND_RESULT.aad(id.toString());
  }

  private void publish(InFlight c, String status, @Nullable String error, long now) {
    publishRaw(c.id, c.instanceId, c.kind, status, error, now);
  }

  private void publishRaw(
      UUID id, int instanceId, CommandKind kind, String status, @Nullable String error, long now) {
    try {
      push.publish(null, id.toString(), now, pushData(id, instanceId, kind, status, error));
    } catch (RuntimeException e) {
      LOG.debug("An admin push failed: {}", e.getClass().getSimpleName());
    }
  }

  /**
   * The {@code data} of an {@code admin} push of a command state transition: {@code {command_id,
   * instance_id, type, status, error}} (14 §5.2; no result, no argument values).
   */
  public static String pushData(
      UUID id, int instanceId, CommandKind kind, String status, @Nullable String error) {
    ObjectNode data = JSON.createObjectNode();
    data.put("command_id", id.toString());
    data.put("instance_id", instanceId);
    data.put("type", kind.wire());
    data.put("status", status);
    data.put("error", error);
    return JSON.writeValueAsString(data);
  }

  static byte[] gzip(byte[] plain) {
    ByteArrayOutputStream out = new ByteArrayOutputStream(Math.max(64, plain.length / 4));
    try (GZIPOutputStream gz = new GZIPOutputStream(out)) {
      gz.write(plain);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    return out.toByteArray();
  }

  /** Decompresses at most {@value #MAX_RESULT_BYTES} bytes (more is not a stored result). */
  static byte[] gunzip(byte[] compressed) {
    try (InputStream in = new GZIPInputStream(new ByteArrayInputStream(compressed))) {
      byte[] out = in.readNBytes(MAX_RESULT_BYTES + 1);
      if (out.length > MAX_RESULT_BYTES) {
        throw new ApiException(ErrorCode.INTERNAL);
      }
      return out;
    } catch (IOException e) {
      throw new ApiException(ErrorCode.INTERNAL);
    } finally {
      Arrays.fill(compressed, (byte) 0);
    }
  }

  /** A command in flight (guarded by {@link #lock}, except its assembly: worker only). */
  static final class InFlight {
    final UUID id;
    final int instanceId;
    final CommandKind kind;
    final Map<String, String> args;
    final UUID requestedBy;
    final long issuedAtMs;
    final long expiresAtMs;
    long sentAtMs;
    @Nullable Assembly assembly;

    InFlight(
        UUID id,
        int instanceId,
        CommandKind kind,
        Map<String, String> args,
        UUID requestedBy,
        long issuedAtMs,
        long expiresAtMs) {
      this.id = id;
      this.instanceId = instanceId;
      this.kind = kind;
      this.args = args;
      this.requestedBy = requestedBy;
      this.issuedAtMs = issuedAtMs;
      this.expiresAtMs = expiresAtMs;
    }
  }

  /** The chunks of one result (worker only). */
  static final class Assembly {
    final int count;
    final byte[][] chunks;
    int received;
    int bytes;
    boolean truncated;
    @Nullable String contentType;
    int durationMs;

    Assembly(int count) {
      this.count = count;
      this.chunks = new byte[count][];
    }
  }
}
