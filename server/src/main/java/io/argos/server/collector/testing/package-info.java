/* SPDX-License-Identifier: Apache-2.0 */

/**
 * Test hooks of the collector's data path (18 §15, §19; development builds only, gated by {@code
 * TestingHooks}): {@link io.argos.server.collector.testing.IngestFaultInjector} ({@code
 * argos.testing.ingest-fault-file}, chaos tests 18 §11.1 T-CH-004).
 *
 * <p>Owner: WO-52.
 */
package io.argos.server.collector.testing;
