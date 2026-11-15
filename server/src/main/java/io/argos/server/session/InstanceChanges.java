/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.session;

/**
 * The fields of an {@code InstanceUpdate} (11 §4.2) that differ from what the session holds, ready
 * for {@link SessionStore#applyInstanceUpdate}. {@code null} means unchanged (11 §4.4: absent
 * optional fields leave the instance unchanged). {@code InstanceUpdate.connectors} has no column
 * (V001) and is not stored.
 *
 * @param instanceName new {@code instances.name} ({@code host:port}, 12 §4.3)
 * @param virtualThreads new {@code virtual_threads}
 * @param appVersion new {@code version_tag} (also the session's {@code app_version}, C-143 (6))
 * @param configHash new {@code config_hash}
 */
public record InstanceChanges(
    String instanceName, Boolean virtualThreads, String appVersion, String configHash) {

  /** Whether nothing changes. */
  public boolean isEmpty() {
    return instanceName == null
        && virtualThreads == null
        && appVersion == null
        && configHash == null;
  }

  /** These changes without the name (after a name conflict). */
  public InstanceChanges withoutName() {
    return new InstanceChanges(null, virtualThreads, appVersion, configHash);
  }
}
