/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.realtime;

import io.argos.server.api.query.QueryLimits;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The validated parameters of one {@code subscribe} frame (14 §5.2). Parsing is strict: an unknown
 * parameter, a wrong type or an out-of-range value is an {@code invalid_params} error ({@link
 * InvalidTopicParams}); the frame itself (op, id, topic) is validated by the WebSocket handler.
 *
 * <ul>
 *   <li>{@code xlog}: {@code app} (required), {@code env}, {@code kind} ({@code HTTP} by default,
 *       {@code BATCH}, {@code MESSAGE}, {@code ALL}), {@code service[]} (unsigned 64-bit keys as
 *       decimal strings or numbers), {@code instance[]};
 *   <li>{@code counter}: {@code app} (required), {@code env}, {@code kind};
 *   <li>{@code active}: {@code app} (required), {@code env}, {@code kind}, {@code instance[]};
 *   <li>{@code overview}: {@code env}, {@code kind};
 *   <li>{@code alert}: {@code env}, {@code app} (WO-25 defines the data);
 *   <li>{@code admin}: {@code change_id}, {@code command_id} (WO-26 defines the data).
 * </ul>
 */
public sealed interface TopicRequest {

  /** Most values of a filter array (the REST limit, 14 §2.3). */
  int MAX_ARRAY_VALUES = QueryLimits.MAX_ARRAY_VALUES;

  /** Longest {@code env} ({@code apps.env VARCHAR(32)}). */
  int MAX_ENV_LENGTH = 32;

  /** Longest opaque id of the {@code admin} topic. */
  int MAX_ID_LENGTH = 64;

  /** The topic. */
  Topic topic();

  /** {@code xlog}. */
  record Xlog(
      int app, String env, QueryLimits.TxKind kind, Set<Long> services, Set<Integer> instances)
      implements TopicRequest {
    /** Copies. */
    public Xlog {
      Objects.requireNonNull(kind, "kind");
      services = Set.copyOf(services);
      instances = Set.copyOf(instances);
    }

    @Override
    public Topic topic() {
      return Topic.XLOG;
    }

    /** Whether a point passes the filters (kind, service, instance). */
    public boolean matches(BusPoint p) {
      return kindMatches(kind, p.kind())
          && (services.isEmpty() || services.contains(p.serviceId()))
          && (instances.isEmpty() || instances.contains(p.instanceId()));
    }
  }

  /** {@code counter}. */
  record Counter(int app, String env, QueryLimits.TxKind kind) implements TopicRequest {
    @Override
    public Topic topic() {
      return Topic.COUNTER;
    }
  }

  /** {@code active}. */
  record Active(int app, String env, QueryLimits.TxKind kind, Set<Integer> instances)
      implements TopicRequest {
    /** Copies. */
    public Active {
      instances = Set.copyOf(instances);
    }

    @Override
    public Topic topic() {
      return Topic.ACTIVE;
    }
  }

  /** {@code overview}. */
  record Overview(String env, QueryLimits.TxKind kind) implements TopicRequest {
    @Override
    public Topic topic() {
      return Topic.OVERVIEW;
    }
  }

  /** {@code alert} (no data before WO-25). */
  record Alert(String env, Integer app) implements TopicRequest {
    @Override
    public Topic topic() {
      return Topic.ALERT;
    }
  }

  /** {@code admin} (no data before WO-26). */
  record Admin(String changeId, String commandId) implements TopicRequest {
    @Override
    public Topic topic() {
      return Topic.ADMIN;
    }
  }

  /** The application of a request, 0 for the topics without one. */
  default int appId() {
    return switch (this) {
      case Xlog x -> x.app();
      case Counter c -> c.app();
      case Active a -> a.app();
      case Alert a -> a.app() == null ? 0 : a.app();
      case Overview o -> 0;
      case Admin a -> 0;
    };
  }

  /** Whether a {@code kind} choice includes the ClickHouse {@code kind} number (1, 2, 3). */
  static boolean kindMatches(QueryLimits.TxKind choice, int kindNumber) {
    return switch (choice) {
      case ALL -> true;
      case HTTP -> kindNumber == 1;
      case BATCH -> kindNumber == 2;
      case MESSAGE -> kindNumber == 3;
    };
  }

  /**
   * Parses the parameters of a subscription.
   *
   * @param params the {@code params} object of the frame as parsed JSON (strings, numbers,
   *     booleans, lists, maps); null is an empty object
   * @throws InvalidTopicParams for anything that is not valid
   */
  static TopicRequest parse(Topic topic, Map<String, ?> params) {
    Params p = new Params(params == null ? Map.of() : params);
    TopicRequest request =
        switch (topic) {
          case XLOG ->
              new Xlog(
                  p.app(), p.env(), p.kind(), p.unsignedKeys("service"), p.instances("instance"));
          case COUNTER -> new Counter(p.app(), p.env(), p.kind());
          case ACTIVE -> new Active(p.app(), p.env(), p.kind(), p.instances("instance"));
          case OVERVIEW -> new Overview(p.env(), p.kind());
          case ALERT -> new Alert(p.env(), p.optionalApp());
          case ADMIN -> new Admin(p.id("change_id"), p.id("command_id"));
        };
    p.rejectUnknown();
    return request;
  }

  /** Reading of the parameter object, remembering the keys that were read. */
  final class Params {
    private static final Pattern UNSIGNED = Pattern.compile("[0-9]{1,20}");
    private static final BigInteger U64_MAX = BigInteger.ONE.shiftLeft(64).subtract(BigInteger.ONE);

    private final Map<String, ?> values;
    private final Set<String> read = new LinkedHashSet<>();

    Params(Map<String, ?> values) {
      this.values = values;
    }

    private Object get(String key) {
      read.add(key);
      return values.get(key);
    }

    int app() {
      Integer app = optionalApp();
      if (app == null) {
        throw new InvalidTopicParams("app", "required");
      }
      return app;
    }

    Integer optionalApp() {
      Object v = get("app");
      if (v == null) {
        return null;
      }
      Long n = integral(v);
      if (n == null || n <= 0 || n > Integer.MAX_VALUE) {
        throw new InvalidTopicParams("app", "invalid_value");
      }
      return n.intValue();
    }

    String env() {
      Object v = get("env");
      if (v == null) {
        return null;
      }
      if (!(v instanceof String s) || s.isBlank() || s.length() > MAX_ENV_LENGTH) {
        throw new InvalidTopicParams("env", "invalid_value");
      }
      return s;
    }

    QueryLimits.TxKind kind() {
      Object v = get("kind");
      if (v == null) {
        return QueryLimits.TxKind.HTTP;
      }
      if (!(v instanceof String s)) {
        throw new InvalidTopicParams("kind", "invalid_value");
      }
      try {
        return QueryLimits.TxKind.valueOf(s.strip().toUpperCase(Locale.ROOT));
      } catch (IllegalArgumentException e) {
        throw new InvalidTopicParams("kind", "unsupported_value");
      }
    }

    String id(String key) {
      Object v = get(key);
      if (v == null) {
        return null;
      }
      if (!(v instanceof String s) || s.isEmpty() || s.length() > MAX_ID_LENGTH) {
        throw new InvalidTopicParams(key, "invalid_value");
      }
      return s;
    }

    Set<Long> unsignedKeys(String key) {
      Set<Long> out = new LinkedHashSet<>();
      for (Object v : array(key)) {
        BigInteger n;
        if (v instanceof String s && UNSIGNED.matcher(s).matches()) {
          n = new BigInteger(s);
        } else if (v instanceof String) {
          throw new InvalidTopicParams(key, "invalid_value");
        } else {
          Long l = integral(v);
          if (l == null || l < 0) {
            throw new InvalidTopicParams(key, "invalid_value");
          }
          n = BigInteger.valueOf(l);
        }
        if (n.compareTo(U64_MAX) > 0) {
          throw new InvalidTopicParams(key, "invalid_value");
        }
        out.add(n.longValue());
      }
      return out;
    }

    Set<Integer> instances(String key) {
      Set<Integer> out = new LinkedHashSet<>();
      for (Object v : array(key)) {
        Long n = v instanceof String s && s.matches("[0-9]{1,10}") ? Long.valueOf(s) : integral(v);
        if (n == null || n <= 0 || n > Integer.MAX_VALUE) {
          throw new InvalidTopicParams(key, "invalid_value");
        }
        out.add(n.intValue());
      }
      return out;
    }

    private Collection<?> array(String key) {
      Object v = get(key);
      if (v == null) {
        return List.of();
      }
      if (!(v instanceof Collection<?> c)) {
        throw new InvalidTopicParams(key, "invalid_value");
      }
      if (c.size() > MAX_ARRAY_VALUES) {
        throw new InvalidTopicParams(key, "too_many_values");
      }
      return c;
    }

    void rejectUnknown() {
      for (String key : values.keySet()) {
        if (!read.contains(key)) {
          throw new InvalidTopicParams(key, "unknown_parameter");
        }
      }
    }

    /** An integral JSON number, null for anything else (fractions, booleans, strings). */
    private static Long integral(Object v) {
      if (v instanceof Integer || v instanceof Long || v instanceof Short || v instanceof Byte) {
        return ((Number) v).longValue();
      }
      if (v instanceof BigInteger b) {
        return b.bitLength() < 64 ? b.longValue() : null;
      }
      if (v instanceof BigDecimal d) {
        try {
          return d.longValueExact();
        } catch (ArithmeticException e) {
          return null;
        }
      }
      if (v instanceof Double || v instanceof Float) {
        double d = ((Number) v).doubleValue();
        return d == Math.rint(d) && Math.abs(d) < 9.007199254740992E15 ? (long) d : null;
      }
      return null;
    }
  }
}
