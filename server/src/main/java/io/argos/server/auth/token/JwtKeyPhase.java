/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.auth.token;

import io.argos.server.bootstrap.PhaseOrder;
import io.argos.server.bootstrap.StartupPhase;
import io.argos.server.bootstrap.StartupPhaseException;
import io.argos.server.config.ArgosProperties;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Loads the access-token signing keys in the {@code KEYS} start phase (DSN-12-15, 16 §7.1), before
 * the database migrations and before any listener:
 *
 * <ol>
 *   <li>{@code argos.jwt-key} ({@code ARGOS_JWT_KEY}) when set, else the file {@code
 *       argos.jwt-key-file} (default {@code conf/jwt.key}); a missing file is created with a new
 *       Ed25519 key, owner-only ({@code 0600}) on POSIX. An existing file that cannot be read or
 *       parsed stops the start; it is never overwritten.
 *   <li>The previous key of a rotation, {@code argos.jwt-previous-key} or {@code
 *       argos.jwt-previous-key-file}, verification only.
 * </ol>
 *
 * Rotation: move the current key file to another name, point {@code ARGOS_JWT_PREVIOUS_KEY_FILE} at
 * it and restart (a new key is created); after the access-token lifetime (15 minutes) remove the
 * previous key setting. The log names the key ids, never key material.
 */
@Component
public class JwtKeyPhase implements StartupPhase {

  private static final Logger LOG = LoggerFactory.getLogger(JwtKeyPhase.class);

  /** Upper bound of a key file. */
  static final int MAX_KEY_FILE_BYTES = 16 * 1024;

  private final ArgosProperties properties;
  private final JwtKeyRing ring;

  public JwtKeyPhase(ArgosProperties properties, JwtKeyRing ring) {
    this.properties = properties;
    this.ring = ring;
  }

  @Override
  public PhaseOrder order() {
    return PhaseOrder.KEYS;
  }

  @Override
  public String name() {
    return "jwt-key";
  }

  @Override
  public void run() throws StartupPhaseException {
    String source;
    JwtKey current;
    if (properties.jwtKey() != null) {
      current = parse("argos.jwt-key", properties.jwtKey(), false);
      source = "argos.jwt-key";
    } else {
      if (properties.jwtKeyFile() == null) {
        throw new StartupPhaseException(
            "argos.jwt-key-file is empty: set it (default conf/jwt.key) or set argos.jwt-key");
      }
      Path file = Paths.get(properties.jwtKeyFile().strip());
      if (Files.exists(file)) {
        current = parse("argos.jwt-key-file", readKeyFile("argos.jwt-key-file", file), false);
        source = "argos.jwt-key-file " + file;
      } else {
        current = JwtKey.generate();
        try {
          PrivateFiles.writeOwnerOnly(file, current.toPem());
        } catch (IOException e) {
          throw new StartupPhaseException(
              "Cannot create the JWT signing key file "
                  + file.toAbsolutePath()
                  + " (argos.jwt-key-file): "
                  + e.getClass().getSimpleName(),
              e);
        }
        source = "created " + file;
      }
    }
    JwtKey previous = null;
    if (properties.jwtPreviousKey() != null) {
      previous = parse("argos.jwt-previous-key", properties.jwtPreviousKey(), true);
    } else if (properties.jwtPreviousKeyFile() != null) {
      Path file = Paths.get(properties.jwtPreviousKeyFile().strip());
      previous =
          parse(
              "argos.jwt-previous-key-file",
              readKeyFile("argos.jwt-previous-key-file", file),
              true);
    }
    try {
      ring.install(current, previous);
    } catch (IllegalArgumentException e) {
      throw new StartupPhaseException("JWT keys: " + e.getMessage(), e);
    }
    // The id is public (every token's header shows it), but in key=value form secret scanners that
    // read the log directories would report it.
    if (previous == null) {
      LOG.info("JWT signing with key id {} ({})", current.kid(), source);
    } else {
      LOG.info(
          "JWT signing with key id {} ({}); previous key id {} accepted for verification only",
          current.kid(),
          source,
          previous.kid());
    }
  }

  private static JwtKey parse(String key, String text, boolean publicAllowed)
      throws StartupPhaseException {
    try {
      return JwtKey.parse(text, publicAllowed);
    } catch (IllegalArgumentException e) {
      throw new StartupPhaseException(key + " is not a usable Ed25519 key: " + e.getMessage(), e);
    }
  }

  private static String readKeyFile(String key, Path file) throws StartupPhaseException {
    String text;
    try {
      text = PrivateFiles.read(file, MAX_KEY_FILE_BYTES);
    } catch (IOException e) {
      throw new StartupPhaseException(
          key
              + " names a file that cannot be read ("
              + file.toAbsolutePath()
              + "): "
              + e.getClass().getSimpleName(),
          e);
    }
    PrivateFiles.loosePermissions(file)
        .ifPresent(
            p ->
                LOG.warn(
                    "{} ({}) is readable by group or others ({}); restrict it to the owner (chmod"
                        + " 600)",
                    key,
                    file.toAbsolutePath(),
                    p));
    return text;
  }
}
