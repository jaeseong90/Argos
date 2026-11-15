/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.storage.clickhouse;

import com.google.protobuf.ByteString;
import io.argos.server.storage.api.BlockToken;
import io.argos.server.storage.api.Table;

/**
 * Sends one block to ClickHouse (12 §3.8, 13 §7.4): the production implementation is {@link
 * ClickHouseBlockInserter} ({@code argos_writer}); tests substitute failing or recording ones.
 */
@FunctionalInterface
public interface BlockInserter {

  /**
   * Inserts {@code rowBinary} (the {@code RowBinaryWithNamesAndTypes} header and the rows) into
   * {@code table} with {@code token} as {@code insert_deduplication_token}.
   *
   * @throws Exception when ClickHouse did not confirm the insert; the caller retries with the same
   *     token (whether the rows arrived is unknown, the token makes the retry safe)
   */
  void insert(Table table, BlockToken token, ByteString rowBinary) throws Exception;
}
