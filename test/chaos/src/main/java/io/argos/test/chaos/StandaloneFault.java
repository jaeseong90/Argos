/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.test.chaos;

import java.util.List;

/**
 * A scenario whose fault exists from the moment its application starts (T-CH-007: a certificate
 * that does not name the address the agent dials), so the baseline, fault and recovery windows of
 * {@link ScenarioDriver} do not apply. The driver calls {@link #prepare}, then {@link #run}, judges
 * the rows the scenario returns with the same rules ({@link Judge}) and always calls {@link
 * #cleanup}. The scenario sets the context's times itself ({@link ScenarioContext#faultStart},
 * ...).
 */
public interface StandaloneFault extends Fault {

  /** Runs the scenario and returns its rows (common and own). */
  List<Check> run(ScenarioContext c) throws Exception;

  @Override
  default void inject(ScenarioContext c) {}

  @Override
  default void remove(ScenarioContext c) {}
}
