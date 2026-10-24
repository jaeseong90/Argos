/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.sample.common.catalog;

import io.argos.sample.common.db.ShopSchema;
import io.argos.sample.common.seed.Seeds;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The shop-batch catalog (18 §4.1, §4.2 last row; F-A24, D-20, S-09): the Spring Batch job started
 * by hand ({@code POST /batch/run/{job}}: one Tasklet step and one chunk step of {@value
 * #CHUNK_SIZE} items), the two Quartz jobs fired now, the counters of the three {@code @Scheduled}
 * methods (fixed rate, cron, long running), HTTP endpoints that share the batch DataSource (the
 * S-09 situation: HTTP and batch work on one database), and the batch job name XSS seed (18 §4.3).
 * Owned by the shop-batch lane.
 */
public final class BatchCatalog {

  /** The Spring Batch job: {@value #TASKLET_STEP} (Tasklet) then {@value #CHUNK_STEP} (chunk). */
  public static final String SETTLEMENT_JOB = "settlementJob";

  /** Tasklet step: removes the output of earlier runs and counts the items to settle. */
  public static final String TASKLET_STEP = "prepareStep";

  /** Chunk step: reads every order item, prices it and writes a settlement line. */
  public static final String CHUNK_STEP = "settleStep";

  /** Commit interval of the chunk step (18 §4.1: chunk 5,000). */
  public static final int CHUNK_SIZE = 5000;

  /** Items of the chunk step: every order item, 1,000 orders x 20 items = 4 full chunks. */
  public static final int SETTLEMENT_ITEMS = ShopSchema.ORDERS * ShopSchema.ITEMS_PER_ORDER;

  /** Quartz job group of the two Quartz jobs. */
  public static final String QUARTZ_GROUP = "shop-batch";

  /** Quartz job: order report by status (SQL, insert, one call to ext-stub). */
  public static final String QUARTZ_REPORT_JOB = "quartzReportJob";

  /** Quartz job: keeps the report table bounded. */
  public static final String QUARTZ_CLEANUP_JOB = "quartzCleanupJob";

  /** Upper bound of {@code GET /batch/scheduled?awaitMs=}. */
  public static final long MAX_AWAIT_MS = 60_000;

  private BatchCatalog() {}

  public static List<CatalogEntry> entries() {
    List<CatalogEntry> e = new ArrayList<>();
    e.add(
        CatalogEntry.get("health", "/actuator/health")
            .check(Check.bodyContains("UP"))
            .purpose("Noise; F-A23 exclusion")
            .build());
    e.add(
        CatalogEntry.get("version", "/api/version")
            .check(Check.bodyContains("\"version\""))
            .purpose("Version tag; F-A21")
            .build());
    e.add(
        CatalogEntry.get("runtime", "/api/runtime")
            .check(Check.bodyContains("\"virtualThreads\""))
            .check(Check.bodyContains("\"quartzJobStore\""))
            .purpose("Switches, runtime facts, scheduler and job store facts")
            .build());
    e.add(
        CatalogEntry.get("orders.get", "/api/orders/1")
            .route("/api/orders/{id}")
            .check(Check.jsonEquals("id", 1))
            .purpose("HTTP request on the DataSource the jobs use (S-09, D-20: kept apart)")
            .build());
    e.add(
        CatalogEntry.get("orders.page", "/api/orders?page=0")
            .check(Check.jsonEquals("rows", ShopSchema.PAGE_SIZE))
            .purpose("HTTP request with 3 SQL on the shared DataSource (D-20)")
            .build());
    e.add(
        CatalogEntry.get("batch.jobs", "/batch/jobs")
            .check(Check.bodyContains(SETTLEMENT_JOB))
            .check(Check.bodyContains(QUARTZ_REPORT_JOB))
            .check(Check.jsonAtLeast("quartzJobsInSharedDataSource", 2))
            .purpose("Job inventory; the Quartz JDBC store lives in the shared DataSource")
            .build());
    e.add(
        CatalogEntry.post("batch.settlement", "/batch/run/" + SETTLEMENT_JOB)
            .route("/batch/run/{job}")
            .check(Check.jsonEquals("status", "COMPLETED"))
            .check(Check.jsonEquals("readCount", SETTLEMENT_ITEMS))
            .check(Check.jsonEquals("writeCount", SETTLEMENT_ITEMS))
            .check(Check.jsonEquals("chunkStepReadCount", SETTLEMENT_ITEMS))
            .check(Check.jsonEquals("chunkStepCommitCount", SETTLEMENT_ITEMS / CHUNK_SIZE + 1))
            .check(Check.jsonEquals("stepsInSharedDataSource", 2))
            .purpose(
                "Spring Batch job by hand: Tasklet step + chunk step; kind=BATCH, chunk summary; job"
                    + " metadata in the shared DataSource. The chunk step reads 20,000 items and"
                    + " commits 20,000 / 5,000 + 1 = 5 times (4 full chunks + the commit of the"
                    + " final empty chunk); an interval of 1,000 would give 21 commits, 10,000 would"
                    + " give 3")
            .build());
    e.add(
        CatalogEntry.post("batch.unknown", "/batch/run/noSuchJob")
            .route("/batch/run/{job}")
            .status(404)
            .purpose("Unknown job name: 404, no job started")
            .build());
    e.add(
        CatalogEntry.post("batch.quartz.report", "/batch/quartz/" + QUARTZ_REPORT_JOB)
            .route("/batch/quartz/{job}")
            .check(Check.jsonEquals("fired", true))
            .check(Check.jsonEquals("completed", true))
            .purpose("Quartz job fired now (SQL, insert, outgoing call to ext-stub); kind=BATCH")
            .build());
    e.add(
        CatalogEntry.post("batch.quartz.cleanup", "/batch/quartz/" + QUARTZ_CLEANUP_JOB)
            .route("/batch/quartz/{job}")
            .check(Check.jsonEquals("fired", true))
            .check(Check.jsonEquals("completed", true))
            .purpose("Second Quartz job fired now")
            .build());
    e.add(
        CatalogEntry.get("batch.scheduled", "/batch/scheduled?awaitMs=30000")
            .check(Check.jsonAtLeast("fixedRate", 1))
            .check(Check.jsonAtLeast("cron", 1))
            .check(Check.jsonAtLeast("longRunningStarted", 1))
            .phase(CatalogEntry.Phase.CONCURRENT)
            .purpose(
                "Execution counters of the @Scheduled methods (fixed rate, cron, long running);"
                    + " waits until each one has run")
            .build());
    for (int i = 0; i < Seeds.XSS.size(); i++) {
      e.add(
          CatalogEntry.post("seed.xss.batch." + i, "/seed/xss-batch?i=" + i)
              .check(Check.bodyContains(Seeds.SENTINEL))
              .check(Check.jsonEquals("status", "COMPLETED"))
              .purpose("XSS payload as a Spring Batch job name and bind parameter (18 §4.3)")
              .build());
    }
    return Collections.unmodifiableList(e);
  }
}
