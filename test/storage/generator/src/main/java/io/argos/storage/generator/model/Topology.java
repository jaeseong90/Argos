/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.storage.generator.model;

import io.argos.storage.generator.GeneratorConfig;
import io.argos.storage.generator.RunRange;
import io.argos.storage.generator.model.Dictionary.Kind;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.SplittableRandom;

/**
 * The monitored estate of docs/13 §15, built deterministically from the seed: apps (prod/stg),
 * instances with clock skew and a rolling restart at the version switch, HTTP services with global
 * Zipf popularity, one batch job per app, SQL statements and databases, outgoing call hosts
 * (internal hosts of other apps and external hosts), error groups, versions, users and client
 * addresses.
 */
public final class Topology {

  /** Instances without CPU time / allocation measurement (D-23, -1 values). */
  public static final int CPU_UNMEASURED_INSTANCES = 2;

  /** Apps running on virtual threads (xlog.flags bit 1). */
  public static final int VIRTUAL_THREAD_APPS = 2;

  /** Methods (custom instrumentation names) per app. */
  public static final int METHODS_PER_APP = 300;

  /** Stack frames per app (FRAME dictionary). */
  public static final int FRAMES_PER_APP = 4000;

  /** Framework frames at the bottom of every stack of an app. */
  public static final int BASE_FRAMES = 25;

  /** SQL preferred by one service (its usual statements, in order). */
  public static final int SQL_PREFERENCE = 8;

  /** Extra Zipf exponent of the statements a service prefers (shared hot statements). */
  public static final double PREFERENCE_ZIPF_EXTRA = 0.5;

  /** External hosts used by one app. */
  public static final int EXTERNAL_HOSTS_PER_APP = 3;

  /** Exception classes of the generated errors (generic JDK and framework classes). */
  static final List<String> ERROR_CLASSES =
      List.of(
          "java.sql.SQLTransientConnectionException",
          "java.sql.SQLIntegrityConstraintViolationException",
          "java.net.SocketTimeoutException",
          "java.net.ConnectException",
          "java.lang.IllegalStateException",
          "java.lang.IllegalArgumentException",
          "java.lang.NullPointerException",
          "java.util.NoSuchElementException",
          "java.util.concurrent.TimeoutException",
          "java.io.UncheckedIOException",
          "org.springframework.dao.DataIntegrityViolationException",
          "org.springframework.web.client.HttpServerErrorException");

  /** Cause of an error group: which part of the transaction failed. */
  public enum Cause {
    SQL,
    EXT,
    APP
  }

  /** An application (PostgreSQL apps row: name and env). */
  public static final class App {
    public final int id;
    public final int index;
    public final String name;
    public final String env;
    public final boolean virtualThreads;
    public final int apdexTMs;
    public final long userSalt;
    public final long threadHttpId;
    public final long threadBatchId;
    public final long[] versionIds;
    public final List<Instance> instances = new ArrayList<>();
    public final List<Service> httpServices = new ArrayList<>();
    public final List<SqlStatement> sqls = new ArrayList<>();
    public final List<DbTarget> dbs = new ArrayList<>();
    public final List<ExtHost> externalHosts = new ArrayList<>();
    public final List<ErrorGroup> errorGroups = new ArrayList<>();
    public final long[] methodIds = new long[METHODS_PER_APP];
    public final long[] frameIds = new long[FRAMES_PER_APP];
    public final long[] baseFrames = new long[BASE_FRAMES];
    public Service batchService;
    public Weighted httpServiceWeights;
    public double httpShare;
    public Weighted sqlWeights;
    public Weighted preferenceWeights;
    public Weighted externalHostWeights;
    public Weighted errorGroupWeights;
    public App callee;
    public ExtHost calleeHost;
    public ExtHost ownHost;

    App(
        int id,
        int index,
        String name,
        String env,
        boolean virtualThreads,
        int apdexTMs,
        long salt,
        long threadHttpId,
        long threadBatchId,
        long[] versionIds) {
      this.id = id;
      this.index = index;
      this.name = name;
      this.env = env;
      this.virtualThreads = virtualThreads;
      this.apdexTMs = apdexTMs;
      this.userSalt = salt;
      this.threadHttpId = threadHttpId;
      this.threadBatchId = threadBatchId;
      this.versionIds = versionIds;
    }
  }

  /** An agent instance (PostgreSQL instances.id). */
  public static final class Instance {
    public final int id;
    public final App app;
    public final int indexInApp;
    public final long skewMs;
    public final boolean skewed;
    public final boolean cpuMeasured;
    public final boolean batchRunner;
    public final long restartAt;
    public final long restartEnd;
    public final int versionAfter;
    public final int internalIpv4;

    /**
     * Indexes into {@code app.sqls} of the statements this instance runs ({@code active-sqls}, N-06
     * load), or null: the services' usual statements (13 §15).
     */
    public int[] activeSqls;

    Instance(
        int id,
        App app,
        int indexInApp,
        long skewMs,
        boolean skewed,
        boolean cpuMeasured,
        long restartAt,
        long restartEnd,
        int versionAfter) {
      this.id = id;
      this.app = app;
      this.indexInApp = indexInApp;
      this.skewMs = skewMs;
      this.skewed = skewed;
      this.cpuMeasured = cpuMeasured;
      this.batchRunner = indexInApp == 0;
      this.restartAt = restartAt;
      this.restartEnd = restartEnd;
      this.versionAfter = versionAfter;
      this.internalIpv4 = (10 << 24) | ((app.index & 0xFF) << 16) | ((indexInApp & 0xFF) << 8);
    }

    /** False during the restart of the rolling deploy. */
    public boolean upAt(long trueMs) {
      return trueMs < restartAt || trueMs >= restartEnd;
    }

    /** Version dictionary key running at a true instant. */
    public long versionIdAt(long trueMs) {
      return trueMs < restartEnd ? app.versionIds[0] : app.versionIds[versionAfter];
    }

    /** Agent clock of a true instant. */
    public long agentClock(long trueMs) {
      return trueMs + skewMs;
    }
  }

  /** A service (URL pattern or batch job). */
  public static final class Service {
    public final long id;
    public final App app;
    public final int rank;
    public final String name;
    public final boolean batch;
    public final String httpMethod;
    public final int apdexTMs;
    public final int[] sqlPreference;
    public final double globalWeight;
    public final long controllerMethodId;
    public final long serviceMethodId;
    public double logShift;

    Service(
        long id,
        App app,
        int rank,
        String name,
        boolean batch,
        String httpMethod,
        int apdexTMs,
        int[] sqlPreference,
        double globalWeight,
        long controllerMethodId,
        long serviceMethodId) {
      this.id = id;
      this.app = app;
      this.rank = rank;
      this.name = name;
      this.batch = batch;
      this.httpMethod = httpMethod;
      this.apdexTMs = apdexTMs;
      this.sqlPreference = sqlPreference;
      this.globalWeight = globalWeight;
      this.controllerMethodId = controllerMethodId;
      this.serviceMethodId = serviceMethodId;
    }
  }

  /** A normalized SQL statement. */
  public static final class SqlStatement {
    public final long id;
    public final int index;
    public final DbTarget db;
    public final boolean write;
    public final double baseMs;
    public final double meanRows;
    public final int params;
    public final String text;

    SqlStatement(
        long id,
        int index,
        DbTarget db,
        boolean write,
        double baseMs,
        double meanRows,
        int params,
        String text) {
      this.id = id;
      this.index = index;
      this.db = db;
      this.write = write;
      this.baseMs = baseMs;
      this.meanRows = meanRows;
      this.params = params;
      this.text = text;
    }
  }

  /**
   * A JDBC target and its pool.
   *
   * @param id db_id (DB_TARGET key of the JDBC URL)
   * @param poolId pool key (DBC step target_id)
   * @param poolNameId pool name key (DBC step name_id)
   * @param url JDBC URL text
   */
  public record DbTarget(long id, long poolId, long poolNameId, String url) {}

  /** An outgoing call host ({@code scheme://host:port}). */
  public static final class ExtHost {
    public final long id;
    public final String url;
    public final boolean internal;
    public final App target;
    public final double medianMs;
    public final long[] pathIds;

    ExtHost(long id, String url, boolean internal, App target, double medianMs, long[] pathIds) {
      this.id = id;
      this.url = url;
      this.internal = internal;
      this.target = target;
      this.medianMs = medianMs;
      this.pathIds = pathIds;
    }
  }

  /**
   * An error group (fingerprint, F-S19).
   *
   * @param id error_group_id
   * @param app owning app
   * @param classId error_class_id
   * @param className exception class
   * @param cause failing part
   * @param message masked message pattern
   */
  public record ErrorGroup(
      long id, App app, long classId, String className, Cause cause, String message) {}

  public final GeneratorConfig config;
  public final RunRange range;
  public final Dictionary dictionary;
  public final List<App> apps;
  public final List<Instance> instances;
  public final List<Service> httpServices;
  public final List<ExtHost> hosts;
  public final Weighted userWeights;
  public final int[] remoteIps;
  public final Weighted remoteIpWeights;

  /** Per-transaction log-normal sigma left after the per-service shifts (total sigma kept). */
  public final double noiseSigma;

  private Topology(
      GeneratorConfig config,
      RunRange range,
      Dictionary dictionary,
      List<App> apps,
      List<Instance> instances,
      List<Service> httpServices,
      List<ExtHost> hosts,
      Weighted userWeights,
      int[] remoteIps,
      Weighted remoteIpWeights,
      double noiseSigma) {
    this.config = config;
    this.range = range;
    this.dictionary = dictionary;
    this.apps = apps;
    this.instances = instances;
    this.httpServices = httpServices;
    this.hosts = hosts;
    this.userWeights = userWeights;
    this.remoteIps = remoteIps;
    this.remoteIpWeights = remoteIpWeights;
    this.noiseSigma = noiseSigma;
  }

  /** Builds the topology of a run. */
  public static Topology build(GeneratorConfig config, RunRange range) {
    SplittableRandom random = new SplittableRandom(Hash.of(config.seed, 0x70706F6CL));
    Dictionary dict = new Dictionary(true);
    List<App> apps = buildApps(config, dict, random);
    List<Instance> instances = buildInstances(config, range, apps);
    List<ExtHost> hosts = buildHosts(config, dict, apps, random);
    List<Service> services = buildServices(config, dict, apps, random);
    for (App app : apps) {
      buildSqls(config, dict, app, random);
      buildErrorGroups(config, dict, app, random);
      buildMethodsAndFrames(app);
    }
    for (Service s : services) {
      pickSqlPreference(s, random);
    }
    for (App app : apps) {
      pickSqlPreference(app.batchService, random);
    }
    if (config.activeSqls > 0) {
      for (Instance instance : instances) {
        pickActiveSqls(config, instance);
      }
    }
    double noiseSigma = normalizeLatencyShifts(config, services, random);
    Weighted users = Weighted.zipf(config.users, 1.0);
    int[] ips = buildRemoteIps(config, random);
    Weighted ipWeights = Weighted.zipf(ips.length, 1.0);
    return new Topology(
        config,
        range,
        dict,
        List.copyOf(apps),
        List.copyOf(instances),
        List.copyOf(services),
        List.copyOf(hosts),
        users,
        ips,
        ipWeights,
        noiseSigma);
  }

  /** Total HTTP root share weights: app shares sum to 1. */
  private static List<App> buildApps(
      GeneratorConfig config, Dictionary dict, SplittableRandom random) {
    List<App> apps = new ArrayList<>();
    Set<Integer> virtual =
        lowestHashes(
            config.seed,
            0x76747A,
            config.apps,
            Math.min(VIRTUAL_THREAD_APPS, config.apps),
            Set.of());
    for (int i = 0; i < config.apps; i++) {
      int id = i + 1;
      String name = String.format(Locale.ROOT, "app-%02d", id);
      String env = i < config.prodApps ? "prod" : "stg";
      boolean vt = virtual.contains(i);
      long[] versions = new long[config.versions];
      for (int v = 0; v < config.versions; v++) {
        versions[v] = dict.id(Kind.APP_VERSION, id, String.format(Locale.ROOT, "1.%d.%d", v, i));
      }
      long threadHttp = dict.id(Kind.THREAD, id, vt ? "virtual-" : "http-nio-8080-exec-");
      long threadBatch = dict.id(Kind.THREAD, id, "scheduling-");
      long salt = random.nextLong();
      apps.add(new App(id, i, name, env, vt, 500, salt, threadHttp, threadBatch, versions));
    }
    // Internal calls: a ring within each env group (prod apps call prod apps).
    for (String env : List.of("prod", "stg")) {
      List<App> group = apps.stream().filter(a -> a.env.equals(env)).toList();
      if (group.size() > 1) {
        for (int i = 0; i < group.size(); i++) {
          group.get(i).callee = group.get((i + 1) % group.size());
        }
      }
    }
    return apps;
  }

  private static List<Instance> buildInstances(
      GeneratorConfig config, RunRange range, List<App> apps) {
    int total = config.instances();
    // Skewed and unmeasured instances are never the batch runner (index 0 within the app).
    Set<Integer> runners = new HashSet<>();
    for (int i = 0; i < total; i++) {
      if (i % config.instancesPerApp == 0) {
        runners.add(i);
      }
    }
    Set<Integer> skewed =
        lowestHashes(
            config.seed,
            0x736B6577,
            total,
            Math.min(config.skewedInstances, total - runners.size()),
            runners);
    Set<Integer> excluded = new HashSet<>(runners);
    excluded.addAll(skewed);
    Set<Integer> unmeasured =
        lowestHashes(
            config.seed,
            0x637075,
            total,
            Math.min(CPU_UNMEASURED_INSTANCES, Math.max(0, total - excluded.size())),
            excluded);
    List<Instance> instances = new ArrayList<>();
    boolean deploy = config.versions >= 2 && range.deployAt() < range.end();
    for (App app : apps) {
      for (int j = 0; j < config.instancesPerApp; j++) {
        int index = app.index * config.instancesPerApp + j;
        long h = Hash.of(config.seed, 0x736B6577L, index);
        long jitter =
            config.clockSkewMs == 0
                ? 0
                : Math.floorMod(h, 2 * config.clockSkewMs + 1) - config.clockSkewMs;
        boolean isSkewed = skewed.contains(index);
        long skew = isSkewed ? config.skewedOffsetMs + jitter : jitter;
        long restartAt = deploy ? range.deployAt() + j * range.restartGapMs() : Long.MAX_VALUE;
        long restartEnd = deploy ? restartAt + range.restartDurationMs() : Long.MAX_VALUE;
        boolean canary =
            config.versions >= 3 && j == config.instancesPerApp - 1 && config.instancesPerApp > 1;
        int versionAfter = config.versions >= 2 ? (canary ? 2 : 1) : 0;
        Instance instance =
            new Instance(
                index + 1,
                app,
                j,
                skew,
                isSkewed,
                !unmeasured.contains(index),
                restartAt,
                restartEnd,
                versionAfter);
        app.instances.add(instance);
        instances.add(instance);
      }
    }
    return instances;
  }

  private static List<ExtHost> buildHosts(
      GeneratorConfig config, Dictionary dict, List<App> apps, SplittableRandom random) {
    List<ExtHost> hosts = new ArrayList<>();
    boolean internal = config.extHosts > config.apps;
    if (internal) {
      for (App app : apps) {
        String url = "http://" + app.name + ":8080";
        app.ownHost = host(dict, app.id, url, true, app, 15 + random.nextDouble() * 25);
        hosts.add(app.ownHost);
      }
      for (App app : apps) {
        if (app.callee != null) {
          app.calleeHost = app.callee.ownHost;
        }
      }
    }
    int external = internal ? config.extHosts - config.apps : config.extHosts;
    List<ExtHost> ext = new ArrayList<>();
    for (int i = 0; i < external; i++) {
      String url = String.format(Locale.ROOT, "https://api-%02d.partner.example:443", i + 1);
      ExtHost h = host(dict, 0, url, false, null, 20 + random.nextDouble() * 130);
      ext.add(h);
      hosts.add(h);
    }
    if (!ext.isEmpty()) {
      Weighted popularity = Weighted.zipf(ext.size(), 1.0);
      for (App app : apps) {
        Set<Integer> chosen = new LinkedHashSet<>();
        int want = Math.min(EXTERNAL_HOSTS_PER_APP, ext.size());
        for (int tries = 0; chosen.size() < want && tries < 1000; tries++) {
          chosen.add(popularity.sample(random));
        }
        double[] weights = new double[chosen.size()];
        int k = 0;
        for (int idx : chosen) {
          app.externalHosts.add(ext.get(idx));
          weights[k] = 1.0 / (k + 1);
          k++;
        }
        app.externalHostWeights = Weighted.of(weights);
      }
    }
    return hosts;
  }

  private static ExtHost host(
      Dictionary dict, int appId, String url, boolean internal, App target, double medianMs) {
    long id = dict.id(Kind.HOST, appId, url);
    long[] paths = new long[5];
    for (int p = 0; p < paths.length; p++) {
      paths[p] = dict.id(Kind.SERVICE, appId, "POST " + url + "/v1/op" + p + "/{id}");
    }
    return new ExtHost(id, url, internal, target, medianMs, paths);
  }

  private static List<Service> buildServices(
      GeneratorConfig config, Dictionary dict, List<App> apps, SplittableRandom random) {
    Weighted zipf = Weighted.zipf(config.services, config.serviceZipf);
    List<Service> services = new ArrayList<>();
    double[] appWeight = new double[apps.size()];
    for (int r = 1; r <= config.services; r++) {
      App app = apps.get((r - 1) % apps.size());
      double u = random.nextDouble();
      String method = u < 0.65 ? "GET" : u < 0.90 ? "POST" : u < 0.96 ? "PUT" : "DELETE";
      String name = method + " /api/v1/r" + r + (method.equals("POST") ? "/items" : "/items/{id}");
      long id = dict.id(Kind.SERVICE, app.id, name);
      double a = random.nextDouble();
      int apdex = a < 0.8 ? app.apdexTMs : a < 0.9 ? 200 : a < 0.97 ? 1000 : 2000;
      double weight = zipf.probability(r - 1);
      long controller = dict.id(Kind.METHOD, app.id, "web.R" + r + "Controller.handle");
      long serviceMethod = dict.id(Kind.METHOD, app.id, "service.R" + r + "Service.execute");
      Service s =
          new Service(
              id,
              app,
              r,
              name,
              false,
              method,
              apdex,
              new int[SQL_PREFERENCE],
              weight,
              controller,
              serviceMethod);
      services.add(s);
      app.httpServices.add(s);
      appWeight[app.index] += weight;
    }
    double total = 0;
    for (double w : appWeight) {
      total += w;
    }
    for (App app : apps) {
      app.httpShare = total > 0 ? appWeight[app.index] / total : 0;
      if (app.httpServices.isEmpty()) {
        app.httpServiceWeights = null;
      } else {
        double[] w = new double[app.httpServices.size()];
        for (int i = 0; i < w.length; i++) {
          w[i] = app.httpServices.get(i).globalWeight;
        }
        app.httpServiceWeights = Weighted.of(w);
      }
      String batchName = String.format(Locale.ROOT, "BATCH job-%02d.step", app.id);
      app.batchService =
          new Service(
              dict.id(Kind.SERVICE, app.id, batchName),
              app,
              0,
              batchName,
              true,
              "",
              app.apdexTMs,
              new int[SQL_PREFERENCE],
              0,
              dict.id(Kind.METHOD, app.id, "batch.Job" + app.id + ".run"),
              dict.id(Kind.METHOD, app.id, "batch.Job" + app.id + "Writer.write"));
    }
    return services;
  }

  private static void buildSqls(
      GeneratorConfig config, Dictionary dict, App app, SplittableRandom random) {
    String main = "jdbc:postgresql://db-" + app.name + ":5432/main";
    String report = "jdbc:postgresql://db-" + app.name + "-ro:5432/report";
    for (String url : List.of(main, report)) {
      long dbId = dict.id(Kind.DB_TARGET, app.id, url);
      String pool = url.equals(main) ? "HikariPool-1" : "HikariPool-2";
      app.dbs.add(
          new DbTarget(
              dbId,
              dict.id(Kind.DB_TARGET, app.id, "pool:" + pool),
              dict.id(Kind.METHOD, app.id, pool + ".getConnection"),
              url));
    }
    int perApp =
        Math.max(1, config.sqls / config.apps + (app.index < config.sqls % config.apps ? 1 : 0));
    for (int k = 0; k < perApp; k++) {
      double u = random.nextDouble();
      boolean write = u >= 0.70;
      String verb = u < 0.70 ? "SELECT" : u < 0.85 ? "INSERT" : u < 0.95 ? "UPDATE" : "DELETE";
      String table = "t" + app.id + "_" + (k % 60);
      int params = 1 + random.nextInt(write ? 8 : 4);
      String text =
          switch (verb) {
            case "SELECT" ->
                "SELECT c0, c1, c2 FROM "
                    + table
                    + " WHERE k"
                    + k
                    + " = ?"
                    + (params > 1 ? " AND c3 IN (?)" : "")
                    + " ORDER BY c0 LIMIT ?";
            case "INSERT" -> "INSERT INTO " + table + " (c0, c1, c2) VALUES (?, ?, ?) -- " + k;
            case "UPDATE" -> "UPDATE " + table + " SET c1 = ?, c2 = ? WHERE k" + k + " = ?";
            default -> "DELETE FROM " + table + " WHERE k" + k + " = ?";
          };
      DbTarget db = app.dbs.get(random.nextDouble() < 0.8 || write ? 0 : 1);
      double baseMs = Math.exp(Math.log(3) + 0.8 * random.nextGaussian());
      double meanRows = write ? 1 : Math.exp(Math.log(8) + 1.2 * random.nextGaussian());
      app.sqls.add(
          new SqlStatement(
              dict.id(Kind.SQL, app.id, text), k, db, write, baseMs, meanRows, params, text));
    }
    app.sqlWeights = Weighted.zipf(app.sqls.size(), config.sqlZipf);
    // Services share the hot statements of the app (lookups, auth): sharper than the overall
    // popularity, which the rare statements of the transactions make long-tailed.
    app.preferenceWeights = Weighted.zipf(app.sqls.size(), config.sqlZipf + PREFERENCE_ZIPF_EXTRA);
  }

  private static void buildErrorGroups(
      GeneratorConfig config, Dictionary dict, App app, SplittableRandom random) {
    int perApp =
        Math.max(
            1,
            config.errorGroups / config.apps
                + (app.index < config.errorGroups % config.apps ? 1 : 0));
    for (int g = 0; g < perApp; g++) {
      double u = random.nextDouble();
      Cause cause = u < 0.3 ? Cause.SQL : u < 0.5 ? Cause.EXT : Cause.APP;
      String className =
          switch (cause) {
            case SQL -> ERROR_CLASSES.get(random.nextInt(2));
            case EXT -> ERROR_CLASSES.get(2 + random.nextInt(2));
            case APP -> ERROR_CLASSES.get(4 + random.nextInt(ERROR_CLASSES.size() - 4));
          };
      String message =
          switch (cause) {
            case SQL -> "Connection is not available, request timed out after #ms";
            case EXT -> "Read timed out after #ms calling ?";
            case APP -> "Unexpected state '?' in step #";
          };
      String pattern = className + "\n" + message + "\nframe" + g;
      long id = dict.id(Kind.ERROR_GROUP, app.id, pattern);
      long classId = dict.id(Kind.CLASS, app.id, className);
      app.errorGroups.add(new ErrorGroup(id, app, classId, className, cause, message));
    }
    app.errorGroupWeights = Weighted.zipf(app.errorGroups.size(), 1.2);
  }

  private static void buildMethodsAndFrames(App app) {
    // Not recorded in the dictionary output (large); keys are still dictionary keys.
    for (int m = 0; m < METHODS_PER_APP; m++) {
      app.methodIds[m] = Dictionary.key(Kind.METHOD, app.id, "app.Component" + m + ".call");
    }
    for (int f = 0; f < FRAMES_PER_APP; f++) {
      app.frameIds[f] =
          Dictionary.key(
              Kind.FRAME,
              app.id,
              "app.pkg"
                  + (f % 40)
                  + ".Type"
                  + f
                  + ".method(Type"
                  + f
                  + ".java:"
                  + (10 + f % 400)
                  + ")");
    }
    for (int f = 0; f < BASE_FRAMES; f++) {
      app.baseFrames[f] = Dictionary.key(Kind.FRAME, app.id, "framework.Layer" + f + ".invoke");
    }
  }

  private static void pickSqlPreference(Service service, SplittableRandom random) {
    App app = service.app;
    Set<Integer> chosen = new LinkedHashSet<>();
    int want = Math.min(SQL_PREFERENCE, app.sqls.size());
    for (int tries = 0; chosen.size() < want && tries < 10_000; tries++) {
      chosen.add(app.preferenceWeights.sample(random));
    }
    int k = 0;
    for (int idx : chosen) {
      service.sqlPreference[k++] = idx;
    }
    for (; k < service.sqlPreference.length; k++) {
      service.sqlPreference[k] = service.sqlPreference[k % Math.max(1, chosen.size())];
    }
  }

  /**
   * The active statements of an instance (N-06 load, 13 §14 "active SQL 200"): {@code active-sqls}
   * distinct statements of its app drawn by popularity (Zipf) with a per-instance stream, or every
   * statement of the app when it has no more. Uses its own random stream so that the rest of the
   * topology is the same with and without the option.
   */
  private static void pickActiveSqls(GeneratorConfig config, Instance instance) {
    App app = instance.app;
    int want = Math.min(config.activeSqls, app.sqls.size());
    int[] active = new int[want];
    if (want == app.sqls.size()) {
      for (int k = 0; k < want; k++) {
        active[k] = k;
      }
    } else {
      SplittableRandom random =
          new SplittableRandom(Hash.of(config.seed, 0x61637476L, instance.id));
      Set<Integer> chosen = new LinkedHashSet<>();
      for (int tries = 0; chosen.size() < want && tries < 100_000; tries++) {
        chosen.add(app.sqlWeights.sample(random));
      }
      for (int k = 0; chosen.size() < want && k < app.sqls.size(); k++) {
        chosen.add(k); // the Zipf tail is too thin: fill with the next statements
      }
      int k = 0;
      for (int idx : chosen) {
        active[k++] = idx;
      }
    }
    instance.activeSqls = active;
  }

  /**
   * Gives every HTTP service a log-normal shift (sigma 0.3) with traffic-weighted mean 0 and
   * returns the per-transaction sigma that keeps the overall sigma at {@code latency-sigma}.
   */
  private static double normalizeLatencyShifts(
      GeneratorConfig config, List<Service> services, SplittableRandom random) {
    double total = 0;
    double mean = 0;
    for (Service s : services) {
      s.logShift = 0.3 * random.nextGaussian();
      total += s.globalWeight;
      mean += s.globalWeight * s.logShift;
    }
    mean /= total;
    double variance = 0;
    for (Service s : services) {
      s.logShift -= mean;
      variance += s.globalWeight * s.logShift * s.logShift;
    }
    variance /= total;
    double sigma = config.latencySigma;
    if (variance >= sigma * sigma) {
      for (Service s : services) {
        s.logShift = 0;
      }
      return sigma;
    }
    return Math.sqrt(sigma * sigma - variance);
  }

  private static int[] buildRemoteIps(GeneratorConfig config, SplittableRandom random) {
    Set<Integer> ips = new LinkedHashSet<>();
    while (ips.size() < config.remoteIps) {
      int a = 1 + random.nextInt(223);
      if (a == 10 || a == 127 || a == 100 || a == 169 || a == 172 || a == 192) {
        continue;
      }
      int ip = (a << 24) | (random.nextInt(256) << 16) | (random.nextInt(256) << 8); // masked /24
      ips.add(ip);
    }
    int[] result = new int[ips.size()];
    int i = 0;
    for (int ip : ips) {
      result[i++] = ip;
    }
    return result;
  }

  /** The {@code count} indexes in [0, n) with the lowest hash, skipping {@code excluded}. */
  private static Set<Integer> lowestHashes(
      long seed, long salt, int n, int count, Set<Integer> excluded) {
    List<Integer> candidates = new ArrayList<>();
    for (int i = 0; i < n; i++) {
      if (!excluded.contains(i)) {
        candidates.add(i);
      }
    }
    candidates.sort(Comparator.comparingLong(i -> Hash.of(seed, salt, i)));
    return Collections.unmodifiableSet(
        new LinkedHashSet<>(candidates.subList(0, Math.min(count, candidates.size()))));
  }

  /** Instances with a large clock skew. */
  public List<Instance> skewedInstances() {
    return instances.stream().filter(i -> i.skewed).toList();
  }

  /** Instances without CPU measurement. */
  public List<Instance> unmeasuredInstances() {
    return instances.stream().filter(i -> !i.cpuMeasured).toList();
  }
}
