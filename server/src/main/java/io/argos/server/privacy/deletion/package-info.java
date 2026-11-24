/* SPDX-License-Identifier: Apache-2.0 */

/**
 * Privacy deletion jobs (SEC-33, F-S32; 13 §9, 16 §6.8): {@link
 * io.argos.server.privacy.deletion.DeletionJobService} runs the ClickHouse deletion mutations of a
 * user id or a client address, {@link io.argos.server.privacy.deletion.DeletionQueries} builds
 * them.
 *
 * <p>Owner: WO-28.
 */
package io.argos.server.privacy.deletion;
