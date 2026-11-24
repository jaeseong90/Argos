/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.privacy.salt;

/** Test access to the package-private hash of {@link AppSaltService}. */
public final class AppSaltServiceAccess {

  private AppSaltServiceAccess() {}

  /** {@code AppSaltService.hash}. */
  public static String hash(byte[] salt, String normalizedUserId) {
    return AppSaltService.hash(salt, normalizedUserId);
  }
}
