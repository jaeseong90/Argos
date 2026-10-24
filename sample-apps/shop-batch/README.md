# shop-batch

The non-HTTP entry points of F-A24 on one shared DataSource (docs/18 §4.1; D-20, S-09): three
`@Scheduled` kinds, two Quartz jobs, a Spring Batch job with a Tasklet step and a chunk step of
5,000 items, and a few HTTP endpoints on the same database. Spring Boot 3.5, Java 17; runs on 17,
21 and 25 (docs/02 §9 and docs/18 §6.1 T-MX-070~075; docs/18 §4.1 lists 17 and 21). Catalog:
`common/.../catalog/BatchCatalog.java`.

## One DataSource

common's `SampleDataSources` builds the only DataSource (`SAMPLE_DB`, `SAMPLE_POOL`,
`SAMPLE_ROUTING_DS`). It holds the shop data, this app's tables (`settlement_lines`,
`order_reports`), the Spring Batch metadata (`BATCH_*`, `spring.batch.jdbc.initialize-schema=always`)
and the Quartz JDBC job store (`QRTZ_*`, Spring's `LocalDataSourceJobStore`). The HTTP endpoints,
the jobs, Spring Batch's `JobRepository` and Quartz all use it and one transaction manager.

## Jobs

| Kind | Name (service name of F-A24) | Schedule | Work |
|---|---|---|---|
| `@Scheduled` fixed rate | `ScheduledJobs#refreshOrderStats` | every `SAMPLE_BATCH_FIXED_RATE_MS` (5,000), first after `SAMPLE_BATCH_FIXED_RATE_INITIAL_DELAY_MS` (1,000) | 1 SQL |
| `@Scheduled` cron | `ScheduledJobs#rollupTopCustomers` | `SAMPLE_BATCH_CRON` (`*/10 * * * * *`) | 1 aggregate SQL |
| `@Scheduled` long running | `ScheduledJobs#settlementScan` | first after `SAMPLE_BATCH_LONG_INITIAL_DELAY_MS` (1,000), again `SAMPLE_BATCH_LONG_INTERVAL_MS` (40,000) after the end | one read-only transaction held `SAMPLE_BATCH_LONG_DURATION_MS` (20,000): a 100-order slice every `SAMPLE_BATCH_LONG_SLICE_MS` (500) |
| Quartz | `quartzReportJob` (`OrderReportJob`) | cron `SAMPLE_BATCH_QUARTZ_REPORT_CRON` (`0/30 * * * * ?`) | aggregate SQL, JDBC batch insert, one call to ext-stub (HttpURLConnection) |
| Quartz | `quartzCleanupJob` (`ReportCleanupJob`) | every `SAMPLE_BATCH_QUARTZ_CLEANUP_MS` (60,000) | keeps the newest 200 report rows (2 SQL) |
| Spring Batch | `settlementJob` | `POST /batch/run/settlementJob` only (`spring.batch.job.enabled=false`) | Tasklet step `prepareStep` (cleanup + count), chunk step `settleStep`: 20,000 order items read in pages of 5,000, priced, written with JDBC batches, commit interval 5,000 (4 full chunks) |

Spring Batch runs the job in the calling thread (Boot's `JobLauncher`); every run gets a unique
`run.id`, so a job can be started any number of times. `SAMPLE_VT=true` (JDK 21) runs Tomcat, the
`@Scheduled` methods and the task executor on virtual threads.

## Endpoints

| Endpoint | Answer |
|---|---|
| `POST /batch/run/{job}` | runs the Spring Batch job to its end: 200 with the execution summary (status, read/write/commit counts per step, `stepsInSharedDataSource`), 500 when it did not complete, 404 for an unknown job |
| `POST /batch/quartz/{job}` | fires the Quartz job now and waits up to 30 s for it: `fired`, `completed`, last result; 504 when it did not finish, 404 for an unknown job |
| `GET /batch/scheduled?awaitMs=` | counters of the three `@Scheduled` methods; `awaitMs` (≤ 60,000) waits until each has run once |
| `GET /batch/jobs` | Spring Batch and Quartz jobs (next fire times), `quartzJobsInSharedDataSource` |
| `POST /seed/xss-batch?i=0..3` | a Spring Batch job named after XSS payload `i` (18 §4.3, "batch job name"); the payload is also a bind parameter |
| `GET /api/orders/{id}`, `GET /api/orders?page=` | HTTP on the DataSource the jobs use (S-09, D-20) |
| `GET /api/version`, `GET /api/runtime`, `GET /actuator/health` | as the shop apps; `/api/runtime` adds `quartzJobStore`, `quartzStoreInAppDataSource`, `batchMetadataInAppDataSource`, `scheduledVirtualThreads`, `taskScheduler` |

## Smoke

The smoke triggers every job and checks its completion: `settlementJob` (`status=COMPLETED`,
`readCount=writeCount=20000`, both steps in the shared DataSource, and the chunk step's own counts
`chunkStepReadCount=20000` and `chunkStepCommitCount=5`), both Quartz jobs (`fired`,
`completed`), the three `@Scheduled` kinds (each at least once), the four XSS job names (sentinel
`ARGOS_SEED` in the answer).

The commit count is measured, not configured: 20,000 items at 5,000 per chunk are
`20000 / 5000 + 1 = 5` commits, 4 full chunks plus the commit of the final empty chunk (the reader
is exhausted only when the fifth chunk starts). A different commit interval changes it (1,000 gives
21 commits, 10,000 gives 3).

| Variant | JDK | Extra checks |
|---|---|---|
| `default` | 17 | `quartzJobStore=LocalDataSourceJobStore`, Quartz store and Batch metadata in the app's DataSource |
| `jdk21` | 21 | |
| `jdk25` | 25 | with `--sun-misc-unsafe-memory-access=deny` (as the 02 §9 matrix runs JDK 25) |
| `vt` | 21 | `SAMPLE_VT=true`: request and `@Scheduled` threads are virtual |
| `routing-ds` | 17 | `SAMPLE_ROUTING_DS=true`: the four-level chain under Batch, Quartz and the shop data |

PostgreSQL 16 and MySQL 8.4 (`SAMPLE_DB=postgres|mysql`, two starts on the same database) were
checked by hand with the smoke runner; Spring Batch and Quartz pick their schema scripts and the
Quartz driver delegate from the database.

```sh
./gradlew :sample-apps:shop-batch:sampleSmoke
./gradlew :sample-apps:shop-batch:sampleImage      # argos-sample/shop-batch:dev
```
