/* SPDX-License-Identifier: Apache-2.0 */
/**
 * Context propagation helpers of the core (10 §8): virtual thread detection, thread name
 * normalization with its bounded dictionary, W3C {@code traceparent}/{@code tracestate} codecs and
 * async carriers. Owner: WO-10 (virtual threads, thread names), WO-14 (W3C propagation), WO-15
 * (executor carriers).
 */
package io.argos.agent.core.context;
