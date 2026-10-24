/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.sample.heavy.aop;

import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.LongAdder;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.stereotype.Component;

/**
 * AspectJ-annotation aspect (Spring AOP proxies) around every public method of the heavy services:
 * call count and total time per method. Bounded: one entry per service method.
 */
@Aspect
@Component
public class ServiceCallStats {

  private final Map<String, LongAdder> calls = new ConcurrentHashMap<>();
  private final Map<String, LongAdder> nanos = new ConcurrentHashMap<>();

  @Around("within(io.argos.sample.heavy.service..*) && execution(public * *(..))")
  public Object count(ProceedingJoinPoint call) throws Throwable {
    String name =
        call.getSignature().getDeclaringType().getSimpleName()
            + "#"
            + call.getSignature().getName();
    long start = System.nanoTime();
    try {
      return call.proceed();
    } finally {
      calls.computeIfAbsent(name, k -> new LongAdder()).increment();
      nanos.computeIfAbsent(name, k -> new LongAdder()).add(System.nanoTime() - start);
    }
  }

  /** Method -> {calls, totalMs}, sorted by method. */
  public Map<String, Object> snapshot() {
    Map<String, Object> result = new TreeMap<>();
    calls.forEach(
        (name, count) ->
            result.put(
                name,
                Map.of(
                    "calls",
                    count.sum(),
                    "totalMs",
                    TimeUnit.NANOSECONDS.toMillis(
                        nanos.getOrDefault(name, new LongAdder()).sum()))));
    return result;
  }
}
