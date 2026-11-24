/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.privacy.salt;

import io.argos.server.secrets.EnvelopeCipher;
import io.argos.server.secrets.SecretField;
import io.argos.server.secrets.SecretUnavailableException;
import io.argos.server.session.HelloService;
import io.argos.server.storage.postgres.privacy.UserIdSaltRepository;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Component;

/**
 * The user id salts of the applications (16 §6.3, ADR-0010 C-125 (2), C-152, C-163): 32 CSPRNG
 * bytes per application, stored as an envelope in {@code apps.user_id_salt_enc} ({@link
 * SecretField#APP_USER_ID_SALT}), created the first time one is needed, by whichever comes first:
 * an agent Hello ({@code ServerHello.user_id_salt}, wired into {@link HelloService} here), a hashed
 * user id search or a privacy deletion ({@link #hashed}). Concurrent creators (two servers, two
 * requests) agree: only the first stored value wins and the others read it back.
 *
 * <ul>
 *   <li>Cache: at most {@value #MAX_CACHED} salts in memory (least recently used out), so a Hello
 *       or a search reads PostgreSQL once per application. A read that fails is not retried for
 *       {@link #RETRY_AFTER} for that application (the Hello path of a PostgreSQL outage, 12 §3.9,
 *       does not wait on it each time).
 *   <li>Neither a salt nor a user id (plain or hashed) is logged; failures log the application id
 *       and the failure class, at most once a minute.
 * </ul>
 */
@Component
public final class AppSaltService implements UserIdHasher {

  private static final Logger LOG = LoggerFactory.getLogger(AppSaltService.class);

  /** Salt length (16 §6.3: 32 bytes). */
  public static final int SALT_BYTES = 32;

  /** Salts kept in memory. */
  static final int MAX_CACHED = 10_000;

  /** How long a failed read of one application is not retried. */
  static final Duration RETRY_AFTER = Duration.ofSeconds(30);

  private static final long WARN_INTERVAL_MS = 60_000;

  private final UserIdSaltRepository repository;
  private final EnvelopeCipher cipher;
  private final Clock clock;
  private final SecureRandom random = new SecureRandom();
  private final AtomicLong lastWarn = new AtomicLong(Long.MIN_VALUE);

  /** Access-ordered: the eldest entry is the least recently used. Guarded by itself. */
  private final Map<Integer, Entry> cache =
      new LinkedHashMap<>(256, 0.75f, true) {
        private static final long serialVersionUID = 1L;

        @Override
        protected boolean removeEldestEntry(Map.Entry<Integer, Entry> eldest) {
          return size() > MAX_CACHED;
        }
      };

  /** The service; connects itself to the agent Hello when the collector is configured. */
  @Autowired
  public AppSaltService(
      UserIdSaltRepository repository,
      EnvelopeCipher cipher,
      ObjectProvider<HelloService> hello,
      ObjectProvider<Clock> clock) {
    this(repository, cipher, clock.getIfUnique(Clock::systemUTC));
    HelloService h = hello.getIfAvailable();
    if (h != null) {
      h.userIdSalts(this::salt);
    }
  }

  /** Without the Hello wiring (tests). */
  public AppSaltService(UserIdSaltRepository repository, EnvelopeCipher cipher, Clock clock) {
    this.repository = repository;
    this.cipher = cipher;
    this.clock = clock;
  }

  /**
   * The salt of an application (a copy; the caller may overwrite it), created when it has none.
   *
   * @return empty when the application does not exist or is deleted, or the salt cannot be read or
   *     stored now (PostgreSQL, a key id that is not loaded)
   */
  public Optional<byte[]> salt(int appId) {
    long now = clock.millis();
    synchronized (cache) {
      Entry e = cache.get(appId);
      if (e != null) {
        if (e.salt != null) {
          return Optional.of(e.salt.clone());
        }
        if (now - e.failedAt < RETRY_AFTER.toMillis()) {
          return Optional.empty();
        }
      }
    }
    byte[] salt;
    try {
      salt = load(appId);
    } catch (DataAccessException | SecretUnavailableException e) {
      warn(appId, e);
      synchronized (cache) {
        cache.put(appId, new Entry(null, now));
      }
      return Optional.empty();
    }
    if (salt == null) {
      return Optional.empty();
    }
    synchronized (cache) {
      cache.put(appId, new Entry(salt, 0));
    }
    return Optional.of(salt.clone());
  }

  @Override
  public Optional<String> hashed(int appId, String normalizedUserId) {
    if (normalizedUserId == null || normalizedUserId.isEmpty()) {
      return Optional.empty();
    }
    Optional<byte[]> salt = salt(appId);
    if (salt.isEmpty()) {
      return Optional.empty();
    }
    byte[] key = salt.get();
    try {
      return Optional.of(hash(key, normalizedUserId));
    } finally {
      Arrays.fill(key, (byte) 0);
    }
  }

  /**
   * {@code base64url(HMAC-SHA256(salt, UTF-8 id))} without padding, its first {@value
   * UserIdHasher#HASH_CHARS} characters (16 §6.3, C-163 (1)): what the agent records.
   */
  static String hash(byte[] salt, String normalizedUserId) {
    try {
      Mac mac = Mac.getInstance("HmacSHA256");
      mac.init(new SecretKeySpec(salt, "HmacSHA256"));
      byte[] digest = mac.doFinal(normalizedUserId.getBytes(StandardCharsets.UTF_8));
      return Base64.getUrlEncoder()
          .withoutPadding()
          .encodeToString(digest)
          .substring(0, HASH_CHARS);
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException("HmacSHA256 is not available", e);
    }
  }

  /** Forgets the cached salts (tests; a salt never changes once stored). */
  public void clearCache() {
    synchronized (cache) {
      cache.clear();
    }
  }

  private byte @Nullable [] load(int appId) {
    Optional<UserIdSaltRepository.Stored> stored = repository.find(appId);
    if (stored.isEmpty()) {
      return null;
    }
    String envelope = stored.get().envelope();
    if (envelope == null) {
      byte[] fresh = new byte[SALT_BYTES];
      random.nextBytes(fresh);
      String sealed = SecretField.APP_USER_ID_SALT.encrypt(cipher, fresh, Integer.toString(appId));
      if (repository.storeIfAbsent(appId, sealed)) {
        LOG.info("User id salt created for app {} (16 §6.3)", appId);
        return fresh;
      }
      Arrays.fill(fresh, (byte) 0);
      // Another server or request stored one first: use that.
      stored = repository.find(appId);
      if (stored.isEmpty() || stored.get().envelope() == null) {
        return null;
      }
      envelope = stored.get().envelope();
    }
    byte[] salt = SecretField.APP_USER_ID_SALT.decrypt(cipher, envelope, Integer.toString(appId));
    if (salt.length != SALT_BYTES) {
      Arrays.fill(salt, (byte) 0);
      throw new SecretUnavailableException("malformed");
    }
    return salt;
  }

  private void warn(int appId, RuntimeException e) {
    long now = clock.millis();
    long last = lastWarn.get();
    if ((last == Long.MIN_VALUE || now - last >= WARN_INTERVAL_MS)
        && lastWarn.compareAndSet(last, now)) {
      String reason =
          e instanceof SecretUnavailableException s ? s.reason() : e.getClass().getSimpleName();
      LOG.warn(
          "The user id salt of app {} cannot be read ({}); hashed user ids of the app are not"
              + " searchable and its agents get no salt until it can; further failures are not"
              + " logged for a minute",
          appId,
          reason);
    }
  }

  private static final class Entry {
    final byte @Nullable [] salt;
    final long failedAt;

    Entry(byte @Nullable [] salt, long failedAt) {
      this.salt = salt;
      this.failedAt = failedAt;
    }
  }
}
