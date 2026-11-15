/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.ops.migration;

/**
 * One schema script {@code schema/V<NNN>__<desc>.sql} of a {@link MigrationSource}.
 *
 * @param number the script number ({@code NNN})
 * @param version the history version, {@code V<NNN>}
 * @param description the {@code <desc>} part of the file name
 * @param path path in the source, e.g. {@code schema/V001__init.sql}
 * @param expanded the template after the {@code {rollup:}} expansion, before the environment
 *     placeholders (C-56)
 * @param checksum lower-case hex SHA-256 of {@code expanded}
 */
public record SchemaScript(
    int number, String version, String description, String path, String expanded, String checksum) {

  @Override
  public String toString() {
    return version + " (" + description + ")";
  }
}
