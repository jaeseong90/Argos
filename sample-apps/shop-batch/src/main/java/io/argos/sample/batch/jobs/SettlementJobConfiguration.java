/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.sample.batch.jobs;

import io.argos.sample.common.catalog.BatchCatalog;
import java.math.BigDecimal;
import java.util.Map;
import javax.sql.DataSource;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.Step;
import org.springframework.batch.core.configuration.annotation.StepScope;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.batch.item.ItemProcessor;
import org.springframework.batch.item.database.JdbcBatchItemWriter;
import org.springframework.batch.item.database.JdbcPagingItemReader;
import org.springframework.batch.item.database.Order;
import org.springframework.batch.item.database.builder.JdbcBatchItemWriterBuilder;
import org.springframework.batch.item.database.builder.JdbcPagingItemReaderBuilder;
import org.springframework.batch.repeat.RepeatStatus;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * The Spring Batch job of 18 §4.1: {@value BatchCatalog#SETTLEMENT_JOB} = Tasklet step {@value
 * BatchCatalog#TASKLET_STEP} then chunk step {@value BatchCatalog#CHUNK_STEP} with a commit
 * interval of {@value BatchCatalog#CHUNK_SIZE}. The chunk step reads all 20,000 order items in
 * pages of 5,000 (4 chunks, so the agent's chunk summary has repetitions to fold), prices each item
 * and writes one settlement line per item. Reader, writer, job metadata and the shop data share the
 * app's single DataSource and transaction manager.
 */
@Configuration(proxyBeanMethods = false)
public class SettlementJobConfiguration {

  @Bean
  public Job settlementJob(
      JobRepository jobRepository,
      @Qualifier("prepareStep") Step prepareStep,
      @Qualifier("settleStep") Step settleStep) {
    return new JobBuilder(BatchCatalog.SETTLEMENT_JOB, jobRepository)
        .start(prepareStep)
        .next(settleStep)
        .build();
  }

  /**
   * Tasklet step: deletes the settlement lines of earlier runs (the table stays bounded) and
   * records how many items the chunk step will settle in the job execution context.
   */
  @Bean
  public Step prepareStep(
      JobRepository jobRepository,
      PlatformTransactionManager transactionManager,
      JdbcTemplate jdbc) {
    return new StepBuilder(BatchCatalog.TASKLET_STEP, jobRepository)
        .tasklet(
            (contribution, chunkContext) -> {
              long jobExecutionId =
                  chunkContext.getStepContext().getStepExecution().getJobExecutionId();
              int removed =
                  jdbc.update(
                      "DELETE FROM settlement_lines WHERE job_execution_id <> ?", jobExecutionId);
              Long items = jdbc.queryForObject("SELECT COUNT(*) FROM order_items", Long.class);
              chunkContext
                  .getStepContext()
                  .getStepExecution()
                  .getJobExecution()
                  .getExecutionContext()
                  .putLong("expectedItems", items == null ? 0 : items);
              chunkContext
                  .getStepContext()
                  .getStepExecution()
                  .getExecutionContext()
                  .putInt("removedLines", removed);
              return RepeatStatus.FINISHED;
            },
            transactionManager)
        .build();
  }

  /** Chunk step: page reader -> pricing processor -> JDBC batch writer, 5,000 items per commit. */
  @Bean
  public Step settleStep(
      JobRepository jobRepository,
      PlatformTransactionManager transactionManager,
      JdbcPagingItemReader<OrderItemRow> orderItemReader,
      ItemProcessor<OrderItemRow, SettlementLine> settlementProcessor,
      JdbcBatchItemWriter<SettlementLine> settlementWriter) {
    return new StepBuilder(BatchCatalog.CHUNK_STEP, jobRepository)
        .<OrderItemRow, SettlementLine>chunk(BatchCatalog.CHUNK_SIZE, transactionManager)
        .reader(orderItemReader)
        .processor(settlementProcessor)
        .writer(settlementWriter)
        .build();
  }

  /** One reader per step execution (step scope): runs started in parallel do not share state. */
  @Bean
  @StepScope
  public JdbcPagingItemReader<OrderItemRow> orderItemReader(DataSource dataSource) {
    return new JdbcPagingItemReaderBuilder<OrderItemRow>()
        .name("orderItemReader")
        .dataSource(dataSource)
        .selectClause("SELECT id, order_id, product_id, quantity")
        .fromClause("FROM order_items")
        .sortKeys(Map.of("id", Order.ASCENDING))
        .pageSize(BatchCatalog.CHUNK_SIZE)
        .fetchSize(BatchCatalog.CHUNK_SIZE)
        .rowMapper(
            (rs, rowNum) ->
                new OrderItemRow(
                    rs.getLong("id"),
                    rs.getLong("order_id"),
                    rs.getLong("product_id"),
                    rs.getInt("quantity")))
        .build();
  }

  @Bean
  public ItemProcessor<OrderItemRow, SettlementLine> settlementProcessor(ProductPrices prices) {
    return item -> {
      BigDecimal price = prices.priceOf(item.productId());
      return new SettlementLine(
          item.id(), item.orderId(), price.multiply(BigDecimal.valueOf(item.quantity())));
    };
  }

  /** Writes the lines of one chunk with one JDBC batch, tagged with the job execution id. */
  @Bean
  @StepScope
  public JdbcBatchItemWriter<SettlementLine> settlementWriter(
      DataSource dataSource, @Value("#{stepExecution.jobExecutionId}") Long jobExecutionId) {
    return new JdbcBatchItemWriterBuilder<SettlementLine>()
        .dataSource(dataSource)
        .sql(
            "INSERT INTO settlement_lines (job_execution_id, item_id, order_id, amount)"
                + " VALUES (?, ?, ?, ?)")
        .itemPreparedStatementSetter(
            (line, ps) -> {
              ps.setLong(1, jobExecutionId);
              ps.setLong(2, line.itemId());
              ps.setLong(3, line.orderId());
              ps.setBigDecimal(4, line.amount());
            })
        .build();
  }
}
