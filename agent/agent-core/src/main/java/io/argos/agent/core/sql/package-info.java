/* SPDX-License-Identifier: Apache-2.0 */
/**
 * SQL text handling (10 §9.4, D-22, SEC-35): normalization to {@code ?} literals, the {@code
 * <unparsed>} fail-safe, bind parameter capture and JDBC URL identity. Owner: WO-10 (normalizer
 * core from WO-02), WO-13 (bind parameters, JDBC URL parser).
 */
package io.argos.agent.core.sql;
