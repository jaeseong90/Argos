/* SPDX-License-Identifier: Apache-2.0 */
package example.custom;

import java.util.concurrent.Callable;

/** Throws the exception instance it was given (the advice must not change it). */
public class Failing implements Callable<Object> {
  public static final IllegalStateException FAILURE = new IllegalStateException("custom-failure");

  @Override
  public Object call() {
    throw FAILURE;
  }
}
