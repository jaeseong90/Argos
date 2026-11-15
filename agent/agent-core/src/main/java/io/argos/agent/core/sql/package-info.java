/* SPDX-License-Identifier: Apache-2.0 */
/**
 * SQL text handling (10 §6.3, §9.4, D-22, SEC-35, SEC-3A): the tokenizer and normalizer ({@code
 * SqlTokenizer}, {@code SqlNormalizer}: literals become {@code ?}, the {@code <unparsed>}
 * fail-safe, {@code norm_version}), the dialect rules of the JDBC targets ({@code SqlDialect}), the
 * SQL id cache ({@code SqlIds}), the JDBC URL parser and target ids ({@code JdbcUrlParser}, {@code
 * DbIdentity}) and bind parameter capture ({@code BindParameterCapturer}). Owner: WO-13 (the
 * normalizer core comes from WO-02).
 */
package io.argos.agent.core.sql;
