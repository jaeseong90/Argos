/* SPDX-License-Identifier: Apache-2.0 */
/**
 * The pre-flight check {@code java -jar argos-agent.jar --check} (10 §13.3, F-A27): configuration,
 * token, DNS, TCP, TLS, hello, clock, JDK, class path compatibility table, log directory and token
 * file permissions, as a table or JSON with exit codes 0, 1 and 2 (WO-02 item 6, WO-19 the rest).
 * The only agent package besides {@code core.log}'s fallback that prints (AGT-02).
 */
package io.argos.agent.core.check;
