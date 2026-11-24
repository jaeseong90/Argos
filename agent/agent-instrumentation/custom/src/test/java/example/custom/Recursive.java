/* SPDX-License-Identifier: Apache-2.0 */
package example.custom;

import java.util.function.Function;

/** Recursion of the summary-mode test: {@code apply(n)} calls {@link #depth} n + 1 times. */
public class Recursive implements Function<Integer, Integer> {
  @Override
  public Integer apply(Integer n) {
    return Integer.valueOf(depth(n.intValue()));
  }

  public int depth(int n) {
    return n <= 0 ? 0 : 1 + depth(n - 1);
  }
}
