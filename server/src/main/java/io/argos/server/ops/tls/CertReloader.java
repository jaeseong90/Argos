/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.ops.tls;

import io.argos.server.audit.Actor;
import io.argos.server.audit.AuditEvent;
import io.argos.server.audit.AuditResult;
import io.argos.server.audit.AuditService;
import io.argos.server.audit.Target;
import io.argos.server.collector.grpc.CollectorTls;
import io.argos.server.collector.grpc.ReloadableSslContext;
import io.argos.server.config.ArgosProperties;
import io.argos.server.ops.management.MeterNames;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.netty.handler.ssl.SslContext;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.ClosedWatchServiceException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchService;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.cert.X509Certificate;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Certificate reload of the gRPC listener (12 §9.5 {@code CertReloader}, 16 §3.1 {@code
 * reload.watch}, §3.3, SEC-09; WO-28).
 *
 * <ul>
 *   <li>{@link #grpcContext()} builds the listener's TLS context at its start ({@code
 *       key-password-file} for an encrypted key, ADR-0010 C-158 (5)) as a {@link
 *       ReloadableSslContext}: a reload replaces it for new handshakes, connections already open
 *       keep theirs. A certificate that has expired is logged at ERROR and the start goes on (16
 *       §3.3: collection continuity; the agents cannot connect, which the operator sees at once).
 *   <li>With {@code argos.collector.tls.reload.watch=true} (default) a {@code WatchService} on the
 *       directories of the certificate, key and password files ({@value #WATCH_THREAD}) checks the
 *       files {@value #DEBOUNCE_SECONDS} s after the last change (debounce) and reloads when their
 *       content changed (directory swaps such as Kubernetes secret updates included). With {@code
 *       false} only a manual reload replaces the certificate.
 *   <li>Manual reload: {@code POST /api/v1/admin/tls/reload} ({@link #reload}) and the request file
 *       {@link #requestFile} that {@code argos-admin tls reload} leaves for the running server,
 *       checked every {@value #REQUEST_POLL_SECONDS} s; the outcome is written next to it ({@link
 *       #resultFile}).
 *   <li>A failed reload keeps the previous context and logs one ERROR line (paths and the exception
 *       class only, never key material or the password).
 *   <li>Gauges: {@value MeterNames#TLS_CERT_EXPIRY_SECONDS} with {@code listener=grpc} ({@code
 *       argos_server_tls_cert_expiry_seconds}, 12 §9.4, 16 §3.3; negative after the expiry) and the
 *       alert gauge {@value MeterNames#TLS_CERT_EXPIRY_DAYS} that the server alert rule
 *       "certificate expiry" reads ({@code SelfProvider.CERT_GAUGE}); both from the loaded leaf
 *       certificate, updated by every reload.
 *   <li>Every reload attempt is audited as {@code TLS_RELOADED} (success or failure, the source,
 *       the new {@code not_after} and serial number).
 * </ul>
 */
@Component
public class CertReloader implements DisposableBean {

  /** The listener label of the gRPC collector. */
  public static final String LISTENER_GRPC = "grpc";

  /** 16 §3.1: five seconds after the last file event. */
  static final int DEBOUNCE_SECONDS = 5;

  /** How often the request file of {@code argos-admin tls reload} is checked. */
  static final int REQUEST_POLL_SECONDS = 5;

  static final String WATCH_THREAD = "argos-tls-watch";

  /** Name of the request file of {@code argos-admin tls reload} in {@code <data>/tls}. */
  public static final String REQUEST_FILE = "reload.request";

  /** Name of the outcome file of the last request in {@code <data>/tls}. */
  public static final String RESULT_FILE = "reload.result";

  private static final Logger LOG = LoggerFactory.getLogger(CertReloader.class);

  private final ArgosProperties.Collector.Tls tls;
  private final Path requestFile;
  private final ObjectProvider<AuditService> audit;
  private final ObjectProvider<MeterRegistry> meters;
  private final Clock clock;
  private ReloadableSslContext context;
  private volatile X509Certificate leaf;
  private byte[] fingerprint;
  private ScheduledExecutorService scheduler;
  private ScheduledFuture<?> pendingCheck;
  private WatchService watch;
  private Thread watcher;
  private final boolean background;
  private volatile boolean closed;

  /** The bean of the server. */
  @Autowired
  public CertReloader(
      ArgosProperties properties,
      ObjectProvider<AuditService> audit,
      ObjectProvider<MeterRegistry> meters,
      ObjectProvider<Clock> clock) {
    this(
        properties.collector().tls(),
        requestFile(properties),
        audit,
        meters,
        clock.getIfAvailable(Clock::systemUTC));
  }

  CertReloader(
      ArgosProperties.Collector.Tls tls,
      Path requestFile,
      ObjectProvider<AuditService> audit,
      ObjectProvider<MeterRegistry> meters,
      Clock clock) {
    this(tls, requestFile, audit, meters, clock, true);
  }

  private CertReloader(
      ArgosProperties.Collector.Tls tls,
      Path requestFile,
      ObjectProvider<AuditService> audit,
      ObjectProvider<MeterRegistry> meters,
      Clock clock,
      boolean background) {
    this.tls = Objects.requireNonNull(tls, "tls");
    this.requestFile = Objects.requireNonNull(requestFile, "requestFile");
    this.audit = audit;
    this.meters = meters;
    this.clock = Objects.requireNonNull(clock, "clock");
    this.background = background;
  }

  /**
   * A reloader outside the server context (a collector-only test context without this bean): it
   * loads the files once, with no watch, no request file check, no gauges and no audit.
   */
  public static CertReloader standalone(ArgosProperties properties) {
    return new CertReloader(
        properties.collector().tls(),
        requestFile(properties),
        null,
        null,
        Clock.systemUTC(),
        false);
  }

  /**
   * {@code <data>/tls/reload.request}: {@code <data>} is the parent directory of {@code
   * argos.wal.dir} ({@code data/wal} → {@code data}), the server's data directory.
   */
  public static Path requestFile(ArgosProperties properties) {
    Path wal = Path.of(properties.wal().dir()).toAbsolutePath().normalize();
    Path data = wal.getParent() == null ? wal : wal.getParent();
    return data.resolve("tls").resolve(REQUEST_FILE);
  }

  /** {@code <data>/tls/reload.result} next to {@link #requestFile}. */
  public static Path resultFile(ArgosProperties properties) {
    return requestFile(properties).resolveSibling(RESULT_FILE);
  }

  /**
   * The certificate files of {@code tls} as the listener would load them, without a running server
   * ({@code argos-admin tls reload} checks them before it asks the server).
   *
   * @throws IllegalStateException when they cannot be loaded (message without key material)
   */
  public static CertificateInfo inspect(ArgosProperties.Collector.Tls tls) {
    CollectorTls.serverContext(
        Path.of(tls.certFile()), Path.of(tls.keyFile()), passwordFile(tls).orElse(null));
    X509Certificate leaf = CollectorTls.certificateChain(Path.of(tls.certFile()))[0];
    return CertificateInfo.of(LISTENER_GRPC, leaf);
  }

  /**
   * The leaf certificate of a listener.
   *
   * @param listener {@value #LISTENER_GRPC}
   * @param notAfter end of validity
   * @param serial serial number, lower-case hex
   * @param subject subject distinguished name
   */
  public record CertificateInfo(String listener, Instant notAfter, String serial, String subject) {
    static CertificateInfo of(String listener, X509Certificate leaf) {
      return new CertificateInfo(
          listener,
          leaf.getNotAfter().toInstant(),
          leaf.getSerialNumber().toString(16),
          leaf.getSubjectX500Principal().getName());
    }
  }

  /**
   * The outcome of a reload.
   *
   * @param listener {@value #LISTENER_GRPC}
   * @param reloaded whether new handshakes now use the files' certificate
   * @param certificate the certificate new handshakes use (the previous one after a failure), or
   *     null without TLS
   * @param error why the reload failed (operator text without key material), or null
   */
  public record ReloadResult(
      String listener, boolean reloaded, CertificateInfo certificate, String error) {}

  // --- start ---------------------------------------------------------------------------------

  /**
   * The TLS context of the gRPC listener (its start, {@code CollectorLifecycle}); starts the file
   * watch and the request file check.
   *
   * @throws IllegalStateException when the files cannot be loaded: the listener does not start
   */
  public synchronized SslContext grpcContext() {
    if (context != null) {
      return context;
    }
    SslContext initial = build();
    X509Certificate loaded = CollectorTls.certificateChain(Path.of(tls.certFile()))[0];
    context = new ReloadableSslContext(initial);
    leaf = loaded;
    fingerprint = fingerprintOrNull();
    warnIfExpired(loaded);
    if (background) {
      registerGauges();
      startBackground();
    }
    return context;
  }

  /** The certificate new handshakes use, or empty before the listener started (or without TLS). */
  public Optional<CertificateInfo> current() {
    X509Certificate l = leaf;
    return l == null ? Optional.empty() : Optional.of(CertificateInfo.of(LISTENER_GRPC, l));
  }

  /** Seconds until the loaded certificate expires (negative after), or null without one. */
  public Double expirySeconds() {
    X509Certificate l = leaf;
    if (l == null) {
      return null;
    }
    return (double) ChronoUnit.SECONDS.between(clock.instant(), l.getNotAfter().toInstant());
  }

  // --- reload --------------------------------------------------------------------------------

  /**
   * Loads the files again and makes them the context of new handshakes; on failure the previous
   * context stays. Audited as {@code TLS_RELOADED}.
   *
   * @param actor who asked (a user, or the server for the watch and the request file)
   * @param source {@code api}, {@code watch} or {@code request_file}
   */
  public synchronized ReloadResult reload(Actor actor, String source) {
    if (context == null) {
      String why =
          tls.enabled()
              ? "The gRPC listener has not started yet"
              : "TLS is off for the gRPC listener (argos.collector.tls.enabled=false)";
      audit(actor, source, AuditResult.failure("not_started"), null);
      return new ReloadResult(LISTENER_GRPC, false, null, why);
    }
    SslContext next;
    X509Certificate loaded;
    try {
      next = build();
      loaded = CollectorTls.certificateChain(Path.of(tls.certFile()))[0];
    } catch (IllegalStateException e) {
      LOG.error(
          "The gRPC TLS certificate reload ({}) failed; the previous certificate stays in use: {}",
          source,
          e.getMessage());
      audit(actor, source, AuditResult.failure("load_failed"), current().orElse(null));
      return new ReloadResult(LISTENER_GRPC, false, current().orElse(null), e.getMessage());
    }
    context.swap(next);
    leaf = loaded;
    fingerprint = fingerprintOrNull();
    CertificateInfo info = CertificateInfo.of(LISTENER_GRPC, loaded);
    LOG.info(
        "gRPC TLS certificate reloaded ({}): serial {}, valid until {}; new handshakes use it",
        source,
        info.serial(),
        info.notAfter());
    warnIfExpired(loaded);
    audit(actor, source, AuditResult.success(), info);
    return new ReloadResult(LISTENER_GRPC, true, info, null);
  }

  private SslContext build() {
    return CollectorTls.serverContext(
        Path.of(tls.certFile()), Path.of(tls.keyFile()), passwordFile(tls).orElse(null));
  }

  private static Optional<Path> passwordFile(ArgosProperties.Collector.Tls tls) {
    return Optional.ofNullable(tls.keyPasswordFile()).map(Path::of);
  }

  private void warnIfExpired(X509Certificate cert) {
    Instant notAfter = cert.getNotAfter().toInstant();
    if (!notAfter.isAfter(clock.instant())) {
      LOG.error(
          "The gRPC TLS certificate {} expired at {}: agents cannot connect until it is replaced"
              + " (16 §3.3)",
          tls.certFile(),
          notAfter);
    }
  }

  private void audit(Actor actor, String source, AuditResult result, CertificateInfo info) {
    AuditService service = audit == null ? null : audit.getIfAvailable();
    if (service == null) {
      return;
    }
    Map<String, Object> after = new LinkedHashMap<>();
    after.put("listener", LISTENER_GRPC);
    after.put("source", source);
    if (info != null) {
      after.put("not_after", info.notAfter().toString());
      after.put("serial", info.serial());
    }
    try {
      service.record(
          AuditEvent.TLS_RELOADED,
          actor,
          new Target("tls_listener", LISTENER_GRPC, LISTENER_GRPC),
          result,
          null,
          after);
    } catch (RuntimeException e) {
      LOG.warn("The TLS_RELOADED audit record failed: {}", e.getClass().getName());
    }
  }

  // --- gauges --------------------------------------------------------------------------------

  private void registerGauges() {
    MeterRegistry registry = meters == null ? null : meters.getIfAvailable();
    if (registry == null) {
      return;
    }
    Gauge.builder(MeterNames.TLS_CERT_EXPIRY_SECONDS, this, r -> nanToNull(r.expirySeconds()))
        .description(
            "Seconds until the loaded TLS certificate of the listener expires (negative after the"
                + " expiry; 16 §3.3)")
        .tag("listener", LISTENER_GRPC)
        .register(registry);
    Gauge.builder(
            MeterNames.TLS_CERT_EXPIRY_DAYS,
            this,
            r -> {
              double s = nanToNull(r.expirySeconds());
              return Double.isNaN(s) ? s : s / 86_400.0;
            })
        .description("Days until the gRPC TLS certificate expires (server alert rule)")
        .register(registry);
  }

  private static double nanToNull(Double v) {
    return v == null ? Double.NaN : v;
  }

  // --- background: file watch and request file ---------------------------------------------

  private void startBackground() {
    scheduler =
        Executors.newSingleThreadScheduledExecutor(
            r -> {
              Thread t = new Thread(r, "argos-tls-reload");
              t.setDaemon(true);
              return t;
            });
    var unused =
        scheduler.scheduleWithFixedDelay(
            this::checkRequestFile, REQUEST_POLL_SECONDS, REQUEST_POLL_SECONDS, TimeUnit.SECONDS);
    if (tls.reload().watch()) {
      startWatch();
    } else {
      LOG.info(
          "gRPC TLS certificate files are not watched (argos.collector.tls.reload.watch=false):"
              + " reload with POST /api/v1/admin/tls/reload or argos-admin tls reload");
    }
  }

  private void startWatch() {
    Set<Path> dirs = new LinkedHashSet<>();
    for (Path file : watchedFiles()) {
      Path parent = file.toAbsolutePath().normalize().getParent();
      if (parent != null) {
        dirs.add(parent);
      }
    }
    try {
      watch = dirs.iterator().next().getFileSystem().newWatchService();
      for (Path dir : dirs) {
        dir.register(
            watch,
            StandardWatchEventKinds.ENTRY_CREATE,
            StandardWatchEventKinds.ENTRY_MODIFY,
            StandardWatchEventKinds.ENTRY_DELETE);
      }
    } catch (IOException | RuntimeException e) {
      LOG.warn(
          "The gRPC TLS certificate files cannot be watched ({}); reload them with POST"
              + " /api/v1/admin/tls/reload or argos-admin tls reload",
          e.getClass().getSimpleName());
      closeWatch();
      return;
    }
    watcher = new Thread(this::watchLoop, WATCH_THREAD);
    watcher.setDaemon(true);
    watcher.start();
    LOG.info(
        "gRPC TLS certificate files watched in {} (reload {} s after a change)",
        dirs,
        DEBOUNCE_SECONDS);
  }

  private List<Path> watchedFiles() {
    List<Path> files = new ArrayList<>();
    files.add(Path.of(tls.certFile()));
    files.add(Path.of(tls.keyFile()));
    passwordFile(tls).ifPresent(files::add);
    return files;
  }

  private void watchLoop() {
    while (!closed) {
      try {
        var key = watch.take();
        key.pollEvents();
        key.reset();
        scheduleCheck();
      } catch (InterruptedException | ClosedWatchServiceException e) {
        return;
      } catch (RuntimeException e) {
        LOG.warn("The gRPC TLS file watch failed: {}", e.getClass().getName());
      }
    }
  }

  private synchronized void scheduleCheck() {
    if (closed || scheduler == null) {
      return;
    }
    if (pendingCheck != null) {
      pendingCheck.cancel(false);
    }
    pendingCheck = scheduler.schedule(this::checkFiles, DEBOUNCE_SECONDS, TimeUnit.SECONDS);
  }

  /** Reloads when the content of the watched files changed since the last load. */
  void checkFiles() {
    byte[] now = fingerprintOrNull();
    byte[] loaded;
    synchronized (this) {
      loaded = fingerprint;
    }
    if (now == null || MessageDigest.isEqual(now, loaded)) {
      return;
    }
    reload(Actor.system(), "watch");
  }

  /** The request of {@code argos-admin tls reload}: reload once and write the outcome. */
  void checkRequestFile() {
    if (closed || !Files.exists(requestFile)) {
      return;
    }
    try {
      Files.deleteIfExists(requestFile);
    } catch (IOException e) {
      LOG.warn("The TLS reload request file {} cannot be removed: {}", requestFile, e.toString());
      return;
    }
    ReloadResult result;
    try {
      result = reload(Actor.system(), "request_file");
    } catch (RuntimeException e) {
      result = new ReloadResult(LISTENER_GRPC, false, null, e.getClass().getSimpleName());
    }
    writeResult(result);
  }

  private void writeResult(ReloadResult r) {
    Path result = requestFile.resolveSibling(RESULT_FILE);
    StringBuilder json = new StringBuilder("{\"listener\":\"").append(r.listener()).append('"');
    json.append(",\"reloaded\":").append(r.reloaded());
    if (r.certificate() != null) {
      json.append(",\"not_after\":\"").append(r.certificate().notAfter()).append('"');
      json.append(",\"serial\":\"").append(r.certificate().serial()).append('"');
    }
    if (r.error() != null) {
      json.append(",\"error\":\"").append(jsonEscape(r.error())).append('"');
    }
    json.append(",\"at\":\"").append(clock.instant()).append("\"}\n");
    try {
      Path tmp = result.resolveSibling(RESULT_FILE + ".tmp");
      Files.writeString(tmp, json, StandardCharsets.UTF_8);
      Files.move(tmp, result, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    } catch (IOException e) {
      LOG.warn("The TLS reload result file {} cannot be written: {}", result, e.toString());
    }
  }

  static String jsonEscape(String s) {
    StringBuilder out = new StringBuilder(s.length());
    for (int i = 0; i < s.length(); i++) {
      char c = s.charAt(i);
      switch (c) {
        case '"' -> out.append("\\\"");
        case '\\' -> out.append("\\\\");
        default -> {
          if (c < 0x20) {
            out.append(String.format(Locale.ROOT, "\\u%04x", (int) c));
          } else {
            out.append(c);
          }
        }
      }
    }
    return out.toString();
  }

  /** SHA-256 over the watched files' contents, or null while one cannot be read. */
  private byte[] fingerprintOrNull() {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      for (Path file : watchedFiles()) {
        byte[] bytes = Files.readAllBytes(file);
        digest.update(Integer.toString(bytes.length).getBytes(StandardCharsets.US_ASCII));
        digest.update(bytes);
        Arrays.fill(bytes, (byte) 0);
      }
      return digest.digest();
    } catch (IOException | NoSuchAlgorithmException e) {
      return null;
    }
  }

  // --- stop ----------------------------------------------------------------------------------

  @Override
  public void destroy() {
    closed = true;
    closeWatch();
    Thread w = watcher;
    if (w != null) {
      w.interrupt();
    }
    ScheduledExecutorService s;
    synchronized (this) {
      s = scheduler;
    }
    if (s != null) {
      s.shutdownNow();
    }
  }

  private void closeWatch() {
    WatchService w = watch;
    if (w != null) {
      try {
        w.close();
      } catch (IOException e) {
        // Closing at the stop; nothing to do.
      }
    }
  }
}
