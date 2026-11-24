/* SPDX-License-Identifier: Apache-2.0 */
package example.custom.globs;

import java.util.concurrent.Callable;

/** Glob fixture {@code example.custom.globs.Alpha}. */
public class Alpha implements Callable<Object> {
  @Override
  public Object call() {
    return "alpha";
  }
}
