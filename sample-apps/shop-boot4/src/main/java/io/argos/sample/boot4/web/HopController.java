/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.sample.boot4.web;

import io.argos.sample.common.http.HopService;
import io.argos.sample.common.http.PayService;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * GET /api/hop/{id}?depth=N&client=X[&fail=timeout|refuse|status500][&mode=M][&preset=1]: a chain
 * of calls to SAMPLE_HOP_URL (default: this app) that ends with the ext-stub call of GET
 * /api/pay/{id} (common: HopService; distributed traces, T-ACC-05).
 */
@RestController
public class HopController {

  private final HopService hops;

  public HopController(PayService pay) {
    this.hops = HopService.fromEnvironment(pay);
  }

  @GetMapping("/api/hop/{id}")
  public ResponseEntity<Map<String, Object>> hop(
      @PathVariable("id") long id,
      @RequestParam(name = "depth", defaultValue = "1") int depth,
      @RequestParam(name = "client", required = false) String client,
      @RequestParam(name = "fail", required = false) String fail,
      @RequestParam(name = "mode", required = false) String mode,
      @RequestParam(name = "preset", defaultValue = "false") boolean preset,
      HttpServletRequest request) {
    PayService.Result result =
        hops.hop(
            id,
            new HopService.Options()
                .client(client)
                .depth(depth)
                .fail(fail)
                .mode(mode)
                .preset(preset),
            "http://127.0.0.1:" + request.getLocalPort() + request.getContextPath());
    return ResponseEntity.status(result.httpStatus()).body(result.body());
  }
}
