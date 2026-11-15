/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.core.sql;

import java.util.Locale;

/**
 * JDBC URL parser of 10 §6.3 ({@code DbInfo}): the vendor, host, port and database of a JDBC URL,
 * and the {@code DB_TARGET} dictionary text {@code <vendor>://<host>:<port>/<database>} (11 §3
 * {@code DB_TARGET}, ADR-0003/02 C-16). Credentials, properties and parameters never reach the
 * result (SEC-35, F-A03): only the four parts are copied out of the URL, each one cut at the first
 * character that starts something else ({@code ?}, {@code ;}, {@code &}, {@code @}, ...).
 *
 * <p>Vendors (9 + H2) and the URL forms read:
 *
 * <ul>
 *   <li>Oracle ({@value #ORACLE}, port 1521): {@code jdbc:oracle:thin|oci|oci8|kprb:[user/pass]@}
 *       followed by {@code host:port:SID}, {@code host:port/service}, {@code //host:port/service},
 *       Easy Connect Plus {@code tcps://host:port/service?...}, a TNS descriptor {@code
 *       (DESCRIPTION=(ADDRESS=(HOST=..)(PORT=..))(CONNECT_DATA=(SERVICE_NAME|SID=..)))} (first
 *       address), {@code ldap://host:port/db,cn=...}, or a TNS alias (host = alias, no database).
 *   <li>Tibero ({@value #TIBERO}, port 8629): {@code jdbc:tibero:thin:@host:port:db}, read like
 *       Oracle.
 *   <li>MySQL ({@value #MYSQL}, 3306) and MariaDB ({@value #MARIADB}, 3306): {@code
 *       jdbc:mysql[:loadbalance|:replication|+srv]://[user:pass@]host[:port][,host2..]/db?...},
 *       {@code address=(host=..)(port=..)} and {@code (host=..,port=..)} hosts; the first host.
 *   <li>PostgreSQL ({@value #POSTGRESQL}, 5432): {@code jdbc:postgresql://host[:port][,..]/db?...},
 *       {@code jdbc:postgresql:db}; IPv6 hosts in brackets.
 *   <li>SQL Server ({@value #SQLSERVER}, 1433): {@code
 *       jdbc:sqlserver://host[\instance][:port];databaseName=db;...} ({@code database}, {@code
 *       serverName}, {@code portNumber}, {@code instanceName} properties read, the others dropped)
 *       and jTDS {@code jdbc:jtds:sqlserver://host:port/db;...}.
 *   <li>Altibase ({@value #ALTIBASE}, 20300): {@code jdbc:Altibase[_x.y]://host:port/db?...}.
 *   <li>CUBRID ({@value #CUBRID}, 33000): {@code jdbc:cubrid:host:port:db:user:password:?props}
 *       (the positional user and password are dropped).
 *   <li>DB2 ({@value #DB2}, 50000): {@code jdbc:db2://host:port/db[:prop=value;...]}, {@code
 *       jdbc:db2:db} (local).
 *   <li>H2 ({@value #H2}, 9092): {@code jdbc:h2:tcp|ssl://host[:port]/path} is a network target
 *       with the last path segment as database; the embedded forms have no host or port and are
 *       written {@code h2:mem:<name>} and {@code h2:file:<last path segment>} (directories are
 *       dropped: they can hold user names).
 * </ul>
 *
 * Wrappers {@code jdbc:p6spy:}, {@code jdbc:log4jdbc:} and {@code jdbc:otel:} are removed first.
 * Other vendors: {@code <subprotocol>://host[:port]/db} when the URL has an authority, else {@code
 * <subprotocol>:<last path segment>}. Host and vendor are lower case, the database keeps its case,
 * each part is cut at {@value #MAX_PART_CHARS} characters. A URL that cannot be read gives {@value
 * #UNKNOWN}; the parser never throws and never returns any other part of the input.
 *
 * <p>Credentials (SEC-35, T-AG-035): an Oracle or Tibero {@code user/password} ends at the first
 * {@code @} outside double quotes; a double-quoted user or password is skipped as a whole, whatever
 * it holds ({@code ( ? @ / :}), an unquoted password may hold a {@code ?} when the user is an
 * Oracle user name ({@code scott/pa?ss@db}, {@code c##scott/pa?ss@db}, {@code "scott"/pa?ss@db}).
 * An authority password ends at the last {@code @} before the first parameter value; when no
 * {@code @} there ends a user info, the authority ends as the drivers read it, at the first {@code
 * /}, {@code ?} or {@code #}, and an {@code @} after that belongs to the parameters ({@code
 * //scott:12/x?a=b@db} is host {@code scott}, port 12, where Connector/J connects too). These fail
 * closed, the target keeping its vendor alone (its text is the vendor name): an unclosed quote; an
 * Oracle-style address without a leading {@code @} in which a {@code (} outside quotes comes before
 * an {@code @} ({@code scott/p(a?ss@db}), or in which a {@code ?} outside quotes comes before the
 * {@code @} and either an {@code =} lies between them or the user is not an Oracle user name
 * ({@code db:1521/svc?password=s3c@ret}, {@code scott/p?a=b@db}; the Oracle driver rejects a thin
 * URL without a leading {@code @} anyway); an authority URL in which an {@code @} that does not end
 * a user info stands in front of the first {@code /}, or anywhere when there is no path ({@code
 * scott:p?a=b@db/shop}, {@code //h1:3306?user=a@b}); and a part that still holds a {@code "} or an
 * {@code @} (or a {@code /} in the host).
 */
public final class JdbcUrlParser {
  public static final String ORACLE = "oracle";
  public static final String MYSQL = "mysql";
  public static final String MARIADB = "mariadb";
  public static final String POSTGRESQL = "postgresql";
  public static final String SQLSERVER = "sqlserver";
  public static final String TIBERO = "tibero";
  public static final String ALTIBASE = "altibase";
  public static final String CUBRID = "cubrid";
  public static final String DB2 = "db2";
  public static final String H2 = "h2";

  /** Vendor and text of a URL that cannot be read. */
  public static final String UNKNOWN = "unknown";

  /** Characters kept of the vendor, the host and the database. */
  static final int MAX_PART_CHARS = 128;

  private static final String[] WRAPPERS = {"p6spy:", "log4jdbc:", "otel:"};

  /** {@link #endOfOracleCredentials}: a double quote that is never closed. */
  private static final int UNTERMINATED_QUOTE = -2;

  private JdbcUrlParser() {}

  /** The parts of one JDBC URL. */
  public static final class Target {
    private final String vendor;
    private final String host;
    private final int port;
    private final String database;
    private final String embeddedMode;

    Target(String vendor, String host, int port, String database, String embeddedMode) {
      this.vendor = cut(vendor == null ? UNKNOWN : vendor.toLowerCase(Locale.ROOT));
      this.host = host == null ? null : cut(host.toLowerCase(Locale.ROOT));
      this.port = port;
      this.database = database == null ? "" : cut(database);
      this.embeddedMode = embeddedMode;
    }

    /** Vendor name ({@link JdbcUrlParser#ORACLE}, ...), {@value JdbcUrlParser#UNKNOWN} else. */
    public String vendor() {
      return vendor;
    }

    /** Host (lower case, IPv6 in brackets), null for an embedded database. */
    public String host() {
      return host;
    }

    /** Port (the vendor's default when the URL has none), -1 when not known. */
    public int port() {
      return port;
    }

    /** Database, SID or service name; empty when the URL names none. */
    public String database() {
      return database;
    }

    /** True for an embedded database (H2 {@code mem}/{@code file}, other vendors' paths). */
    public boolean embedded() {
      return embeddedMode != null;
    }

    /**
     * The {@code DB_TARGET} text: {@code <vendor>://<host>:<port>/<database>} ({@code :<port>} left
     * out when the port is not known), {@code <vendor>:<mode>:<name>} for an embedded database,
     * {@value JdbcUrlParser#UNKNOWN} for an unreadable URL.
     */
    public String text() {
      if (embeddedMode != null) {
        return vendor + ":" + embeddedMode + database;
      }
      if (host == null) {
        return vendor;
      }
      StringBuilder out =
          new StringBuilder(vendor.length() + host.length() + database.length() + 16);
      out.append(vendor).append("://").append(host);
      if (port >= 0) {
        out.append(':').append(port);
      }
      return out.append('/').append(database).toString();
    }

    @Override
    public String toString() {
      return text();
    }
  }

  /** The {@code DB_TARGET} text of {@code url} ({@link Target#text()}). */
  public static String targetText(String url) {
    return parse(url).text();
  }

  /** The parts of {@code url}; never null, never throws. */
  public static Target parse(String url) {
    try {
      Target target = read(url);
      if (target == null) {
        return unknown();
      }
      // Last line of defence (SEC-35): a part that still holds a character of the credentials
      // syntax means the URL was not read as intended; keep the vendor alone.
      return leftovers(target) ? vendorOnly(target.vendor()) : target;
    } catch (RuntimeException e) {
      return unknown();
    }
  }

  private static Target unknown() {
    return new Target(UNKNOWN, null, -1, null, null);
  }

  /** A target of which only the vendor is known (the text is the vendor name). */
  private static Target vendorOnly(String vendor) {
    return new Target(vendor, null, -1, null, null);
  }

  /**
   * True when the host holds {@code "}, {@code @} or {@code /}, or the database {@code
   * "}/{@code @}.
   */
  private static boolean leftovers(Target target) {
    String host = target.host();
    String database = target.database();
    return (host != null && indexOfAny(host, "\"@/") >= 0) || indexOfAny(database, "\"@") >= 0;
  }

  private static Target read(String url) {
    if (url == null) {
      return null;
    }
    String value = url.trim();
    if (!value.regionMatches(true, 0, "jdbc:", 0, 5)) {
      return null;
    }
    String rest = value.substring(5);
    for (String wrapper : WRAPPERS) {
      if (rest.regionMatches(true, 0, wrapper, 0, wrapper.length())) {
        rest = rest.substring(wrapper.length());
      }
    }
    int colon = rest.indexOf(':');
    if (colon <= 0) {
      return null;
    }
    String sub = rest.substring(0, colon).toLowerCase(Locale.ROOT);
    String after = rest.substring(colon + 1);
    if (sub.startsWith("mysql")) {
      return authorityUrl(MYSQL, 3306, afterScheme(after));
    }
    if (sub.equals(MARIADB)) {
      return authorityUrl(MARIADB, 3306, afterScheme(after));
    }
    if (sub.equals(POSTGRESQL) || sub.equals("pgsql")) {
      return postgresql(after);
    }
    if (sub.equals(ORACLE)) {
      return oracleStyle(ORACLE, 1521, after);
    }
    if (sub.equals(TIBERO)) {
      return oracleStyle(TIBERO, 8629, after);
    }
    if (sub.equals(SQLSERVER)) {
      return sqlServer(after);
    }
    if (sub.equals("jtds")) {
      return jtds(after);
    }
    if (sub.startsWith(ALTIBASE)) {
      return authorityUrl(ALTIBASE, 20300, after);
    }
    if (sub.equals(CUBRID)) {
      return cubrid(after);
    }
    if (sub.equals(DB2)) {
      return db2(after);
    }
    if (sub.equals(H2)) {
      return h2(after);
    }
    return other(sub, after);
  }

  // --- authority URLs: //[user:pass@]host[:port][,host..]/database?params ----------------------

  /**
   * {@code after} without a sub-scheme ({@code loadbalance:}, {@code replication:}, {@code
   * failover:}, ...) in front of {@code //}.
   */
  private static String afterScheme(String after) {
    int slashes = after.indexOf("//");
    if (slashes > 0 && after.substring(0, slashes).matches("[A-Za-z0-9+]+:")) {
      return after.substring(slashes);
    }
    return after;
  }

  /**
   * {@code //[user[:password]@]host[:port][,host..]/database?params}. The user info ends at the
   * last {@code @} before the first parameter value ({@link #endOfUserInfo}). When no {@code @}
   * ends a user info but one still stands in front of the first {@code /} (anywhere, when the URL
   * has no path), the URL is not read and the target keeps its vendor alone (fail closed): a
   * password holding a {@code ?}, {@code ;} or {@code #} before an {@code =} ({@code
   * scott:p?a=b@db/shop}) would otherwise give a user or password fragment as the host, and a
   * parameter value of a URL without a path ({@code //h1:3306?user=a@b}) cannot be told from it. An
   * {@code @} after the path ({@code /db?user=admin@srv}) is a parameter value and is allowed.
   */
  private static Target authorityUrl(String vendor, int defaultPort, String after) {
    if (!after.startsWith("//")) {
      return null;
    }
    String body = after.substring(2);
    // MySQL address=(host=..)(port=..) and (host=..,port=..): read the groups before anything
    // else, so that a '/', '@' or '?' inside a group value (a password) is never taken as a cut.
    if (body.startsWith("address=") || body.startsWith("(")) {
      int groupsEnd = endOfGroups(body);
      String groups = body.substring(0, groupsEnd);
      String host = keyValue(groups, "host");
      int port = parsePort(keyValue(groups, "port"), defaultPort);
      String database = afterSlash(body.substring(groupsEnd), "?;");
      return new Target(vendor, host == null ? "localhost" : host, port, database, null);
    }
    int at = endOfUserInfo(body, firstValue(body, "?;#"));
    if (at < 0) {
      int firstSlash = body.indexOf('/');
      if ((firstSlash < 0 ? body : body.substring(0, firstSlash)).indexOf('@') >= 0) {
        return vendorOnly(vendor);
      }
    }
    String base = cutAt(at >= 0 ? body.substring(at + 1) : body, "?;#");
    int slash = base.indexOf('/');
    String hosts = slash < 0 ? base : base.substring(0, slash);
    String database = slash < 0 ? "" : cutAt(base.substring(slash + 1), "/:,");
    return hostPort(vendor, defaultPort, firstHost(hosts), database);
  }

  /**
   * Index of the {@code =} of the first parameter value: the first {@code =} after the first of
   * {@code starts} ({@code ?}, {@code ;}, {@code #}), or the length of {@code body} when there is
   * none.
   */
  private static int firstValue(String body, String starts) {
    int start = indexOfAny(body, starts);
    int eq = start < 0 ? -1 : body.indexOf('=', start);
    return eq < 0 ? body.length() : eq;
  }

  /**
   * Index of the {@code @} that ends a {@code user[:password]} in front of the host, or -1: the
   * last {@code @} before {@code limit}, the first parameter value. A password may hold {@code (},
   * {@code @}, {@code /} and {@code :}, and a {@code ?}, {@code ;} or {@code #} with no {@code =}
   * between it and the {@code @} (else that {@code =} is taken for the first parameter value and
   * {@link #authorityUrl} fails closed); an {@code @} inside a parameter value ({@code
   * /db?user=admin@srv}) is never taken for it.
   */
  private static int endOfUserInfo(String body, int limit) {
    return limit <= 0 ? -1 : body.lastIndexOf('@', limit - 1);
  }

  /** End of the leading {@code address=(..)(..)} or {@code (..)} groups (and their commas). */
  private static int endOfGroups(String body) {
    int i = body.startsWith("address=") ? "address=".length() : 0;
    while (i < body.length() && body.charAt(i) == '(') {
      int depth = 0;
      while (i < body.length()) {
        char c = body.charAt(i++);
        if (c == '(') {
          depth++;
        } else if (c == ')' && --depth == 0) {
          break;
        }
      }
      if (i < body.length() && body.charAt(i) == ',') {
        // A second host: stop, the first one is the target.
        return i;
      }
    }
    return i;
  }

  /** The value of {@code key=value} in {@code (k=v)(k=v)} or {@code (k=v,k=v)}, any key case. */
  private static String keyValue(String groups, String key) {
    String lower = groups.toLowerCase(Locale.ROOT);
    int from = 0;
    while (true) {
      int at = lower.indexOf(key + "=", from);
      if (at < 0) {
        return null;
      }
      char before = at == 0 ? '(' : lower.charAt(at - 1);
      if (before == '(' || before == ',' || before == ' ') {
        int start = at + key.length() + 1;
        int end = start;
        while (end < groups.length() && ")(,".indexOf(groups.charAt(end)) < 0) {
          end++;
        }
        return groups.substring(start, end).trim();
      }
      from = at + 1;
    }
  }

  private static String afterSlash(String tail, String cuts) {
    int slash = tail.indexOf('/');
    return slash < 0 ? "" : cutAt(tail.substring(slash + 1), cuts + "/:,");
  }

  /** The first host of a comma-separated host list. */
  private static String firstHost(String hosts) {
    String first = hosts;
    int comma = indexOfOutsideBrackets(first, ',');
    if (comma >= 0) {
      first = first.substring(0, comma);
    }
    return first.trim();
  }

  private static Target hostPort(String vendor, int defaultPort, String hostAndPort, String db) {
    String host = hostAndPort;
    int port = defaultPort;
    if (host.startsWith("[")) {
      int close = host.indexOf(']');
      if (close > 0) {
        String tail = host.substring(close + 1);
        host = host.substring(0, close + 1);
        if (tail.startsWith(":")) {
          port = parsePort(tail.substring(1), defaultPort);
        }
      }
    } else {
      int colon = host.lastIndexOf(':');
      if (colon >= 0 && host.indexOf(':') == colon) {
        port = parsePort(host.substring(colon + 1), defaultPort);
        host = host.substring(0, colon);
      } else if (colon >= 0) {
        // A bare IPv6 address without brackets: keep it whole.
        host = "[" + host + "]";
      }
    }
    if (host.isEmpty()) {
      host = "localhost";
    }
    return new Target(vendor, host, port, db, null);
  }

  // --- PostgreSQL ------------------------------------------------------------------------------

  private static Target postgresql(String after) {
    if (after.startsWith("//")) {
      return authorityUrl(POSTGRESQL, 5432, after);
    }
    // jdbc:postgresql:database (localhost:5432)
    return new Target(POSTGRESQL, "localhost", 5432, cutAt(after, "?;/"), null);
  }

  // --- Oracle and Tibero -----------------------------------------------------------------------

  private static Target oracleStyle(String vendor, int defaultPort, String after) {
    String address = after;
    int colon = address.indexOf(':');
    int at = address.indexOf('@');
    // thin:, oci:, oci8:, kprb: (the driver type) before the address.
    if (colon >= 0 && (at < 0 || colon < at)) {
      address = address.substring(colon + 1);
    }
    if (address.startsWith("(")) {
      // A TNS descriptor without credentials in front.
      return descriptor(vendor, defaultPort, address);
    }
    if (address.startsWith("@")) {
      address = address.substring(1);
    } else {
      // user/password@ : a double-quoted user or password may hold any of ( ? @ / : and is
      // skipped as a whole, an unquoted one may hold a '?'; the first '@' outside quotes ends the
      // credentials.
      int end = endOfOracleCredentials(address);
      if (end == UNTERMINATED_QUOTE) {
        return vendorOnly(vendor);
      }
      if (end >= 0) {
        if (!readsAsCredentials(address, end)) {
          // An address without a leading '@' whose '@' may sit in a parameter value
          // (db:1521/svc?password=s3c@ret): what follows the '@' would be read as the host.
          return vendorOnly(vendor);
        }
        address = address.substring(end + 1);
      } else if (indexOutsideQuotes(address, '@') != -1) {
        // A '(' outside quotes came before an '@' (scott/p(a?ss@db): an unquoted password holding
        // '(' would be read as a user or password fragment in place of the host. Fail closed.
        return vendorOnly(vendor);
      }
    }
    if (address.startsWith("(")) {
      return descriptor(vendor, defaultPort, address);
    }
    address = cutAt(address, "?");
    String lower = address.toLowerCase(Locale.ROOT);
    if (lower.startsWith("ldap://") || lower.startsWith("ldaps://")) {
      String body = address.substring(address.indexOf("//") + 2);
      int slash = body.indexOf('/');
      String hostPart = slash < 0 ? body : body.substring(0, slash);
      String db = slash < 0 ? "" : cutAt(body.substring(slash + 1), ",/ ");
      return hostPort(vendor, 389, firstHost(hostPart), db);
    }
    int scheme = address.indexOf("://");
    if (scheme > 0 && address.substring(0, scheme).matches("[A-Za-z]+")) {
      address = address.substring(scheme + 3);
    } else if (address.startsWith("//")) {
      address = address.substring(2);
    }
    int slash = address.indexOf('/');
    if (slash >= 0) {
      // host[:port]/service[:server][/instance]
      String db = cutAt(address.substring(slash + 1), ":/");
      return hostPort(vendor, defaultPort, firstHost(address.substring(0, slash)), db);
    }
    String[] parts = address.split(":", -1);
    if (parts.length >= 3) {
      // host:port:SID
      return new Target(
          vendor, emptyAsLocal(parts[0]), parsePort(parts[1], defaultPort), parts[2], null);
    }
    if (parts.length == 2) {
      return new Target(vendor, emptyAsLocal(parts[0]), parsePort(parts[1], defaultPort), "", null);
    }
    // A TNS alias resolved by tnsnames.ora: its name is all the URL tells.
    return new Target(vendor, emptyAsLocal(parts[0]), defaultPort, "", null);
  }

  /**
   * Index of the {@code @} that ends the {@code user/password} of an Oracle-style address, -1 when
   * the address has no credentials (a {@code (} outside quotes comes first, or no {@code @} at
   * all), {@link #UNTERMINATED_QUOTE} when a double quote is not closed. A quoted part is skipped
   * as a whole, so a {@code (}, {@code ?}, {@code @}, {@code /} or {@code :} inside it is never
   * taken as a separator; an unquoted {@code ?} is part of the credentials ({@code
   * scott/pa?ss@db}).
   */
  private static int endOfOracleCredentials(String address) {
    int i = 0;
    while (i < address.length()) {
      char c = address.charAt(i);
      if (c == '"') {
        int close = address.indexOf('"', i + 1);
        if (close < 0) {
          return UNTERMINATED_QUOTE;
        }
        i = close + 1;
        continue;
      }
      if (c == '@') {
        return i;
      }
      if (c == '(') {
        return -1;
      }
      i++;
    }
    return -1;
  }

  /**
   * True when {@code address[0, at)}, the text in front of the {@code @} that {@link
   * #endOfOracleCredentials} found, can be a {@code user/password}: it holds no {@code ?} outside
   * double quotes, or it holds one, no {@code =} outside quotes follows that {@code ?} before the
   * {@code @}, and the user (the text before the first {@code /} outside quotes, or all of it) is
   * an Oracle user name, double-quoted ({@code "scott"}) or made of ASCII letters, digits, {@code
   * _}, {@code $} and {@code #} ({@code c##scott}). Otherwise the {@code ?} may start the
   * parameters of an address without credentials ({@code db:1521/svc?password=s3c@ret}, {@code
   * scott/p?a=b@db}) and the {@code @} lie in a parameter value. Character loops only, no regular
   * expression.
   */
  private static boolean readsAsCredentials(String address, int at) {
    int question = -1;
    int slash = -1;
    int i = 0;
    while (i < at) {
      char c = address.charAt(i);
      if (c == '"') {
        // endOfOracleCredentials found this quote closed before the '@'.
        i = address.indexOf('"', i + 1) + 1;
        continue;
      }
      if (c == '?' && question < 0) {
        question = i;
      } else if (c == '=' && question >= 0) {
        return false;
      } else if (c == '/' && slash < 0) {
        slash = i;
      }
      i++;
    }
    return question < 0 || isOracleUserName(address, 0, slash >= 0 ? slash : at);
  }

  /**
   * True when {@code value[from, until)} is an Oracle user name: one double-quoted part, or one or
   * more ASCII letters, digits, {@code _}, {@code $} and {@code #}.
   */
  private static boolean isOracleUserName(String value, int from, int until) {
    if (until <= from) {
      return false;
    }
    if (value.charAt(from) == '"') {
      return until - from >= 2 && value.indexOf('"', from + 1) == until - 1;
    }
    for (int i = from; i < until; i++) {
      char c = value.charAt(i);
      boolean letter = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z');
      if (!(letter || SqlTokenizer.isDigit(c) || c == '_' || c == '$' || c == '#')) {
        return false;
      }
    }
    return true;
  }

  /**
   * Index of the first {@code wanted} outside double quotes, -1 when there is none, {@link
   * #UNTERMINATED_QUOTE} when a double quote is not closed before one is found.
   */
  private static int indexOutsideQuotes(String value, char wanted) {
    int i = 0;
    while (i < value.length()) {
      char c = value.charAt(i);
      if (c == '"') {
        int close = value.indexOf('"', i + 1);
        if (close < 0) {
          return UNTERMINATED_QUOTE;
        }
        i = close + 1;
        continue;
      }
      if (c == wanted) {
        return i;
      }
      i++;
    }
    return -1;
  }

  /** The first ADDRESS and the service name (or SID) of a TNS connect descriptor. */
  private static Target descriptor(String vendor, int defaultPort, String text) {
    StringBuilder compact = new StringBuilder(text.length());
    for (int i = 0; i < text.length(); i++) {
      if (!Character.isWhitespace(text.charAt(i))) {
        compact.append(text.charAt(i));
      }
    }
    String descriptor = compact.toString();
    String host = keyValue(descriptor, "host");
    int port = parsePort(keyValue(descriptor, "port"), defaultPort);
    String db = keyValue(descriptor, "service_name");
    if (db == null) {
      db = keyValue(descriptor, "sid");
    }
    return new Target(vendor, host == null ? "localhost" : host, port, db, null);
  }

  // --- SQL Server ------------------------------------------------------------------------------

  private static Target sqlServer(String after) {
    if (!after.startsWith("//")) {
      return null;
    }
    String body = after.substring(2);
    int semicolon = body.indexOf(';');
    String server = semicolon < 0 ? body : body.substring(0, semicolon);
    String properties = semicolon < 0 ? "" : body.substring(semicolon + 1);
    int at = server.lastIndexOf('@');
    if (at >= 0) {
      server = server.substring(at + 1);
    }
    String host = server;
    int port = -1;
    String instance = null;
    int close = host.startsWith("[") ? host.indexOf(']') : -1;
    int colon = host.lastIndexOf(':');
    if (colon > close) {
      port = parsePort(host.substring(colon + 1), -1);
      host = host.substring(0, colon);
    }
    int backslash = host.indexOf('\\');
    if (backslash >= 0) {
      instance = host.substring(backslash + 1);
      host = host.substring(0, backslash);
    }
    String database = property(properties, "databaseName");
    if (database == null) {
      database = property(properties, "database");
    }
    if (host.isEmpty()) {
      String named = property(properties, "serverName");
      host = named == null ? "" : named;
    }
    if (port < 0) {
      port = parsePort(property(properties, "portNumber"), 1433);
    }
    if (instance == null) {
      instance = property(properties, "instanceName");
    }
    if (host.isEmpty()) {
      host = "localhost";
    }
    if (instance != null && !instance.isEmpty()) {
      host = host + "\\" + instance;
    }
    return new Target(SQLSERVER, host, port, database, null);
  }

  /** The value of {@code name=value} in {@code a=1;b=2} properties (name in any case), or null. */
  private static String property(String properties, String name) {
    for (String pair : properties.split(";", -1)) {
      int eq = pair.indexOf('=');
      if (eq > 0 && pair.substring(0, eq).trim().equalsIgnoreCase(name)) {
        return pair.substring(eq + 1).trim();
      }
    }
    return null;
  }

  private static Target jtds(String after) {
    String lower = after.toLowerCase(Locale.ROOT);
    if (lower.startsWith("sqlserver:")) {
      return authorityUrl(SQLSERVER, 1433, after.substring("sqlserver:".length()));
    }
    int colon = after.indexOf(':');
    return colon > 0 ? other(after.substring(0, colon), after.substring(colon + 1)) : null;
  }

  // --- CUBRID, DB2, H2 -------------------------------------------------------------------------

  private static Target cubrid(String after) {
    // host:port:db:user:password:?props (positional credentials are never read).
    String[] parts = cutAt(after, "?").split(":", -1);
    String host = parts.length > 0 ? parts[0] : "";
    int port = parts.length > 1 ? parsePort(parts[1], 33000) : 33000;
    String db = parts.length > 2 ? parts[2] : "";
    return new Target(CUBRID, emptyAsLocal(host), port, db, null);
  }

  private static Target db2(String after) {
    if (after.startsWith("//")) {
      // DB2 properties follow the database (":user=..;password=..;"): every '=' is a value, so
      // a user info ends at the last '@' before the first '='.
      String body = after.substring(2);
      int eq = body.indexOf('=');
      int at = endOfUserInfo(body, eq < 0 ? body.length() : eq);
      if (at >= 0) {
        body = body.substring(at + 1);
      }
      int slash = body.indexOf('/');
      String hostPart = cutAt(slash < 0 ? body : body.substring(0, slash), ";?");
      String db = slash < 0 ? "" : cutAt(body.substring(slash + 1), ":;?/");
      return hostPort(DB2, 50000, firstHost(hostPart), db);
    }
    // jdbc:db2:database (a local catalog entry).
    return new Target(DB2, "localhost", 50000, cutAt(after, ":;?/"), null);
  }

  private static Target h2(String after) {
    String lower = after.toLowerCase(Locale.ROOT);
    if (lower.startsWith("tcp://") || lower.startsWith("ssl://")) {
      String body = after.substring(6);
      String base = cutAt(body, ";?");
      int slash = base.indexOf('/');
      String hostPart = slash < 0 ? base : base.substring(0, slash);
      String path = slash < 0 ? "" : base.substring(slash + 1);
      return hostPort(H2, 9092, firstHost(hostPart), lastSegment(path));
    }
    String path = cutAt(after, ";?");
    if (lower.startsWith("mem:")) {
      return new Target(H2, null, -1, path.substring(4), "mem:");
    }
    int prefix = path.indexOf(':');
    if (prefix > 1 && path.substring(0, prefix).matches("[A-Za-z]+")) {
      // file:, split:, nio:, zip: ... (a one-letter prefix is a Windows drive).
      path = path.substring(prefix + 1);
    }
    return new Target(H2, null, -1, lastSegment(path), "file:");
  }

  // --- other vendors ---------------------------------------------------------------------------

  private static Target other(String sub, String after) {
    String vendor = sub.replaceAll("[^a-z0-9_.+-]", "");
    if (vendor.isEmpty()) {
      return null;
    }
    if (after.startsWith("//")) {
      return authorityUrl(vendor, -1, after);
    }
    String path = cutAt(after, ";?");
    int at = path.lastIndexOf('@');
    if (at >= 0) {
      path = path.substring(at + 1);
    }
    return new Target(vendor, null, -1, lastSegment(path), "");
  }

  // --- helpers ---------------------------------------------------------------------------------

  /** The last segment of a path ({@code /} or {@code \} separated); directories are dropped. */
  private static String lastSegment(String path) {
    int cut = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'));
    return cut < 0 ? path : path.substring(cut + 1);
  }

  private static String emptyAsLocal(String host) {
    String value = host == null ? "" : host.trim();
    if (value.startsWith("//")) {
      value = value.substring(2);
    }
    return value.isEmpty() ? "localhost" : value;
  }

  private static int parsePort(String text, int fallback) {
    if (text == null) {
      return fallback;
    }
    String digits = text.trim();
    if (digits.isEmpty() || digits.length() > 5) {
      return fallback;
    }
    for (int i = 0; i < digits.length(); i++) {
      if (!SqlTokenizer.isDigit(digits.charAt(i))) {
        return fallback;
      }
    }
    int port = Integer.parseInt(digits);
    return port > 0 && port <= 65_535 ? port : fallback;
  }

  /** {@code value} up to the first character of {@code stops}. */
  private static String cutAt(String value, String stops) {
    int cut = indexOfAny(value, stops);
    return cut < 0 ? value : value.substring(0, cut);
  }

  private static int indexOfAny(String value, String chars) {
    for (int i = 0; i < value.length(); i++) {
      if (chars.indexOf(value.charAt(i)) >= 0) {
        return i;
      }
    }
    return -1;
  }

  private static int indexOfOutsideBrackets(String value, char wanted) {
    int depth = 0;
    for (int i = 0; i < value.length(); i++) {
      char c = value.charAt(i);
      if (c == '[') {
        depth++;
      } else if (c == ']') {
        depth = Math.max(0, depth - 1);
      } else if (c == wanted && depth == 0) {
        return i;
      }
    }
    return -1;
  }

  private static String cut(String value) {
    return value.length() > MAX_PART_CHARS ? value.substring(0, MAX_PART_CHARS) : value;
  }
}
