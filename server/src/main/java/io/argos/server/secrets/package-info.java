/* SPDX-License-Identifier: Apache-2.0 */

/**
 * Secret encryption (12 §8.2): {@code MasterKey}, {@code EnvelopeCipher} (AES-256-GCM), {@code
 * SecretField}. The master key is loaded in the {@code KEYS} phase of the start sequence.
 *
 * <p>Owner: WO-28.
 */
package io.argos.server.secrets;
