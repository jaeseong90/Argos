/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.sample.batch.web;

import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** Invalid parameters (IllegalArgumentException) answer 400; everything else keeps Boot's 500. */
@RestControllerAdvice
public class BadRequestAdvice {

  @ExceptionHandler(IllegalArgumentException.class)
  public ResponseEntity<Map<String, Object>> badRequest(IllegalArgumentException e) {
    return ResponseEntity.status(HttpStatus.BAD_REQUEST)
        .body(Map.of("error", String.valueOf(e.getMessage())));
  }
}
