/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.alert.channel;

import io.argos.server.api.error.ApiException;
import io.argos.server.api.error.ErrorCode;
import io.argos.server.audit.Actor;
import io.argos.server.audit.AuditEvent;
import io.argos.server.audit.AuditResult;
import io.argos.server.audit.AuditService;
import io.argos.server.audit.Target;
import io.argos.server.config.ArgosProperties;
import io.argos.server.secrets.SecretUnavailableException;
import io.argos.server.storage.postgres.alert.channel.ChannelRepository;
import io.argos.server.storage.postgres.alert.channel.ChannelRow;
import io.argos.server.storage.postgres.alert.channel.SigningKeyRow;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Administration of the notification channels (14 §4.13 {@code /alerts/channels*}, 12 §5.5, 16 §8):
 * create, change, delete, test send and signing key rotation, each with its audit event (16 §11.1).
 * Secrets are encrypted before they reach the database ({@link ChannelVault}) and shown only in
 * masked form; a generated signing secret is returned once.
 *
 * <p>Without a master key every operation that stores or reads a secret answers 409 {@code
 * secret_unavailable} (WO-25 assumption, PM question 2).
 */
@Service
public class ChannelService {

  /** Channels at most (each enabled channel receives every self alert). */
  public static final int MAX_CHANNELS = 200;

  /** Longest channel name (V001 {@code name VARCHAR(128)}). */
  public static final int MAX_NAME = 128;

  /** Grace of the previous signing key after a rotation (16 §8.4). */
  public static final Duration ROTATION_GRACE = Duration.ofDays(7);

  private static final JsonMapper JSON = JsonMapper.builder().build();

  private final ChannelRepository channels;
  private final ChannelVault vault;
  private final DestinationValidator validator;
  private final ChannelSenders senders;
  private final AuditService audit;
  private final Clock clock;
  private final String publicUrl;

  public ChannelService(
      ChannelRepository channels,
      ChannelVault vault,
      DestinationValidator validator,
      ChannelSenders senders,
      AuditService audit,
      Clock clock,
      ArgosProperties properties) {
    this.channels = channels;
    this.vault = vault;
    this.validator = validator;
    this.senders = senders;
    this.audit = audit;
    this.clock = clock;
    this.publicUrl = properties.web().publicUrl();
  }

  /**
   * A channel as the API lists it: the target always masked (SEC-52).
   *
   * @param id channel id
   * @param type type
   * @param name name (operator text)
   * @param targetMasked masked URL or recipients
   * @param enabled whether deliveries go to it
   * @param status {@code ok} or {@code failing}
   * @param lastSuccessAt last successful delivery
   * @param lastFailureAt last failed delivery
   * @param consecutiveFailures failed deliveries since the last success
   * @param signingEnabled whether requests are signed (webhook with a valid key)
   * @param allowHttp whether the channel asked for plain http
   * @param createdAt creation
   * @param updatedAt last change
   */
  public record Summary(
      UUID id,
      ChannelType type,
      String name,
      String targetMasked,
      boolean enabled,
      String status,
      Instant lastSuccessAt,
      Instant lastFailureAt,
      int consecutiveFailures,
      boolean signingEnabled,
      boolean allowHttp,
      Instant createdAt,
      Instant updatedAt) {}

  /**
   * {@code POST /alerts/channels}.
   *
   * @param type {@code email}, {@code slack} or {@code webhook}
   * @param name channel name
   * @param target URL (webhook, Slack) or recipients (e-mail, comma separated)
   * @param secret a signing secret of the caller's choice (webhook only), or null to generate one
   * @param allowHttp ask for a plain http URL (only with {@code argos.alert.webhook.allow-http})
   */
  public record CreateCommand(
      String type, String name, String target, String secret, Boolean allowHttp) {

    @Override
    public String toString() {
      return "CreateCommand[type=" + type + ", name=" + name + "]";
    }
  }

  /** A created channel and, when generated, its signing secret (shown once). */
  public record Created(Summary channel, String signingSecret) {

    @Override
    public String toString() {
      return "Created[" + channel.id() + "]";
    }
  }

  /** {@code PUT /alerts/channels/{id}}: only the re-entered fields; null keeps the value. */
  public record UpdateCommand(String name, String target, Boolean allowHttp, Boolean enabled) {

    @Override
    public String toString() {
      return "UpdateCommand[name=" + name + ", enabled=" + enabled + "]";
    }
  }

  /** The outcome of a test send: never the response body. */
  public record TestResult(boolean delivered, Integer statusCode, long durationMs, String error) {}

  /** A rotated signing key: the new secret (shown once) and the end of the old key's grace. */
  public record Rotated(String signingSecret, Instant previousValidUntil) {

    @Override
    public String toString() {
      return "Rotated[previousValidUntil=" + previousValidUntil + "]";
    }
  }

  /** The channels, by name. */
  public List<Summary> list() {
    Instant now = clock.instant();
    return channels.list(now, MAX_CHANNELS + 1).stream().map(ChannelService::summary).toList();
  }

  /** Creates a channel. */
  public Created create(CreateCommand command, Actor actor) {
    ChannelType type =
        ChannelType.of(command.type())
            .orElseThrow(
                () -> new ApiException(ErrorCode.INVALID_REQUEST).detail("type", "invalid"));
    String name = name(command.name(), true);
    boolean allowHttp = Boolean.TRUE.equals(command.allowHttp());
    ChannelConfig config = config(type, command.target(), allowHttp);
    if (command.secret() != null && type != ChannelType.WEBHOOK) {
      throw new ApiException(ErrorCode.INVALID_REQUEST).detail("secret", "not_supported");
    }
    if (command.secret() != null && !validSecret(command.secret())) {
      throw new ApiException(ErrorCode.INVALID_REQUEST).detail("secret", "invalid");
    }
    requireKey();
    Instant now = clock.instant();
    if (channels.list(now, MAX_CHANNELS + 1).size() >= MAX_CHANNELS) {
      throw new ApiException(ErrorCode.LIMIT_EXCEEDED).detail("channels", "max_" + MAX_CHANNELS);
    }
    UUID id = UUID.randomUUID();
    String generated = null;
    SigningKeyRow key = null;
    if (type == ChannelType.WEBHOOK) {
      String secret = command.secret();
      if (secret == null) {
        generated = vault.newSecret();
        secret = generated;
      }
      key = vault.newKey(id, secret, now);
    }
    channels.insert(
        id, name, type.wire(), vault.encryptConfig(id, config), config.maskedJson(type), now, key);
    Summary created = summary(channels.find(id, now).orElseThrow());
    audit.record(
        AuditEvent.CHANNEL_CREATED,
        actor,
        target(created),
        AuditResult.success(),
        null,
        Map.of("type", type.wire(), "target_masked", config.maskedTarget()));
    return new Created(created, generated);
  }

  /** Changes the re-entered fields of a channel. */
  public Summary update(UUID id, UpdateCommand command, Actor actor) {
    if (command.name() == null
        && command.target() == null
        && command.allowHttp() == null
        && command.enabled() == null) {
      throw new ApiException(ErrorCode.INVALID_REQUEST, "The request changes nothing");
    }
    Instant now = clock.instant();
    ChannelRow row = find(id, now);
    ChannelType type = ChannelType.of(row.type()).orElseThrow();
    String name = command.name() == null ? null : name(command.name(), true);
    String envelope = null;
    String masked = null;
    Map<String, Object> changed = new LinkedHashMap<>();
    if (command.target() != null || command.allowHttp() != null) {
      requireKey();
      ChannelConfig config;
      if (command.target() != null) {
        config = config(type, command.target(), Boolean.TRUE.equals(command.allowHttp()));
        changed.put("target_masked", config.maskedTarget());
      } else {
        if (!type.urlTarget()) {
          throw new ApiException(ErrorCode.INVALID_REQUEST).detail("allow_http", "not_supported");
        }
        ChannelConfig current = vault.decryptConfig(row);
        config = ChannelConfig.url(current.url(), command.allowHttp());
        validator.validate(config.url(), config.allowHttp());
      }
      if (type.urlTarget()) {
        changed.put("allow_http", config.allowHttp());
      }
      envelope = vault.encryptConfig(id, config);
      masked = config.maskedJson(type);
    }
    if (name != null) {
      changed.put("name", name);
    }
    if (command.enabled() != null) {
      changed.put("enabled", command.enabled());
    }
    if (!channels.update(id, name, envelope, masked, command.enabled(), now)) {
      throw new ApiException(ErrorCode.NOT_FOUND);
    }
    Summary updated = summary(find(id, now));
    audit.record(
        AuditEvent.CHANNEL_UPDATED, actor, target(updated), AuditResult.success(), null, changed);
    return updated;
  }

  /** Deletes a channel; 409 while a rule refers to it. */
  public void delete(UUID id, Actor actor) {
    Instant now = clock.instant();
    ChannelRow row = find(id, now);
    if (channels.referencedByRule(id)) {
      throw new ApiException(ErrorCode.INVALID_STATE, "A rule still uses this channel")
          .detail("channel", "referenced_by_rule");
    }
    if (!channels.softDelete(id, now)) {
      throw new ApiException(ErrorCode.NOT_FOUND);
    }
    audit.record(
        AuditEvent.CHANNEL_DELETED,
        actor,
        new Target("channel", id.toString(), row.name()),
        AuditResult.success());
  }

  /**
   * Sends a test message through the same validation and client as a real delivery (SEC-51). A
   * blocked destination answers 422; any other failure is a result with {@code delivered = false}.
   */
  public TestResult test(UUID id, Actor actor) {
    Instant now = clock.instant();
    ChannelRow row = find(id, now);
    Target target = new Target("channel", id.toString(), row.name());
    NotificationChannel.OpenChannel open;
    try {
      open = vault.open(row, now);
    } catch (SecretUnavailableException e) {
      audit.record(
          AuditEvent.CHANNEL_TEST_SENT, actor, target, AuditResult.failure("secret_unavailable"));
      throw e;
    }
    long start = System.nanoTime();
    TestResult result;
    try {
      NotificationChannel.Outcome outcome =
          senders.of(open.type()).send(open, ChannelMessage.test(row.name(), publicUrl));
      result = new TestResult(true, outcome.statusCode(), outcome.durationMs(), null);
    } catch (DestinationBlockedException e) {
      audit.record(
          AuditEvent.CHANNEL_TEST_SENT,
          actor,
          target,
          AuditResult.rejected("destination_blocked:" + e.reason()));
      throw e;
    } catch (DeliveryException e) {
      result =
          new TestResult(false, e.statusCode(), (System.nanoTime() - start) / 1_000_000L, e.code());
    }
    audit.record(
        AuditEvent.CHANNEL_TEST_SENT,
        actor,
        target,
        result.delivered()
            ? AuditResult.success("status=" + result.statusCode())
            : AuditResult.failure(result.error()));
    return result;
  }

  /** Rotates the signing key of a webhook channel (16 §8.4; PM question 4 path). */
  public Rotated rotateSigningKey(UUID id, Actor actor) {
    Instant now = clock.instant();
    ChannelRow row = find(id, now);
    if (ChannelType.of(row.type()).orElseThrow() != ChannelType.WEBHOOK) {
      throw new ApiException(ErrorCode.INVALID_STATE, "Only webhook channels have a signing key")
          .detail("type", "not_webhook");
    }
    requireKey();
    String secret = vault.newSecret();
    Instant graceEnd = now.plus(ROTATION_GRACE);
    if (!channels.rotateKey(id, vault.newKey(id, secret, now), now, graceEnd)) {
      throw new ApiException(ErrorCode.NOT_FOUND);
    }
    audit.record(
        AuditEvent.CHANNEL_SIGNING_KEY_ROTATED,
        actor,
        new Target("channel", id.toString(), row.name()),
        AuditResult.success(),
        null,
        Map.of("previous_valid_until", graceEnd.toString()));
    return new Rotated(secret, graceEnd);
  }

  private ChannelRow find(UUID id, Instant now) {
    return channels.find(id, now).orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
  }

  private void requireKey() {
    if (!vault.available()) {
      throw new SecretUnavailableException("no_master_key");
    }
  }

  /** The configuration of a target, validated (422 for a blocked URL, 400 for bad recipients). */
  private ChannelConfig config(ChannelType type, String target, boolean allowHttp) {
    if (target == null || target.isBlank()) {
      throw new ApiException(ErrorCode.INVALID_REQUEST).detail("target", "required");
    }
    if (type.urlTarget()) {
      String url = target.strip();
      validator.validate(url, allowHttp);
      return ChannelConfig.url(url, allowHttp);
    }
    List<String> recipients;
    try {
      recipients = ChannelMasking.parseRecipients(target);
    } catch (IllegalArgumentException e) {
      throw new ApiException(ErrorCode.INVALID_REQUEST).detail("target", e.getMessage());
    }
    if (!senders.email().recipientsAllowed(recipients)) {
      throw new ApiException(ErrorCode.INVALID_REQUEST, "A recipient domain is not allowed")
          .detail("target", "recipient_domain_not_allowed");
    }
    return ChannelConfig.recipients(recipients);
  }

  private static String name(String value, boolean required) {
    if (value == null || value.isBlank()) {
      if (required) {
        throw new ApiException(ErrorCode.INVALID_REQUEST).detail("name", "required");
      }
      return null;
    }
    String name = value.strip();
    if (name.length() > MAX_NAME || name.chars().anyMatch(Character::isISOControl)) {
      throw new ApiException(ErrorCode.INVALID_REQUEST).detail("name", "invalid");
    }
    return name;
  }

  /** A caller's signing secret: 32 to 256 printable ASCII characters. */
  private static boolean validSecret(String secret) {
    return secret.length() >= 32
        && secret.length() <= 256
        && secret.chars().allMatch(c -> c > 0x20 && c < 0x7f);
  }

  private static Target target(Summary s) {
    return new Target("channel", s.id().toString(), s.name());
  }

  static Summary summary(ChannelRow row) {
    String masked = "****";
    boolean allowHttp = false;
    try {
      JsonNode node = JSON.readTree(row.configMasked() == null ? "{}" : row.configMasked());
      masked = node.path("target").asString("****");
      allowHttp = node.path("allow_http").asBoolean(false);
    } catch (RuntimeException e) {
      // a masked copy that does not parse shows as ****
    }
    ChannelType type = ChannelType.of(row.type()).orElse(ChannelType.WEBHOOK);
    return new Summary(
        row.id(),
        type,
        row.name(),
        masked,
        row.enabled(),
        row.status(),
        row.lastSuccessAt(),
        row.lastFailureAt(),
        row.consecutiveFailures(),
        type == ChannelType.WEBHOOK && row.signingKeys() > 0,
        allowHttp,
        row.createdAt(),
        row.updatedAt());
  }
}
