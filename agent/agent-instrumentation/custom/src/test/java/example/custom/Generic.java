/* SPDX-License-Identifier: Apache-2.0 */
package example.custom;

import java.util.concurrent.Callable;

/** {@code call()} returns String: javac adds a bridge {@code Object call()} (never a step). */
public class Generic implements Callable<String> {
  @Override
  public String call() {
    return "generic";
  }
}
