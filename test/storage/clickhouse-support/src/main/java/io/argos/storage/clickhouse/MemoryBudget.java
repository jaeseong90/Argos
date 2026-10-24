/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.storage.clickhouse;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Values of the RAM substitution variables of {@code deploy/clickhouse} (13 §7.1, 17 §7.3) for a
 * ClickHouse server whose memory is limited to {@code limitBytes}.
 *
 * <p>Proportions of the S topology (13 §7.1): server 7.5 GiB, reader query 3 GiB (0.4 of the
 * server), writer query 2 GiB (4/15 of the server). The server value is 0.9 of the limit: inside a
 * cgroup ClickHouse caps {@code max_server_memory_usage} at {@code
 * max_server_memory_usage_to_ram_ratio} (0.9) of the limit anyway (measured on 26.8: 7.5 GiB on an
 * 8 GiB container becomes 7.2 GiB).
 *
 * @param limitBytes container (or host share) memory limit
 * @param serverBytes {@code ARGOS_CH_MAX_SERVER_MEMORY_USAGE}
 * @param readerQueryBytes {@code ARGOS_CH_READER_MAX_MEMORY_USAGE}
 * @param writerQueryBytes {@code ARGOS_CH_WRITER_MAX_MEMORY_USAGE}
 */
public record MemoryBudget(
    long limitBytes, long serverBytes, long readerQueryBytes, long writerQueryBytes) {

  /** One GiB. */
  public static final long GIB = 1L << 30;

  /** Smallest supported limit; ClickHouse needs headroom for caches and merges. */
  public static final long MIN_LIMIT_BYTES = GIB;

  public MemoryBudget {
    if (limitBytes < MIN_LIMIT_BYTES) {
      throw new IllegalArgumentException("Memory limit below 1 GiB: " + limitBytes);
    }
    if (serverBytes > limitBytes
        || readerQueryBytes > serverBytes
        || writerQueryBytes > serverBytes) {
      throw new IllegalArgumentException("Inconsistent memory budget");
    }
  }

  /**
   * Budget for a container memory limit. In a container: at most 0.9 x the container memory limit
   * (ClickHouse caps a larger value there).
   */
  public static MemoryBudget forContainerLimit(long limitBytes) {
    long server = limitBytes / 10 * 9;
    return new MemoryBudget(limitBytes, server, server / 5 * 2, server / 15 * 4);
  }

  /** Budget with the absolute values of 13 §7.1 (16 GiB host, ClickHouse share 7.5 GiB). */
  public static MemoryBudget sTopology() {
    return new MemoryBudget(8 * GIB, 8_053_063_680L, 3_221_225_472L, 2_147_483_648L);
  }

  /** The substitution variables as environment entries. */
  public Map<String, String> environment() {
    Map<String, String> env = new LinkedHashMap<>();
    env.put("ARGOS_CH_MAX_SERVER_MEMORY_USAGE", Long.toString(serverBytes));
    env.put("ARGOS_CH_READER_MAX_MEMORY_USAGE", Long.toString(readerQueryBytes));
    env.put("ARGOS_CH_WRITER_MAX_MEMORY_USAGE", Long.toString(writerQueryBytes));
    return env;
  }
}
