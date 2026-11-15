/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.core.metric;

import io.argos.agent.bootstrap.PoolKind;

/**
 * The values of one registered connection pool at one moment ({@link PoolRegistry#snapshot()}): the
 * sources of the 10 §11 series {@code jdbc_pool_active}, {@code jdbc_pool_idle}, {@code
 * jdbc_pool_waiting} and {@code jdbc_pool_max} with the label {@code pool} = {@link #name()}, plus
 * the total of open connections where the library reports it. Immutable.
 *
 * <p>A value the pool does not report (DBCP2 has no waiting count) or whose getter failed is
 * {@value Getters#MISSING}, never 0 (C-4).
 */
public final class PoolSample {
  private final String name;
  private final int kind;
  private final long active;
  private final long idle;
  private final long waiting;
  private final long total;
  private final long max;

  PoolSample(String name, int kind, long active, long idle, long waiting, long total, long max) {
    this.name = name;
    this.kind = kind;
    this.active = active;
    this.idle = idle;
    this.waiting = waiting;
    this.total = total;
    this.max = max;
  }

  /** The pool's own name, or {@code <SimpleName>-<n>} when it has none (10 §6.4). */
  public String name() {
    return name;
  }

  /** The {@link PoolKind}. */
  public int kind() {
    return kind;
  }

  /** {@link PoolKind#name(int)} of {@link #kind()}. */
  public String kindName() {
    return PoolKind.name(kind);
  }

  /** Connections in use; -1 when missing. */
  public long active() {
    return active;
  }

  /** Idle connections; -1 when missing. */
  public long idle() {
    return idle;
  }

  /** Threads waiting for a connection; -1 when missing (always for DBCP2). */
  public long waiting() {
    return waiting;
  }

  /** Open connections (active and idle); -1 when missing (always for DBCP2). */
  public long total() {
    return total;
  }

  /** Configured maximum of connections; -1 when missing. */
  public long max() {
    return max;
  }

  @Override
  public String toString() {
    return "PoolSample{"
        + name
        + ", "
        + kindName()
        + ", active="
        + active
        + ", idle="
        + idle
        + ", waiting="
        + waiting
        + ", total="
        + total
        + ", max="
        + max
        + "}";
  }
}
