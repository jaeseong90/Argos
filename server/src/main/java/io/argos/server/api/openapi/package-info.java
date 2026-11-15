/* SPDX-License-Identifier: Apache-2.0 */

/**
 * The OpenAPI document of the REST API at run time (WO-24; 14 §2.8, §8, DSN-12-11, ADR-0010 C-110):
 * springdoc serves it to viewer sessions at {@code /api/v1/openapi} (JSON) and {@code
 * /api/v1/openapi.yaml}; {@link io.argos.server.api.openapi.ArgosOperationCustomizer} adds the
 * {@code x-argos-*} extensions from the handler annotations of this package and {@link
 * io.argos.server.auth.RequiresRole}. The committed specification file, its lint and the
 * compatibility diff belong to WO-23.
 *
 * <p>Owner: WO-24.
 */
package io.argos.server.api.openapi;
