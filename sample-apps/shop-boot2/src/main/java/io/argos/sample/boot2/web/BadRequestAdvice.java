/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.sample.boot2.web;

import java.util.Collections;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Invalid parameters (IllegalArgumentException from common: unknown client, out-of-range values)
 * answer 400. Every other exception keeps Spring Boot's default 500 handling (18 §4.2 error-500).
 */
@RestControllerAdvice
public class BadRequestAdvice {

  @ExceptionHandler(IllegalArgumentException.class)
  public ResponseEntity<Map<String, Object>> badRequest(IllegalArgumentException e) {
    return ResponseEntity.status(HttpStatus.BAD_REQUEST)
        .body(Collections.<String, Object>singletonMap("error", String.valueOf(e.getMessage())));
  }
}
