/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.sample.boot2.web;

import io.argos.sample.common.db.PoolExhauster;
import io.argos.sample.common.db.ShopJdbc;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Database endpoints of 18 §4.2: pool exhaustion, commit and rollback, Statement with literals,
 * batch insert.
 */
@RestController
public class DbController {

  private final ShopJdbc jdbc;
  private final PoolExhauster exhauster;
  private final TransactionTemplate transactions;

  public DbController(ShopJdbc jdbc, PoolExhauster exhauster, TransactionTemplate transactions) {
    this.jdbc = jdbc;
    this.exhauster = exhauster;
    this.transactions = transactions;
  }

  /** maxPoolSize + 1 connections held for {@code hold} ms; 409 while another run is active. */
  @GetMapping("/api/pool/exhaust")
  public ResponseEntity<Map<String, Object>> exhaust(
      @RequestParam(name = "hold", defaultValue = "5000") long hold) throws InterruptedException {
    Map<String, Object> result = exhauster.exhaust(hold);
    if (result == null) {
      return ResponseEntity.status(HttpStatus.CONFLICT)
          .body(Collections.<String, Object>singletonMap("busy", true));
    }
    return ResponseEntity.ok(result);
  }

  @PostMapping("/api/tx/commit")
  public Map<String, Object> commit() {
    Long id = transactions.execute(status -> jdbc.insertAudit("tx commit"));
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("id", id);
    result.put("committed", id != null && jdbc.auditExists(id));
    return result;
  }

  @PostMapping("/api/tx/rollback")
  public Map<String, Object> rollback() {
    Long id =
        transactions.execute(
            status -> {
              long inserted = jdbc.insertAudit("tx rollback");
              status.setRollbackOnly();
              return inserted;
            });
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("id", id);
    result.put("rolledBack", id != null && !jdbc.auditExists(id));
    return result;
  }

  @GetMapping("/api/stmt")
  public Map<String, Object> statement(@RequestParam(name = "q", defaultValue = "kim") String q) {
    return jdbc.statementSearch(q);
  }

  @GetMapping("/api/batch-insert")
  public Map<String, Object> batchInsert() {
    return jdbc.batchInsert(50);
  }
}
