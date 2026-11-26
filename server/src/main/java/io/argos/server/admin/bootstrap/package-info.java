/* SPDX-License-Identifier: Apache-2.0 */

/**
 * Applications and agent tokens registered at start from {@code argos.bootstrap.apps-file} ({@code
 * ARGOS_BOOTSTRAP_APPS_FILE}; 17 §3.2, §3.8, §6.4; WO-41): the file format ({@link
 * io.argos.server.admin.bootstrap.BootstrapAppsFile}) and the start step ({@link
 * io.argos.server.admin.bootstrap.AppBootstrapPhase}). Tokens come from files the installation
 * generated; PostgreSQL keeps their SHA-256 and prefix only.
 *
 * <p>Owner: WO-41 (lane B).
 */
package io.argos.server.admin.bootstrap;
