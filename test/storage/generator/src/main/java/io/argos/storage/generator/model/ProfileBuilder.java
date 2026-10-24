/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.storage.generator.model;

import io.argos.storage.generator.model.Dictionary.Kind;
import io.argos.storage.generator.model.Topology.App;
import io.argos.storage.generator.model.Transaction.ExtCall;
import io.argos.storage.generator.model.Transaction.SqlRun;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.SplittableRandom;

/**
 * Builds the profile steps of a captured transaction as the agent's StepBuffer would send them
 * (docs/10 §9.1-§9.2, 13 §3.2): controller and service METHOD steps, custom METHOD steps, the DBC
 * wait, one SQL step per run of consecutive executions (repeat_count, DSN-13-20), COMMIT or
 * ROLLBACK, one HTTP step per outgoing call, rare ASYNC and MESSAGE steps, an ERROR step with
 * frames, STACK captures of slow transactions (from 1 s, every 3 s, at most 5, up to 64 frames),
 * and summary mode beyond 1,000 steps (SUMMARY steps per type and name, xlog flag 4).
 */
public final class ProfileBuilder {

  /** sampling.profile.max-steps (docs/10). */
  public static final int MAX_STEPS = 1000;

  /** STACK capture interval and maximum (docs/10 §10). */
  static final int STACK_INTERVAL_MS = 3000;

  static final int MAX_STACK_CAPTURES = 5;

  private static final String[] BIND_TYPES = {"Long", "Int", "String", "Timestamp", "Decimal"};

  private final int slowThresholdMs;

  public ProfileBuilder(int slowThresholdMs) {
    this.slowThresholdMs = slowThresholdMs;
  }

  /** The steps of {@code tx} (captured). Sets {@link Transaction#FLAG_SUMMARIZED} if needed. */
  public List<ProfileStep> build(Transaction tx, SplittableRandom random) {
    App app = tx.instance.app;
    int e = tx.elapsedMs;
    List<ProfileStep> top = new ArrayList<>();
    ProfileStep controller =
        new ProfileStep(tx, ProfileStep.METHOD, 0, e, tx.service.controllerMethodId, 0);
    ProfileStep service =
        new ProfileStep(
            tx,
            ProfileStep.METHOD,
            Math.min(1, e),
            Math.max(0, e - 2),
            tx.service.serviceMethodId,
            0);
    top.add(controller);
    top.add(service);

    List<ProfileStep> body = new ArrayList<>();
    int methods = tx.kind == Transaction.BATCH ? 3 + random.nextInt(6) : 3 + random.nextInt(9);
    for (int m = 0; m < methods; m++) {
      double u = random.nextDouble();
      int method = (int) (Topology.METHODS_PER_APP * u * u);
      int start = tx.preMs + (e > tx.preMs ? random.nextInt(e - tx.preMs) : 0);
      int elapsed = (int) Math.min(e - start, Math.round(e * 0.05 * random.nextDouble()));
      body.add(new ProfileStep(tx, ProfileStep.METHOD, start, elapsed, app.methodIds[method], 0));
    }
    boolean write = false;
    long writeDb = 0;
    int sqlEnd = 0;
    if (tx.dbcTimeMs > 0 && !tx.sqlRuns.isEmpty()) {
      Topology.DbTarget db = tx.sqlRuns.get(0).sql.db;
      body.add(
          new ProfileStep(
              tx, ProfileStep.DBC, tx.dbcOffsetMs, tx.dbcTimeMs, db.poolNameId(), db.poolId()));
    }
    for (SqlRun run : tx.sqlRuns) {
      ProfileStep s =
          new ProfileStep(
              tx, ProfileStep.SQL, run.startOffsetMs, run.totalMs, run.sql.id, run.sql.db.id());
      s.execMs = run.execMs;
      s.fetchMs = run.fetchMs;
      s.rows = run.rows;
      s.repeatCount = run.count;
      s.error = run.error;
      s.batchSize = run.batchSize;
      s.detail = binds(run, random);
      body.add(s);
      if (run.sql.write) {
        write = true;
        writeDb = run.sql.db.id();
      }
      sqlEnd = Math.max(sqlEnd, run.startOffsetMs + run.totalMs);
    }
    if (write) {
      int type = tx.error ? ProfileStep.ROLLBACK : ProfileStep.COMMIT;
      body.add(new ProfileStep(tx, type, Math.min(e, sqlEnd), random.nextInt(3), 0, writeDb));
    }
    for (ExtCall call : tx.extCalls) {
      ProfileStep s =
          new ProfileStep(
              tx,
              ProfileStep.HTTP,
              call.startOffsetMs,
              call.durationMs,
              call.host.pathIds[call.pathIndex],
              call.host.id);
      s.rows = call.responseBytes;
      s.status = call.status;
      s.error = call.error || call.timeout || call.connectFail;
      s.detail = "POST /v1/op" + call.pathIndex + "/{id}";
      body.add(s);
    }
    if (tx.kind == Transaction.HTTP && random.nextDouble() < 0.02) {
      int start = tx.preMs;
      body.add(
          new ProfileStep(
              tx,
              ProfileStep.ASYNC,
              start,
              Math.max(0, (e - start) / 3),
              app.methodIds[random.nextInt(Topology.METHODS_PER_APP)],
              0));
    }
    if (random.nextDouble() < 0.01) {
      int start = Math.max(0, e - 5);
      body.add(
          new ProfileStep(
              tx,
              ProfileStep.MESSAGE,
              start,
              Math.min(e - start, 5),
              Dictionary.key(Kind.METHOD, app.id, "topic-" + random.nextInt(8)),
              0));
    }
    if (tx.error) {
      ProfileStep s =
          new ProfileStep(tx, ProfileStep.ERROR, Math.max(0, e - 1), 0, tx.errorGroup.classId(), 0);
      s.error = true;
      s.detail = tx.errorGroup.message();
      s.stack = frames(tx, 20 + random.nextInt(21), random);
      body.add(s);
    }
    if (e >= slowThresholdMs) {
      int captures = Math.min(MAX_STACK_CAPTURES, 1 + (e - slowThresholdMs) / STACK_INTERVAL_MS);
      for (int c = 0; c < captures; c++) {
        ProfileStep s =
            new ProfileStep(
                tx, ProfileStep.STACK, slowThresholdMs + c * STACK_INTERVAL_MS, 0, 0, 0);
        s.stack = frames(tx, 30 + random.nextInt(35), random);
        body.add(s);
      }
    }
    body.sort(Comparator.comparingInt(s -> s.startOffsetMs));

    List<ProfileStep> steps = new ArrayList<>(top);
    int room = MAX_STEPS - top.size();
    List<ProfileStep> kept = body.size() <= room ? body : body.subList(0, room);
    for (ProfileStep s : kept) {
      s.parentSeq = s.type == ProfileStep.ERROR ? 1 : s.type == ProfileStep.STACK ? 0 : 2;
    }
    steps.addAll(kept);
    if (body.size() > room) {
      steps.addAll(summarize(tx, body.subList(room, body.size())));
      tx.flags |= Transaction.FLAG_SUMMARIZED;
    }
    controller.parentSeq = 0;
    service.parentSeq = 1;
    for (int i = 0; i < steps.size(); i++) {
      steps.get(i).seq = i + 1;
    }
    return steps;
  }

  /** Summary mode: one SUMMARY step per (type, name, target) with the count and total time. */
  private static List<ProfileStep> summarize(Transaction tx, List<ProfileStep> rest) {
    Map<List<Long>, ProfileStep> groups = new LinkedHashMap<>();
    for (ProfileStep s : rest) {
      List<Long> key = List.of((long) s.type, s.nameId, s.targetId);
      ProfileStep g = groups.get(key);
      if (g == null) {
        g = new ProfileStep(tx, ProfileStep.SUMMARY, s.startOffsetMs, 0, s.nameId, s.targetId);
        g.repeatCount = 0;
        g.parentSeq = 2;
        groups.put(key, g);
      }
      g.elapsedMs += s.elapsedMs;
      g.execMs += s.execMs;
      g.fetchMs += s.fetchMs;
      g.rows += s.rows;
      g.repeatCount += s.repeatCount;
      g.error |= s.error;
    }
    return new ArrayList<>(groups.values());
  }

  /** Masked bind parameters: types and string lengths only (SEC-3A masked mode). */
  private static String binds(SqlRun run, SplittableRandom random) {
    StringBuilder sb = new StringBuilder("[");
    for (int p = 0; p < run.sql.params; p++) {
      if (p > 0) {
        sb.append(", ");
      }
      String type =
          BIND_TYPES[(int) Math.floorMod(Hash.of(run.sql.id, p), (long) BIND_TYPES.length)];
      sb.append(type);
      if (type.equals("String")) {
        sb.append('(').append(1 + random.nextInt(40)).append(')');
      }
    }
    return sb.append(']').toString();
  }

  /**
   * A stack (innermost frame first): service-specific application frames with some variation at the
   * top, then the framework frames shared by the app.
   */
  private static long[] frames(Transaction tx, int depth, SplittableRandom random) {
    App app = tx.instance.app;
    int base = Math.min(Topology.BASE_FRAMES, depth);
    int own = depth - base;
    long[] frames = new long[depth];
    int variant = random.nextInt(4);
    for (int j = 0; j < own; j++) {
      long h = Hash.of(tx.service.id, j, j < 3 ? variant : 0);
      frames[j] = app.frameIds[(int) Math.floorMod(h, (long) Topology.FRAMES_PER_APP)];
    }
    System.arraycopy(app.baseFrames, Topology.BASE_FRAMES - base, frames, own, base);
    return frames;
  }
}
