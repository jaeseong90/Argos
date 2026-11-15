/* SPDX-License-Identifier: Apache-2.0 */

/**
 * Content hashes of the server (DSN-02-10, ADR-0010 C-131, C-143 (6)): {@link
 * io.argos.server.ingest.hash.Xxh3}, the copy of the agent's XXH3-64 that yields the same
 * dictionary ids. Added by the WO-21 contracts step; used by lane {@code durable-ingest}
 * (dictionary keys, error group ids) and by the test fixtures (FakeAgent dictionary entries).
 */
package io.argos.server.ingest.hash;
