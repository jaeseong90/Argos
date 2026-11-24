/* SPDX-License-Identifier: Apache-2.0 */
package example.custom.globs;

import java.util.concurrent.Callable;

/** Glob fixture {@code example.custom.globs.Gamma1} ({@code Gamma?} matches it). */
public class Gamma1 implements Callable<Object> {
  @Override
  public Object call() {
    return "gamma1";
  }
}
