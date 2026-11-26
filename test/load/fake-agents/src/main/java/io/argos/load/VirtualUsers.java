/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.load;

import io.argos.server.auth.Role;
import io.argos.server.testing.ServerTestEnvironment;
import io.argos.server.testing.auth.ApiResponse;
import io.argos.server.testing.auth.ApiTestClient;
import io.argos.server.testing.auth.TestUsers;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The concurrent dashboard users of N-06 (18 §8): {@code --users} VIEWER accounts (generated names
 * and passwords, bcrypt in PostgreSQL), each signed in through the login API like the UI (CSRF
 * cookie, session cookies), each polling every {@code --user-interval} the three calls of an open
 * dashboard: the dashboard of the last hour, the XLog of the last 5 minutes and the SQL Top N of
 * the last hour (the call set of WO-23's bench, 14 §3). User {@code i} watches app {@code i mod
 * apps}. The latency is the client's wall time of one call (send to body read). A 401 (expired
 * access token) signs the user in again and is not counted.
 */
final class VirtualUsers implements AutoCloseable {

  /** The dashboard of the last hour. */
  static final String DASHBOARD = "dashboard 1 h";

  /** The XLog of the last 5 minutes. */
  static final String XLOG = "xlog 5 min";

  /** The SQL Top N of the last hour. */
  static final String SQL_TOP = "sql top-n 1 h";

  private final List<User> users = new ArrayList<>();
  private final LatencyRecorder recorder;
  private final ScheduledExecutorService scheduler;
  private final List<ScheduledFuture<?>> polls = new ArrayList<>();
  private final Duration interval;

  private static final class User {
    final ApiTestClient client;
    final TestUsers.TestUser account;
    final int appId;
    final AtomicLong failures = new AtomicLong();

    User(ApiTestClient client, TestUsers.TestUser account, int appId) {
      this.client = client;
      this.account = account;
      this.appId = appId;
    }
  }

  VirtualUsers(
      ServerTestEnvironment env,
      String baseUrl,
      List<LoadApp> apps,
      int count,
      Duration interval,
      LatencyRecorder recorder) {
    this.recorder = recorder;
    this.interval = interval;
    this.scheduler =
        Executors.newScheduledThreadPool(Math.max(1, count), Generator.daemonThreads("load-user-"));
    TestUsers accounts = new TestUsers(env);
    for (int i = 0; i < count; i++) {
      TestUsers.TestUser account = accounts.create(Role.VIEWER);
      ApiTestClient client = ApiTestClient.of(baseUrl);
      ApiResponse login = client.login(account.username(), account.password());
      if (login.status() != 200) {
        throw new IllegalStateException("Virtual user login failed: " + login);
      }
      users.add(new User(client, account, apps.get(i % apps.size()).appId()));
    }
  }

  /** Starts polling, users spread over one interval. */
  void start() {
    long n = users.size();
    for (int i = 0; i < users.size(); i++) {
      User u = users.get(i);
      long offset = i * interval.toMillis() / Math.max(1, n);
      polls.add(
          scheduler.scheduleAtFixedRate(
              () -> poll(u), offset, interval.toMillis(), TimeUnit.MILLISECONDS));
    }
  }

  /** Calls that threw (connection failures), all users. */
  long failures() {
    return users.stream().mapToLong(u -> u.failures.get()).sum();
  }

  private void poll(User u) {
    Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
    String app = "/api/v1/apps/" + u.appId;
    String hour = "from=" + now.minus(Duration.ofHours(1)) + "&to=" + now;
    String fiveMinutes = "from=" + now.minus(Duration.ofMinutes(5)) + "&to=" + now;
    call(u, DASHBOARD, app + "/dashboard?" + hour);
    call(u, XLOG, app + "/xlog?" + fiveMinutes + "&kind=ALL&limit=10000");
    call(u, SQL_TOP, app + "/sql/stats?" + hour + "&sort=sum_ms:desc&limit=100");
  }

  private void call(User u, String endpoint, String path) {
    try {
      long started = System.nanoTime();
      ApiResponse r = u.client.get(path);
      if (r.status() == 401) {
        u.client.login(u.account.username(), u.account.password());
        started = System.nanoTime();
        r = u.client.get(path);
      }
      double millis = (System.nanoTime() - started) / 1e6;
      recorder.record(endpoint, millis, r.status() == 200);
    } catch (RuntimeException e) {
      u.failures.incrementAndGet();
      recorder.record(endpoint, Double.NaN, false);
    }
  }

  @Override
  public void close() {
    polls.forEach(p -> p.cancel(false));
    scheduler.shutdownNow();
    try {
      scheduler.awaitTermination(30, TimeUnit.SECONDS);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }
}
