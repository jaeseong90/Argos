/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.testing.arch.fixture.violating.core;

import io.argos.agent.testing.arch.fixture.violating.core.log.AgentLogger;
import java.util.List;

/** LOG-01 violations: collected data (SQL, URL, header) reaching INFO, WARN and ERROR logs. */
public final class LogsCollectedData {
  private static String lastSql;

  private LogsCollectedData() {}

  public static void executed(String sql, Object request, List<String> headers) {
    AgentLogger.info("executed {}", sql);
    lastSql = sql;
    AgentLogger.warn("slow SQL {}", lastSql);
    AgentLogger.error("request " + request);
    AgentLogger.info("first header {}", headers.get(0));
  }
}
