/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.sample.batch.web;

import io.argos.sample.batch.jobs.BatchJobs;
import io.argos.sample.common.seed.Seeds;
import java.util.Map;
import org.springframework.batch.core.JobExecutionException;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * POST /seed/xss-batch?i=0..3: runs a Spring Batch job named after XSS payload {@code i} (18 §4.3:
 * "batch job name"); the payload is a bind parameter of the step's query too. The answer carries
 * the job name, hence the ARGOS_SEED sentinel.
 */
@RestController
@RequestMapping("/seed")
public class SeedBatchController {

  private final BatchJobs batchJobs;

  public SeedBatchController(BatchJobs batchJobs) {
    this.batchJobs = batchJobs;
  }

  @PostMapping("/xss-batch")
  public Map<String, Object> xssBatch(@RequestParam(name = "i", defaultValue = "0") int index)
      throws JobExecutionException {
    return batchJobs.runSeedJob(Seeds.pick(Seeds.XSS, index));
  }
}
