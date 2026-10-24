/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.sample.batch.jobs;

/** One row of {@code order_items} read by the chunk step. */
public record OrderItemRow(long id, long orderId, long productId, int quantity) {}
