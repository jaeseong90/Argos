/* SPDX-License-Identifier: Apache-2.0 */
package com.example.jobs;

import io.argos.agent.bootstrap.Argos;
import io.argos.agent.bootstrap.DictKind;
import io.argos.agent.bootstrap.StepType;
import io.argos.agent.bootstrap.TraceContext;
import io.argos.agent.bootstrap.Tracer;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobInstance;
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.StepExecution;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.item.ChunkOrientedTasklet;
import org.springframework.batch.core.step.item.SimpleChunkProcessor;
import org.springframework.batch.core.step.item.SimpleChunkProvider;
import org.springframework.batch.core.step.tasklet.TaskletStep;
import org.springframework.batch.item.ItemProcessor;
import org.springframework.batch.item.ItemReader;
import org.springframework.batch.item.ItemWriter;
import org.springframework.batch.repeat.policy.SimpleCompletionPolicy;
import org.springframework.batch.repeat.support.RepeatTemplate;
import org.springframework.batch.support.transaction.ResourcelessTransactionManager;

/**
 * Entry points of the Spring Batch tests with JDK-only signatures: a real chunk-oriented {@code
 * TaskletStep} (reader, processor, writer, commit interval, transaction per chunk) executed through
 * {@code AbstractStep.execute(StepExecution)}, as {@code SimpleJob} does. Only the job repository
 * and the writer are no-op proxies, because their interfaces differ between Spring Batch 4.3 and
 * 5.2 (the test classes are compiled once against 4.3).
 */
public final class BatchFixtures {
  /** The statement of the per-item price lookup (the N+1 shape of shop-batch's settleStep). */
  public static final String PRICE_SQL = "SELECT price FROM product_prices WHERE product_id = ?";

  private BatchFixtures() {}

  /**
   * Executes chunk step {@code jobName.stepName} over {@code items} items, {@code commitInterval}
   * per chunk, on this thread. With {@code perItemSql} the processor records one SQL step per item
   * on the transaction of this thread (what the JDBC module records for a query); {@code failAt}
   * (1-based, -1 never) makes the processor throw at that item. Returns the step's final status.
   */
  public static String runChunkStep(
      String jobName,
      String stepName,
      final int items,
      int commitInterval,
      final boolean perItemSql,
      final int failAt)
      throws Exception {
    TaskletStep step = new TaskletStep(stepName);
    step.setJobRepository(noopRepository());
    step.setTransactionManager(new ResourcelessTransactionManager());
    RepeatTemplate chunk = new RepeatTemplate();
    chunk.setCompletionPolicy(new SimpleCompletionPolicy(commitInterval));
    ItemReader<Integer> reader =
        new ItemReader<Integer>() {
          private int next;

          @Override
          public Integer read() {
            return next < items ? Integer.valueOf(++next) : null;
          }
        };
    ItemProcessor<Integer, Integer> processor =
        new ItemProcessor<Integer, Integer>() {
          @Override
          public Integer process(Integer item) {
            if (item.intValue() == failAt) {
              throw new IllegalArgumentException("item " + item + " has no price");
            }
            if (perItemSql) {
              priceLookup();
            }
            return item;
          }
        };
    step.setTasklet(
        new ChunkOrientedTasklet<Integer>(
            new SimpleChunkProvider<Integer>(reader, chunk),
            new SimpleChunkProcessor<Integer, Integer>(processor, noopWriter())));
    JobExecution job = new JobExecution(new JobInstance(1L, jobName), new JobParameters());
    StepExecution execution = job.createStepExecution(stepName);
    step.execute(execution);
    return execution.getStatus().name();
  }

  /** One SQL step on the current transaction, as the JDBC module records a query. */
  static void priceLookup() {
    Tracer tracer = Argos.tracer();
    TraceContext ctx = tracer.current();
    if (ctx == null) {
      tracer.countOrphan(StepType.SQL);
      return;
    }
    int seq =
        tracer.stepStart(
            ctx, StepType.SQL, tracer.dict(DictKind.SQL, PRICE_SQL), 0L, System.nanoTime());
    tracer.stepEnd(ctx, seq, 0, false, 1L, System.nanoTime());
  }

  @SuppressWarnings("unchecked")
  private static ItemWriter<Integer> noopWriter() {
    return (ItemWriter<Integer>)
        Proxy.newProxyInstance(
            BatchFixtures.class.getClassLoader(), new Class<?>[] {ItemWriter.class}, NOOP);
  }

  private static JobRepository noopRepository() {
    return (JobRepository)
        Proxy.newProxyInstance(
            BatchFixtures.class.getClassLoader(), new Class<?>[] {JobRepository.class}, NOOP);
  }

  /** Returns the default value of each method's return type ({@code "noop"} for toString). */
  private static final InvocationHandler NOOP =
      new InvocationHandler() {
        @Override
        public Object invoke(Object proxy, Method method, Object[] args) {
          Class<?> type = method.getReturnType();
          if (type == String.class) {
            return "noop";
          }
          if (type == boolean.class) {
            return false;
          }
          if (type == int.class) {
            return Integer.valueOf(0);
          }
          if (type == long.class) {
            return Long.valueOf(0L);
          }
          return null;
        }
      };
}
