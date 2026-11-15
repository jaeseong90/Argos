/* SPDX-License-Identifier: Apache-2.0 */

/**
 * The REST error contract (14 §2.6, DSN-14-05; 16 §7.14; SEC-56): {@link
 * io.argos.server.api.error.ErrorCode}, {@link io.argos.server.api.error.ErrorBody}, {@link
 * io.argos.server.api.error.ApiException}, the single writer {@link
 * io.argos.server.api.error.ErrorBodyWriter}, the global exception mapping {@link
 * io.argos.server.api.error.ApiExceptionHandler} and the error page {@link
 * io.argos.server.api.error.ApiErrorController}.
 *
 * <p>Owner: WO-24. Endpoints of later work orders throw {@code ApiException} (or let framework
 * exceptions through) and never build error bodies themselves.
 */
package io.argos.server.api.error;
