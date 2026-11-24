/* SPDX-License-Identifier: Apache-2.0 */
package example.custom;

/** An {@code as-transaction} entry point: {@code run()} calls {@link #step()}. */
public class Job implements Runnable {
  @Override
  public void run() {
    step();
  }

  public void step() {
    // work of the job
  }
}
