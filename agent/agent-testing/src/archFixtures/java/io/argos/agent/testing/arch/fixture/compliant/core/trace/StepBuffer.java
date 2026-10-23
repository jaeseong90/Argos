/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.testing.arch.fixture.compliant.core.trace;

/** Step builder: structure of arrays with primitive, String and core types only (10 §9.1). */
public final class StepBuffer {
  int[] type = new int[64];
  long[] nameId = new long[64];
  long[] elapsedNanos = new long[64];
  String[] detail;
  long[][] stack;
  SummaryStep[] summary;
  int size;

  public int add(int stepType, long name) {
    if (size == type.length) {
      return -1;
    }
    type[size] = stepType;
    nameId[size] = name;
    return size++;
  }
}
