/* SPDX-License-Identifier: Apache-2.0 */

/**
 * Read-only PostgreSQL access of the query API (WO-23, 14 §4.2 to §4.15): applications, instances,
 * events and error groups as the REST endpoints show them ({@link
 * io.argos.server.storage.postgres.catalog.CatalogRepository}). Behind the storage boundary of
 * SRV-01; every statement binds its values as JDBC parameters and runs as the runtime account
 * {@code argos_app}.
 *
 * <p>Owner: WO-23.
 */
package io.argos.server.storage.postgres.catalog;
