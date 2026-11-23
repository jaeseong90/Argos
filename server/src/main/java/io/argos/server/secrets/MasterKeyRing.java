/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.secrets;

import io.argos.server.auth.token.PrivateFiles;
import io.argos.server.config.ArgosProperties;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * The master keys of this server (16 §8.2): the current one, which encrypts and decrypts, and the
 * previous one of a rotation, which only decrypts.
 *
 * <p>Sources, first match wins: {@code argos.master-key} (the environment variable {@code
 * ARGOS_MASTER_KEY}), then {@code argos.master-key-file} (default {@code conf/master.key}; 17 §3
 * mounts {@code /run/secrets/master_key}). The previous key: {@code
 * argos.security.master-key.previous}, then {@code argos.security.master-key.previous-file}.
 *
 * <p>A key file that group or others can read (POSIX) is ignored with a warning unless {@code
 * argos.security.master-key.permissive} is set, which warns at every start (16 §8.2). A missing,
 * unreadable or malformed key leaves the ring without it: the server starts and every operation
 * that needs a secret answers 409 {@code secret_unavailable} (WO-25 assumption; the start refusal
 * of 16 §8.2 is WO-28's). Log lines name the setting and the key id, never the key.
 */
@Component
public class MasterKeyRing implements DisposableBean {

  private static final Logger LOG = LoggerFactory.getLogger(MasterKeyRing.class);

  /** A key file holds one line; anything much larger is not a key. */
  private static final int MAX_FILE_BYTES = 1024;

  private final MasterKey current;
  private final MasterKey previous;

  /** Loads the keys from the configuration. */
  @Autowired
  public MasterKeyRing(ArgosProperties properties) {
    boolean permissive = properties.security().masterKey().permissive();
    this.current =
        load(
            "argos.master-key",
            properties.masterKey(),
            "argos.master-key-file",
            properties.masterKeyFile(),
            permissive,
            true);
    MasterKey old =
        load(
            "argos.security.master-key.previous",
            properties.security().masterKey().previous(),
            "argos.security.master-key.previous-file",
            properties.security().masterKey().previousFile(),
            permissive,
            false);
    if (old != null && current != null && old.kid().equals(current.kid())) {
      old.close();
      old = null;
    }
    this.previous = old;
    if (current == null) {
      LOG.warn(
          "No master key is loaded (argos.master-key or argos.master-key-file): notification"
              + " channels cannot be created, changed or used until one is configured");
    } else {
      LOG.info("Master key loaded (key id {})", current.kid());
    }
    if (previous != null) {
      LOG.info("Previous master key loaded for decryption only (key id {})", previous.kid());
    }
  }

  /** A ring of the given keys (tests); either may be null. */
  public MasterKeyRing(MasterKey current, MasterKey previous) {
    this.current = current;
    this.previous = previous;
  }

  /** The key that encrypts, when one is loaded. */
  public Optional<MasterKey> current() {
    return Optional.ofNullable(current);
  }

  /** The key with id {@code kid} (current or previous), when loaded. */
  public Optional<MasterKey> find(String kid) {
    if (current != null && current.kid().equals(kid)) {
      return Optional.of(current);
    }
    if (previous != null && previous.kid().equals(kid)) {
      return Optional.of(previous);
    }
    return Optional.empty();
  }

  /** Whether secrets can be stored now. */
  public boolean available() {
    return current != null;
  }

  /** Overwrites the keys with zeros at shutdown (16 §8.2 "메모리"). */
  @Override
  public void destroy() {
    if (current != null) {
      current.close();
    }
    if (previous != null) {
      previous.close();
    }
  }

  private static MasterKey load(
      String valueKey,
      String value,
      String fileKey,
      String file,
      boolean permissive,
      boolean defaultFile) {
    if (value != null) {
      try {
        return MasterKey.parse(value);
      } catch (IllegalArgumentException e) {
        LOG.warn("{} is ignored: {}", valueKey, e.getMessage());
        return null;
      }
    }
    if (file == null) {
      return null;
    }
    Path path = Path.of(file);
    if (!Files.exists(path)) {
      if (!defaultFile || !"conf/master.key".equals(file)) {
        LOG.warn("{} is ignored: the file {} does not exist", fileKey, path);
      }
      return null;
    }
    Optional<String> loose = PrivateFiles.loosePermissions(path);
    if (loose.isPresent()) {
      if (!permissive) {
        LOG.warn(
            "{} is ignored: the file {} is readable by group or others ({}); make it 0600 or set"
                + " argos.security.master-key.permissive=true",
            fileKey,
            path,
            loose.get());
        return null;
      }
      LOG.warn(
          "{}: the file {} is readable by group or others ({}); accepted because"
              + " argos.security.master-key.permissive=true",
          fileKey,
          path,
          loose.get());
    }
    try {
      String text = PrivateFiles.read(path, MAX_FILE_BYTES);
      return MasterKey.parse(text);
    } catch (IOException e) {
      LOG.warn("{} is ignored: the file {} cannot be read", fileKey, path);
      return null;
    } catch (IllegalArgumentException e) {
      LOG.warn("{} is ignored: {} in {}", fileKey, e.getMessage(), path);
      return null;
    }
  }
}
