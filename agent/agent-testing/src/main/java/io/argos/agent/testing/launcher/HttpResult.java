/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.testing.launcher;

import java.util.List;
import java.util.Map;

/**
 * The result of one HTTP call to a sample app.
 *
 * @param status HTTP status
 * @param body response body (UTF-8)
 * @param headers response headers
 */
public record HttpResult(int status, String body, Map<String, List<String>> headers) {
  public HttpResult {
    headers = Map.copyOf(headers);
  }
}
