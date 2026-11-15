/* SPDX-License-Identifier: Apache-2.0 */
/**
 * The {@code argos-watch} thread tasks (10 §13.1, §14): slow stack capture, overhead monitor and
 * the maximum transaction duration. WO-12 adds {@link
 * io.argos.agent.core.watch.MaxDurationEnforcer} ({@code transaction.max-duration-ms}, 10 §6.7);
 * slow stacks and the overhead monitor are WO-19. The thread itself is {@code
 * io.argos.agent.core.boot.WatchThread} (WO-02, WO-10), which registers the tasks at start.
 */
package io.argos.agent.core.watch;
