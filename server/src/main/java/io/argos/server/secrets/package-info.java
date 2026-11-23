/* SPDX-License-Identifier: Apache-2.0 */

/**
 * Secret encryption (12 §8.2, 16 §8.2, §8.3): {@link io.argos.server.secrets.MasterKey}, {@link
 * io.argos.server.secrets.MasterKeyRing} (the current and the previous key) and {@link
 * io.argos.server.secrets.EnvelopeCipher} (AES-256-GCM envelopes {@code v1:<kid>:<nonce>:<ct>}).
 *
 * <p>WO-25 brought the minimal part the notification channels need (channel URLs, recipients and
 * webhook signing keys, SEC-52): without a master key the server starts and every operation that
 * stores or reads a secret answers 409 {@code secret_unavailable}. The start refusal of 16 §8.2,
 * the {@code KEYS} phase, {@code SecretField} and the re-encryption command ({@code argos-admin
 * secrets rekey}) are WO-28's.
 */
package io.argos.server.secrets;
