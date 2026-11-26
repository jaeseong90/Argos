/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.test.soak;

import io.argos.agent.testing.launcher.HttpResult;
import io.argos.agent.testing.launcher.RunningApp;
import io.argos.server.testing.ServerTestEnvironment;
import io.argos.storage.clickhouse.Account;
import java.io.IOException;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The batch side of 18 §11.2 ("shop-batch 배치 매시간"): starts shop-batch's Spring Batch job on a plain
 * thread of the app ({@code POST /batch/run/settlementJob?detached=true}, a BATCH transaction of
 * the agent) once per batch interval, skips a start while the previous run is still running, and
 * applies the alert templates to the batch application once its first BATCH transaction is stored
 * (the batch template needs one). The app's own {@code @Scheduled} and Quartz jobs run on their
 * schedules besides.
 */
final class BatchDriver {

  private static final Pattern STATE = Pattern.compile("\"state\"\\s*:\\s*\"([A-Z_]+)\"");

  private final RunningApp app;
  private final AlertWatch alerts;
  private final ServerTestEnvironment env;
  private final int appId;
  private long started;
  private long refused;
  private long skipped;
  private boolean templatesApplied;
  private String templates = "not applied: no BATCH transaction stored before the next start";

  BatchDriver(RunningApp app, AlertWatch alerts, ServerTestEnvironment env, int appId) {
    this.app = app;
    this.alerts = alerts;
    this.env = env;
    this.appId = appId;
  }

  /** One scheduled start. */
  synchronized void trigger() {
    try {
      if (started > 0) {
        String state = state(app.get("/batch/executions/last").body());
        if ("RUNNING".equals(state)) {
          skipped++;
          return;
        }
        applyTemplatesOnce();
      }
      HttpResult r =
          app.call("POST", "/batch/run/" + SoakRunner.BATCH_JOB + "?detached=true", Map.of());
      if (r.status() == 202) {
        started++;
      } else {
        refused++;
      }
    } catch (IOException e) {
      refused++;
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  /** The counts and the last state for the report. */
  synchronized void finish(RunFacts f) {
    f.batchRuns = started;
    f.batchRefused = refused;
    String last = "none";
    if (started > 0) {
      try {
        last = state(app.get("/batch/executions/last?awaitMs=60000").body());
      } catch (IOException e) {
        last = "unreadable: " + e.getMessage();
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        last = "interrupted";
      }
    }
    f.batchLastState =
        last + (skipped > 0 ? " (" + skipped + " start(s) skipped while a run was running)" : "");
    f.endState.put("Alert templates of " + SoakRunner.BATCH_APP, templates);
  }

  private void applyTemplatesOnce() {
    if (templatesApplied) {
      return;
    }
    String n =
        env.clickHouse(Account.MIGRATOR)
            .scalar(
                "SELECT count() FROM argos.xlog WHERE app_id = " + appId + " AND kind = 'BATCH'")
            .strip();
    if (n.matches("\\d+") && Long.parseLong(n) > 0) {
      templatesApplied = true;
      templates = "after the first BATCH transaction: " + alerts.applyTemplates(appId);
      SoakRunner.log("templates of " + SoakRunner.BATCH_APP + ": " + templates);
    }
  }

  /** {@code state} of a {@code /batch/executions} answer, or {@code ?}. */
  static String state(String body) {
    Matcher m = STATE.matcher(body == null ? "" : body);
    return m.find() ? m.group(1) : "?";
  }
}
