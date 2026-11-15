/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.security.fixture;

import io.argos.server.auth.Sensitive;

/**
 * Negative fixture: the generated {@code toString()} of this record prints the secret.
 *
 * @param name a name
 * @param password a secret
 */
public record RecordWithDefaultToString(String name, @Sensitive String password) {}
