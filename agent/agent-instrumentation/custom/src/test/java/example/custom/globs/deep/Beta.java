/* SPDX-License-Identifier: Apache-2.0 */
package example.custom.globs.deep;

import java.util.concurrent.Callable;

/** Glob fixture one package deeper ({@code *} does not cross the dot, {@code **} does). */
public class Beta implements Callable<Object> {
  @Override
  public Object call() {
    return "beta";
  }
}
