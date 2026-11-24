/* SPDX-License-Identifier: Apache-2.0 */
package example.custom.globs;

import java.util.concurrent.Callable;

/** Glob fixture {@code example.custom.globs.Gamma22} ({@code Gamma?} does not match it). */
public class Gamma22 implements Callable<Object> {
  @Override
  public Object call() {
    return "gamma22";
  }
}
