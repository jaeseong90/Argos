/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.bootstrap;

import io.argos.server.config.ArgosProperties;
import io.argos.server.secrets.MasterKeyLoader;
import io.argos.server.secrets.MasterKeyRing;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Loads the master key of the envelope cipher in the {@code KEYS} start phase (DSN-12-15, 12 §7.2,
 * 16 §8.2, ADR-0010 C-226 (2)), before the database migrations and before any listener: the server
 * does not start without a usable key.
 *
 * <ul>
 *   <li>Refused (one ERROR line of the start sequence with the setting names, never a key, then
 *       exit): no {@code argos.master-key} and no file at {@code argos.master-key-file}; a key that
 *       is not 32 bytes as 64 hex characters or base64; a key file that cannot be read; a key file
 *       that group or others may access (POSIX), unless {@code
 *       argos.security.master-key.permissive=true}, which logs a WARN at every start instead; a
 *       configured previous key with any of these faults.
 *   <li>Where the file system has no POSIX permissions (Windows) the permission check is not
 *       possible: one INFO line says so and the start goes on. The operator restricts the file with
 *       the operating system's tools (docs/install).
 *   <li>Success: one INFO line with the key id (and the previous key's id during a rotation).
 * </ul>
 *
 * The keys go into the {@link MasterKeyRing} that the envelope cipher reads. Contexts that do not
 * run the start sequence (the web-layer contract server) have an empty ring and answer 409 {@code
 * secret_unavailable} where a secret is needed.
 */
@Component
public class MasterKeyPhase implements StartupPhase {

  private static final Logger LOG = LoggerFactory.getLogger(MasterKeyPhase.class);

  private final ArgosProperties properties;
  private final MasterKeyRing ring;
  private final MasterKeyLoader.PermissionProbe probe;

  @Autowired
  public MasterKeyPhase(ArgosProperties properties, MasterKeyRing ring) {
    this(properties, ring, MasterKeyLoader.FILE_SYSTEM);
  }

  /** With another permission source (tests of the POSIX rule on any file system). */
  MasterKeyPhase(
      ArgosProperties properties, MasterKeyRing ring, MasterKeyLoader.PermissionProbe probe) {
    this.properties = properties;
    this.ring = ring;
    this.probe = probe;
  }

  @Override
  public PhaseOrder order() {
    return PhaseOrder.KEYS;
  }

  @Override
  public String name() {
    return "master-key";
  }

  @Override
  public void run() throws StartupPhaseException {
    MasterKeyLoader.Loaded keys;
    try {
      keys = MasterKeyLoader.load(properties, probe);
    } catch (MasterKeyLoader.MasterKeyException e) {
      throw new StartupPhaseException(e.getMessage(), e);
    }
    for (String note : keys.notes()) {
      LOG.info("{}", note);
    }
    for (String warning : keys.warnings()) {
      LOG.warn("{}", warning);
    }
    List<io.argos.server.secrets.MasterKey> previous = new ArrayList<>();
    if (keys.previous() != null) {
      previous.add(keys.previous());
    }
    ring.install(keys.current(), previous);
    if (keys.previous() == null) {
      LOG.info("Master key loaded: key id {} ({})", keys.current().kid(), keys.currentSource());
    } else {
      LOG.info(
          "Master key loaded: key id {} ({}); previous key id {} ({}) decrypts only, until"
              + " argos-admin secrets rekey has re-encrypted every stored secret",
          keys.current().kid(),
          keys.currentSource(),
          keys.previous().kid(),
          keys.previousSource());
    }
  }
}
