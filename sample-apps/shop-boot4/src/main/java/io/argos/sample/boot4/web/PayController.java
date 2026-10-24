/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.sample.boot4.web;

import io.argos.sample.common.http.PayService;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * GET /api/pay/{id}: one call to ext-stub with {@code ?client=}; {@code timeout=1}, {@code
 * refuse=1}, {@code status=5xx} and {@code redirect=1} select the failure classes (common:
 * PayService). The answer status follows OutboundOutcome (200, 502, 503, 504).
 */
@RestController
public class PayController {

  private final PayService pay;

  public PayController(PayService pay) {
    this.pay = pay;
  }

  @GetMapping("/api/pay/{id}")
  public ResponseEntity<Map<String, Object>> pay(
      @PathVariable("id") long id,
      @RequestParam(name = "client", required = false) String client,
      @RequestParam(name = "timeout", defaultValue = "false") boolean timeout,
      @RequestParam(name = "refuse", defaultValue = "false") boolean refuse,
      @RequestParam(name = "status", required = false) Integer status,
      @RequestParam(name = "redirect", defaultValue = "false") boolean redirect) {
    PayService.Result result =
        pay.pay(
            id,
            new PayService.Options()
                .client(client)
                .timeout(timeout)
                .refuse(refuse)
                .status(status)
                .redirect(redirect));
    return ResponseEntity.status(result.httpStatus()).body(result.body());
  }
}
