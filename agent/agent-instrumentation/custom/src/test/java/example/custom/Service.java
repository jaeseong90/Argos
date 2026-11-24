/* SPDX-License-Identifier: Apache-2.0 */
package example.custom;

import java.util.concurrent.Callable;

/** An application service of the custom module tests (defined per test by FixtureLoader). */
public class Service implements Callable<Object> {
  @Override
  public Object call() throws Exception {
    return Integer.valueOf(work() + helper());
  }

  public int work() {
    return 1;
  }

  private int helper() {
    return 2;
  }

  /** A lambda whose body calls {@link #work()}: the lambda class and its body are never steps. */
  public Callable<Object> viaLambda() {
    return () -> Integer.valueOf(work());
  }
}
