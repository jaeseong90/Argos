/* SPDX-License-Identifier: Apache-2.0 */

/**
 * The diagnostic bundle of {@code argos-admin bundle} (12 §9.5, 17 §4.4, SEC-06): {@link
 * io.argos.server.ops.diagnostics.DiagnosticBundle} collects the masked log, configuration,
 * versions and storage state into an owner-only {@code .tar.gz} written by {@link
 * io.argos.server.ops.diagnostics.TarGzWriter}; {@link
 * io.argos.server.ops.diagnostics.BundleMasking} masks every text in it.
 *
 * <p>Owner: WO-28.
 */
package io.argos.server.ops.diagnostics;
