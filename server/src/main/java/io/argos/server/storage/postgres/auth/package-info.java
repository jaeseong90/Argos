/* SPDX-License-Identifier: Apache-2.0 */

/**
 * PostgreSQL access of user authentication (WO-24; 13 §12.1 {@code users}, {@code user_sessions},
 * V002 {@code user_permissions} and {@code login_failures_unknown}, {@code settings}): the only
 * place where the authentication code reaches the database (SRV-01). Every statement runs on the
 * runtime pool of {@code argos_app}; {@link io.argos.server.storage.postgres.auth.AuthTransactions}
 * groups several statements into one transaction.
 *
 * <p>Nothing secret is stored in plain text: passwords as bcrypt hashes, refresh tokens as SHA-256
 * hex (16 §7.1, §7.9). The records returned here never print a hash in {@code toString()}.
 *
 * <p>Owner: WO-24 (lane sessions).
 */
package io.argos.server.storage.postgres.auth;
