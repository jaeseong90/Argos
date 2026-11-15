/* SPDX-License-Identifier: Apache-2.0 */

/**
 * Common infrastructure of the query API (WO-23; 14 §2, §3, §7): the source rule ({@link
 * io.argos.server.api.query.SourceSelector}, a pure planner, and {@link
 * io.argos.server.api.query.QuerySources} with the server's retentions, clock and debug header),
 * the response envelope ({@link io.argos.server.api.query.ResponseEnvelope}, 14 §2.5), the input
 * limits ({@link io.argos.server.api.query.QueryLimits}, 14 §7.2) and the 256 KB body limit ({@link
 * io.argos.server.api.query.QueryBodyLimitAdvice}), the keyset cursor ({@link
 * io.argos.server.api.query.CursorCodec}) and the rules for collected strings ({@link
 * io.argos.server.api.query.UntrustedStrings}, {@link
 * io.argos.server.api.query.UntrustedStringsModule}, 14 §2.10).
 *
 * <p>The ClickHouse and PostgreSQL side is {@code io.argos.server.storage.query}.
 *
 * <p>Owner: WO-23.
 */
package io.argos.server.api.query;
