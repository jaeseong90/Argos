/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.secrets;

import java.nio.charset.StandardCharsets;
import org.jspecify.annotations.Nullable;

/**
 * The PostgreSQL columns that hold envelopes of the {@link EnvelopeCipher} (16 §8.3), each with its
 * associated data {@code <table>:<column>:<row id>}: one place for the AAD of every secret column,
 * used by the stores that write and read them and by {@code argos-admin secrets rekey}, which
 * re-encrypts all of them ({@link io.argos.server.storage.postgres.secrets.SecretsRekeyService}).
 *
 * <p>Every column is {@code BYTEA} holding the envelope string as UTF-8 (ASCII) bytes (ADR-0010
 * C-98, C-226 (2)).
 */
public enum SecretField {

  /** {@code notification_channels.config_enc}: a channel's secret configuration (SEC-52). */
  CHANNEL_CONFIG("notification_channels", "config_enc"),

  /**
   * {@code webhook_signing_keys.key_enc}: a webhook signing secret (SEC-53); row id {@code <channel
   * id>/<key id>}.
   */
  WEBHOOK_SIGNING_KEY("webhook_signing_keys", "key_enc"),

  /** {@code command_results.body_enc}: a remote command's gzip-compressed result (SEC-22). */
  COMMAND_RESULT("command_results", "body_enc"),

  /** {@code apps.user_id_salt_enc}: an application's 32-byte user id salt (16 §6.3). */
  APP_USER_ID_SALT("apps", "user_id_salt_enc");

  private final String table;
  private final String column;

  SecretField(String table, String column) {
    this.table = table;
    this.column = column;
  }

  /** The table name (schema {@code argos}). */
  public String table() {
    return table;
  }

  /** The column name. */
  public String column() {
    return column;
  }

  /** {@code table.column}, as reports name it. */
  public String qualifiedName() {
    return table + "." + column;
  }

  /** The associated data of the value of row {@code rowId}. */
  public String aad(String rowId) {
    return EnvelopeCipher.aad(table, column, rowId);
  }

  /** The envelope of {@code plaintext} for row {@code rowId}, under the current key. */
  public String encrypt(EnvelopeCipher cipher, byte[] plaintext, String rowId) {
    return cipher.encrypt(plaintext, aad(rowId));
  }

  /** {@link #encrypt} of a UTF-8 string. */
  public String encryptString(EnvelopeCipher cipher, String plaintext, String rowId) {
    return cipher.encryptString(plaintext, aad(rowId));
  }

  /**
   * The plaintext of the envelope stored in row {@code rowId}.
   *
   * @throws SecretUnavailableException as {@link EnvelopeCipher#decrypt}
   */
  public byte[] decrypt(EnvelopeCipher cipher, String envelope, String rowId) {
    return cipher.decrypt(envelope, aad(rowId));
  }

  /** {@link #decrypt} to a UTF-8 string. */
  public String decryptString(EnvelopeCipher cipher, String envelope, String rowId) {
    return cipher.decryptString(envelope, aad(rowId));
  }

  /** The envelope string of a stored column value (UTF-8 bytes), or null for null. */
  public static @Nullable String envelope(byte @Nullable [] stored) {
    return stored == null ? null : new String(stored, StandardCharsets.UTF_8);
  }

  /** The column value of an envelope string. */
  public static byte[] stored(String envelope) {
    return envelope.getBytes(StandardCharsets.UTF_8);
  }

  /** The key id an envelope names, or null when it is not an envelope. */
  public static @Nullable String kidOf(@Nullable String envelope) {
    return EnvelopeCipher.kidOf(envelope);
  }
}
