/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.sample.common.db;

/** Unchecked wrapper of a JDBC failure in the sample data access code. */
public final class ShopDataException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  public ShopDataException(String message, Throwable cause) {
    super(message, cause);
  }
}
