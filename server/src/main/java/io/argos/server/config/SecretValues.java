/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.config;

import io.argos.server.config.ArgosProperties.Credential;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Optional;

/**
 * Reads secret settings (17 §6.1, 16 §10.6): a {@code *-file} key names a file whose first line is
 * the secret (a trailing line break, as Docker secrets and {@code echo} write it, is not part of
 * it); the plain key holds the value itself. The file wins when both are set. Errors name the
 * setting and the path, never the value.
 *
 * <p>Use it for every password, token and key of {@link ArgosProperties}, e.g. {@code
 * SecretValues.password("argos.postgres.owner", properties.postgres().owner())}. Read the secret
 * where it is used and do not keep it longer than needed.
 */
public final class SecretValues {

  /** Upper bound of a secret file; anything larger is not a secret file. */
  static final int MAX_FILE_BYTES = 64 * 1024;

  private SecretValues() {}

  /**
   * The password of an account whose keys are {@code <prefix>.password-file} and {@code
   * <prefix>.password}, e.g. {@code argos.postgres} (runtime account) or {@code
   * argos.clickhouse.writer}.
   *
   * @return the password, or empty when neither key is set
   * @throws SecretException when the password file cannot be read
   */
  public static Optional<String> password(String prefix, Credential credential) {
    return resolve(
        prefix + ".password-file",
        credential.passwordFile(),
        prefix + ".password",
        credential.password());
  }

  /**
   * A secret from a file key or a value key.
   *
   * @param fileKey name of the file key, for messages
   * @param file value of the file key (a path), may be null
   * @param valueKey name of the value key, for messages
   * @param value value of the value key, may be null
   * @return the secret, or empty when neither key is set; an empty file counts as an empty secret
   * @throws SecretException when the file cannot be read
   */
  public static Optional<String> resolve(
      String fileKey, String file, String valueKey, String value) {
    if (file != null && !file.isBlank()) {
      return Optional.of(readFile(fileKey, Paths.get(file.strip())));
    }
    if (value != null) {
      return Optional.of(value);
    }
    return Optional.empty();
  }

  /** The first line of a secret file (without the line break). */
  static String readFile(String key, Path file) {
    byte[] bytes;
    try (InputStream in = Files.newInputStream(file)) {
      bytes = in.readNBytes(MAX_FILE_BYTES + 1);
    } catch (IOException e) {
      throw new SecretException(
          key + " names a file that cannot be read (" + file + "): " + e.getClass().getSimpleName(),
          e);
    }
    if (bytes.length > MAX_FILE_BYTES) {
      throw new SecretException(
          key + " names a file larger than " + MAX_FILE_BYTES + " bytes (" + file + ")", null);
    }
    String text = new String(bytes, StandardCharsets.UTF_8);
    int end = text.indexOf('\n');
    String line = end < 0 ? text : text.substring(0, end);
    return line.endsWith("\r") ? line.substring(0, line.length() - 1) : line;
  }

  /** A secret file could not be read. The message names the key and path, never the value. */
  public static final class SecretException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    SecretException(String message, Throwable cause) {
      super(message, cause);
    }
  }
}
