/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.instrumentation.scheduler;

import org.quartz.JobDetail;
import org.quartz.JobExecutionContext;
import org.quartz.JobKey;

/**
 * Quartz job names (10 §6.8): {@code BATCH <group>.<name>}, the {@code JobKey} of the job detail
 * ({@code JobKey.toString()} is {@code group.name} in Quartz 2.3 to 2.5).
 */
public final class QuartzHelper {
  private QuartzHelper() {}

  /** The service name of the job run by {@code jobExecutionContext}, or null without a key. */
  public static String serviceName(Object jobExecutionContext) {
    if (jobExecutionContext == null) {
      return null;
    }
    JobDetail detail = ((JobExecutionContext) jobExecutionContext).getJobDetail();
    if (detail == null) {
      return null;
    }
    JobKey key = detail.getKey();
    if (key == null) {
      return null;
    }
    return SchedulerHelper.PREFIX + key.getGroup() + "." + key.getName();
  }
}
