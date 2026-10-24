/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.sample.batch.jobs;

import java.math.BigDecimal;

/** One priced order item written by the chunk step to {@code settlement_lines}. */
public record SettlementLine(long itemId, long orderId, BigDecimal amount) {}
