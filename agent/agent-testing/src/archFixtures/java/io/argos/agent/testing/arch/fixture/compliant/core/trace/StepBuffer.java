/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.testing.arch.fixture.compliant.core.trace;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLongArray;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicReferenceArray;

/**
 * Step builder: structure of arrays with primitive, String and core types only (10 §9.1), also in
 * atomic holders (primitives, or references whose type argument is allowed).
 */
public final class StepBuffer {
  int[] type = new int[64];
  long[] nameId = new long[64];
  long[] elapsedNanos = new long[64];
  String[] detail;
  long[][] stack;
  SummaryStep[] summary;
  int size;
  final AtomicInteger next = new AtomicInteger();
  final AtomicLongArray rows = new AtomicLongArray(64);
  final AtomicReference<String[]> details = new AtomicReference<String[]>();
  final AtomicReferenceArray<SummaryStep> chunks = new AtomicReferenceArray<SummaryStep>(4);

  public int add(int stepType, long name) {
    if (size == type.length) {
      return -1;
    }
    type[size] = stepType;
    nameId[size] = name;
    next.incrementAndGet();
    rows.set(0, name);
    return size++;
  }

  public int chunks() {
    return chunks.length() + (details.get() == null ? 0 : 1);
  }
}
