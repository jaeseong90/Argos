/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.testing.secrets;

import io.argos.server.secrets.EnvelopeCipher;
import io.argos.server.secrets.MasterKey;
import io.argos.server.secrets.MasterKeyRing;
import io.argos.server.secrets.SecretField;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.zip.GZIPOutputStream;

/**
 * One stored secret in every {@link SecretField} column, written directly with a given master key
 * the way the server writes them (WO-28 rekey tests): an application with its user id salt, an
 * instance and a finished command with an encrypted result, a webhook channel with a signing key.
 * The plaintexts are random markers, so a dump of the database can be searched for them.
 *
 * @param appId the application (its salt is {@link #salt()})
 * @param instanceId its instance
 * @param commandId the command whose result is {@link #commandBody}
 * @param channelId the channel whose configuration holds {@link #channelMarker}
 * @param keyId the channel's signing key id ({@link #signingSecret})
 * @param saltHex the application's 32-byte salt, hex
 * @param commandBody the command result text
 * @param channelMarker a secret part of the channel URL
 * @param signingSecret the signing secret
 */
public record SecretRows(
    int appId,
    int instanceId,
    UUID commandId,
    UUID channelId,
    String keyId,
    String saltHex,
    String commandBody,
    String channelMarker,
    String signingSecret) {

  private static final SecureRandom RANDOM = new SecureRandom();

  /** The user that requested the seeded command (an administrator). */
  public static final UUID REQUESTER =
      UUID.nameUUIDFromBytes("ADMIN".getBytes(StandardCharsets.UTF_8));

  /** A cipher that encrypts with {@code hex} (64 hex characters). */
  public static EnvelopeCipher cipher(String hex) {
    return new EnvelopeCipher(new MasterKeyRing(MasterKey.parse(hex), null));
  }

  /** A cipher that decrypts with exactly these keys and encrypts with the first. */
  public static EnvelopeCipher cipher(String hex, String... others) {
    List<MasterKey> rest = java.util.Arrays.stream(others).map(MasterKey::parse).toList();
    return new EnvelopeCipher(MasterKeyRing.of(MasterKey.parse(hex), rest));
  }

  /** The key id of {@code hex}. */
  public static String kid(String hex) {
    return MasterKey.parse(hex).kid();
  }

  /**
   * Writes the rows with {@code cipher} over {@code app} (the runtime account, which may write all
   * of these tables).
   */
  public static SecretRows seed(Connection app, EnvelopeCipher cipher) throws SQLException {
    String marker = token();
    int appId;
    try (PreparedStatement ps =
        app.prepareStatement(
            "INSERT INTO argos.apps (name, env) VALUES (?, 'prod') RETURNING id")) {
      ps.setString(1, "rekey-" + marker.substring(0, 8).toLowerCase(java.util.Locale.ROOT));
      appId = single(ps);
    }
    byte[] salt = new byte[32];
    RANDOM.nextBytes(salt);
    update(
        app,
        "UPDATE argos.apps SET user_id_salt_enc = ? WHERE id = ?",
        SecretField.stored(
            SecretField.APP_USER_ID_SALT.encrypt(cipher, salt, Integer.toString(appId))),
        appId);
    int instanceId;
    try (PreparedStatement ps =
        app.prepareStatement(
            "INSERT INTO argos.instances (app_id, name) VALUES (?, 'rekey-host:8080') RETURNING"
                + " id")) {
      ps.setInt(1, appId);
      instanceId = single(ps);
    }
    update(
        app,
        "INSERT INTO argos.users (id, username, password_hash, role) VALUES (?, ?, ?, 'ADMIN')"
            + " ON CONFLICT DO NOTHING",
        REQUESTER,
        "rekey-admin",
        "$2a$12$" + "x".repeat(53));
    UUID commandId = UUID.randomUUID();
    update(
        app,
        "INSERT INTO argos.commands (id, instance_id, type, requested_by, expires_at, status,"
            + " finished_at) VALUES (?, ?, 'self_diagnosis', ?, now() + interval '1 hour', 'DONE',"
            + " now())",
        commandId,
        instanceId,
        REQUESTER);
    String body = "{\"rekey-command-body\":\"" + token() + "\"}";
    update(
        app,
        "INSERT INTO argos.command_results (command_id, body_enc, body_size, content_type)"
            + " VALUES (?, ?, ?, 'application/json')",
        commandId,
        SecretField.stored(
            SecretField.COMMAND_RESULT.encrypt(
                cipher, gzip(body.getBytes(StandardCharsets.UTF_8)), commandId.toString())),
        body.length());
    UUID channelId = UUID.randomUUID();
    String config = "{\"url\":\"https://127.0.0.1:9/hooks/" + marker + "\",\"allow_http\":false}";
    update(
        app,
        "INSERT INTO argos.notification_channels (id, name, type, config_enc, config_masked,"
            + " enabled, created_at, updated_at) VALUES (?, ?, 'webhook', ?,"
            + " '{\"target\":\"https://127.0.0.1:9/****\"}'::jsonb, FALSE, now(), now())",
        channelId,
        "rekey-" + marker.substring(0, 8),
        SecretField.stored(
            SecretField.CHANNEL_CONFIG.encryptString(cipher, config, channelId.toString())));
    String keyId = HexFormat.of().formatHex(randomBytes(8));
    String secret = token();
    update(
        app,
        "INSERT INTO argos.webhook_signing_keys (channel_id, key_id, key_enc) VALUES (?, ?, ?)",
        channelId,
        keyId,
        SecretField.stored(
            SecretField.WEBHOOK_SIGNING_KEY.encryptString(
                cipher, secret, channelId + "/" + keyId)));
    return new SecretRows(
        appId,
        instanceId,
        commandId,
        channelId,
        keyId,
        HexFormat.of().formatHex(salt),
        body,
        marker,
        secret);
  }

  /** The application's salt. */
  public byte[] salt() {
    return HexFormat.of().parseHex(saltHex);
  }

  /** Every plaintext of these rows in the text forms a dump could show them. */
  public List<String> plaintexts() {
    return List.of(
        commandBody,
        channelMarker,
        signingSecret,
        saltHex,
        Base64.getEncoder().encodeToString(salt()),
        Base64.getUrlEncoder().withoutPadding().encodeToString(salt()));
  }

  /** The stored envelope of each column for these rows. */
  public Map<SecretField, String> envelopes(Connection c) throws SQLException {
    Map<SecretField, String> out = new LinkedHashMap<>();
    out.put(
        SecretField.APP_USER_ID_SALT,
        text(c, "SELECT user_id_salt_enc FROM argos.apps WHERE id = ?", appId));
    out.put(
        SecretField.COMMAND_RESULT,
        text(c, "SELECT body_enc FROM argos.command_results WHERE command_id = ?", commandId));
    out.put(
        SecretField.CHANNEL_CONFIG,
        text(c, "SELECT config_enc FROM argos.notification_channels WHERE id = ?", channelId));
    out.put(
        SecretField.WEBHOOK_SIGNING_KEY,
        text(
            c,
            "SELECT key_enc FROM argos.webhook_signing_keys WHERE channel_id = ? AND key_id = ?",
            channelId,
            keyId));
    return out;
  }

  /** The row id (AAD) of each column for these rows. */
  public String rowId(SecretField field) {
    return switch (field) {
      case APP_USER_ID_SALT -> Integer.toString(appId);
      case COMMAND_RESULT -> commandId.toString();
      case CHANNEL_CONFIG -> channelId.toString();
      case WEBHOOK_SIGNING_KEY -> channelId + "/" + keyId;
    };
  }

  /** gzip of {@code plain} (the command result format, 16 §5.3). */
  public static byte[] gzip(byte[] plain) {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    try (GZIPOutputStream gz = new GZIPOutputStream(out)) {
      gz.write(plain);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    return out.toByteArray();
  }

  /** 18 random bytes, base64url. */
  public static String token() {
    return Base64.getUrlEncoder().withoutPadding().encodeToString(randomBytes(18));
  }

  private static byte[] randomBytes(int n) {
    byte[] b = new byte[n];
    RANDOM.nextBytes(b);
    return b;
  }

  private static int single(PreparedStatement ps) throws SQLException {
    try (ResultSet rs = ps.executeQuery()) {
      rs.next();
      return rs.getInt(1);
    }
  }

  private static void update(Connection c, String sql, Object... args) throws SQLException {
    try (PreparedStatement ps = c.prepareStatement(sql)) {
      for (int i = 0; i < args.length; i++) {
        ps.setObject(i + 1, args[i]);
      }
      ps.executeUpdate();
    }
  }

  private static String text(Connection c, String sql, Object... args) throws SQLException {
    try (PreparedStatement ps = c.prepareStatement(sql)) {
      for (int i = 0; i < args.length; i++) {
        ps.setObject(i + 1, args[i]);
      }
      try (ResultSet rs = ps.executeQuery()) {
        return rs.next() ? SecretField.envelope(rs.getBytes(1)) : null;
      }
    }
  }
}
