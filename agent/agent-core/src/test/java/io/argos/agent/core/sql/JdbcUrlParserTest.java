/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.core.sql;

import static org.assertj.core.api.Assertions.assertThat;

import io.argos.agent.bootstrap.DictKind;
import io.argos.agent.core.trace.Dictionary;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * JdbcUrlParser (10 §6.3 DbInfo, F-A03, SEC-35): vendor, host, port and database of the URL forms
 * of 9 vendors and H2, with credentials, properties and parameters removed, as the {@code
 * DB_TARGET} text {@code <vendor>://<host>:<port>/<database>}.
 */
@Tag("req:F-A03")
@Tag("req:SEC-35")
class JdbcUrlParserTest {
  /** The secrets of the URLs below; none may reach a parsed part. */
  static final String[] SECRETS = {
    "tiger",
    "s3cret",
    "pa55",
    "hunter2",
    "scott",
    "dbuser",
    "x9",
    "pa(ss",
    "pa?ss",
    "p@ss",
    "pa/ss",
    "p(ss",
    "pa:ss",
    "pw(",
    "pw?",
    "pw@",
    "pw/",
    "pw:",
    "db2inst1",
    "pass#",
    "p(a",
    "p?a",
    "12?"
  };

  /** {url, target text, vendor, host, port, database}. */
  static List<Object[]> urls() {
    return Arrays.asList(
        // Oracle: SID, service, //, TNS descriptor, Easy Connect Plus, LDAP, alias, credentials.
        row(
            "jdbc:oracle:thin:@db1:1521:ORCL",
            "oracle://db1:1521/ORCL",
            "oracle",
            "db1",
            1521,
            "ORCL"),
        row(
            "jdbc:oracle:thin:scott/tiger@DB1.Example.com:1522/orclpdb1",
            "oracle://db1.example.com:1522/orclpdb1",
            "oracle",
            "db1.example.com",
            1522,
            "orclpdb1"),
        row("jdbc:oracle:thin:@//db1/svc", "oracle://db1:1521/svc", "oracle", "db1", 1521, "svc"),
        row(
            "jdbc:oracle:thin:scott/\"s3c@ret\"@(DESCRIPTION=(ADDRESS_LIST=(ADDRESS=(PROTOCOL=TCP)"
                + "(HOST = rac1)(PORT=1530))(ADDRESS=(PROTOCOL=TCP)(HOST=rac2)(PORT=1531)))"
                + "(CONNECT_DATA=(SERVICE_NAME=sales.example)))",
            "oracle://rac1:1530/sales.example",
            "oracle",
            "rac1",
            1530,
            "sales.example"),
        row(
            "jdbc:oracle:thin:@(DESCRIPTION=(ADDRESS=(HOST=h9)(PORT=1521))(CONNECT_DATA=(SID=XE)))",
            "oracle://h9:1521/XE",
            "oracle",
            "h9",
            1521,
            "XE"),
        row(
            "jdbc:oracle:thin:@tcps://db1:2484/svc?wallet_location=/home/scott/wallet&pa55=1",
            "oracle://db1:2484/svc",
            "oracle",
            "db1",
            2484,
            "svc"),
        row(
            "jdbc:oracle:thin:@ldap://ldap.example.com:389/orcl,cn=OracleContext,dc=com",
            "oracle://ldap.example.com:389/orcl",
            "oracle",
            "ldap.example.com",
            389,
            "orcl"),
        row(
            "jdbc:oracle:oci:scott/tiger@PRODDB",
            "oracle://proddb:1521/",
            "oracle",
            "proddb",
            1521,
            ""),
        // Tibero.
        row(
            "jdbc:tibero:thin:dbuser/hunter2@tb1.example:8629:tibero",
            "tibero://tb1.example:8629/tibero",
            "tibero",
            "tb1.example",
            8629,
            "tibero"),
        row("jdbc:tibero:thin:@tb2:tbdb", "tibero://tb2:8629/", "tibero", "tb2", 8629, ""),
        // MySQL: credentials, parameters, failover hosts, address= forms, IPv6, sub-schemes.
        row(
            "jdbc:mysql://scott:tiger@db:3307/shop?useSSL=true&password=s3cret",
            "mysql://db:3307/shop",
            "mysql",
            "db",
            3307,
            "shop"),
        row(
            "jdbc:mysql://primary:3306,replica:3306/shop?user=scott",
            "mysql://primary:3306/shop",
            "mysql",
            "primary",
            3306,
            "shop"),
        row(
            "jdbc:mysql://address=(host=m1)(port=3310)(user=scott)(password=s3c/ret)/shop",
            "mysql://m1:3310/shop",
            "mysql",
            "m1",
            3310,
            "shop"),
        row(
            "jdbc:mysql://(host=m2,port=3311,user=dbuser,password=hunter2)/inv",
            "mysql://m2:3311/inv",
            "mysql",
            "m2",
            3311,
            "inv"),
        row(
            "jdbc:mysql:loadbalance://lb1,lb2/app",
            "mysql://lb1:3306/app",
            "mysql",
            "lb1",
            3306,
            "app"),
        row(
            "jdbc:mysql://[::1]:3306/shop",
            "mysql://[::1]:3306/shop",
            "mysql",
            "[::1]",
            3306,
            "shop"),
        // MariaDB.
        row(
            "jdbc:mariadb://dbuser:pa55@maria.example/orders?sslMode=verify-full",
            "mariadb://maria.example:3306/orders",
            "mariadb",
            "maria.example",
            3306,
            "orders"),
        row(
            "jdbc:mariadb:failover://m1:3306,m2:3306/shop",
            "mariadb://m1:3306/shop",
            "mariadb",
            "m1",
            3306,
            "shop"),
        // PostgreSQL.
        row(
            "jdbc:postgresql://pg.example:5433/shop?user=scott&password=tiger&ssl=true",
            "postgresql://pg.example:5433/shop",
            "postgresql",
            "pg.example",
            5433,
            "shop"),
        row(
            "jdbc:postgresql://pg1,pg2:5434/shop?targetServerType=primary",
            "postgresql://pg1:5432/shop",
            "postgresql",
            "pg1",
            5432,
            "shop"),
        row(
            "jdbc:postgresql:shop",
            "postgresql://localhost:5432/shop",
            "postgresql",
            "localhost",
            5432,
            "shop"),
        row(
            "jdbc:postgresql://[2001:db8::7]:5432/shop",
            "postgresql://[2001:db8::7]:5432/shop",
            "postgresql",
            "[2001:db8::7]",
            5432,
            "shop"),
        // SQL Server and jTDS.
        row(
            "jdbc:sqlserver://ms1:1434;databaseName=Shop;user=sa;password=s3cret;encrypt=true",
            "sqlserver://ms1:1434/Shop",
            "sqlserver",
            "ms1",
            1434,
            "Shop"),
        row(
            "jdbc:sqlserver://ms2\\SQLEXPRESS;database=inv;integratedSecurity=false;password=pa55",
            "sqlserver://ms2\\sqlexpress:1433/inv",
            "sqlserver",
            "ms2\\sqlexpress",
            1433,
            "inv"),
        row(
            "jdbc:sqlserver://;serverName=ms3;portNumber=1500;databaseName=hr;password=hunter2",
            "sqlserver://ms3:1500/hr",
            "sqlserver",
            "ms3",
            1500,
            "hr"),
        row(
            "jdbc:jtds:sqlserver://ms4:1433/legacy;user=sa;password=x9",
            "sqlserver://ms4:1433/legacy",
            "sqlserver",
            "ms4",
            1433,
            "legacy"),
        // Altibase.
        row(
            "jdbc:Altibase://alt1:20300/mydb?user=sys&password=hunter2",
            "altibase://alt1:20300/mydb",
            "altibase",
            "alt1",
            20300,
            "mydb"),
        row(
            "jdbc:Altibase_7.1://alt2/mydb",
            "altibase://alt2:20300/mydb",
            "altibase",
            "alt2",
            20300,
            "mydb"),
        // CUBRID: positional user and password.
        row(
            "jdbc:cubrid:cub1:33000:demodb:dbuser:hunter2:?altHosts=cub2:33000",
            "cubrid://cub1:33000/demodb",
            "cubrid",
            "cub1",
            33000,
            "demodb"),
        row(
            "jdbc:CUBRID:cub3::demodb:::",
            "cubrid://cub3:33000/demodb",
            "cubrid",
            "cub3",
            33000,
            "demodb"),
        // DB2.
        row(
            "jdbc:db2://db2a:50001/SAMPLE:user=db2inst1;password=s3cret;currentSchema=X;",
            "db2://db2a:50001/SAMPLE",
            "db2",
            "db2a",
            50001,
            "SAMPLE"),
        row(
            "jdbc:db2:LOCALDB",
            "db2://localhost:50000/LOCALDB",
            "db2",
            "localhost",
            50000,
            "LOCALDB"),
        // H2: network and embedded forms; directories are dropped.
        row(
            "jdbc:h2:tcp://h2srv:9093/~/data/shop;USER=sa;PASSWORD=s3cret",
            "h2://h2srv:9093/shop",
            "h2",
            "h2srv",
            9093,
            "shop"),
        row(
            "jdbc:h2:tcp://localhost/mem:test",
            "h2://localhost:9092/mem:test",
            "h2",
            "localhost",
            9092,
            "mem:test"),
        row(
            "jdbc:h2:mem:shop;DB_CLOSE_DELAY=-1;PASSWORD=pa55",
            "h2:mem:shop",
            "h2",
            null,
            -1,
            "shop"),
        row(
            "jdbc:h2:file:/home/scott/db/app;AUTO_SERVER=TRUE",
            "h2:file:app",
            "h2",
            null,
            -1,
            "app"),
        row("jdbc:h2:~/test", "h2:file:test", "h2", null, -1, "test"),
        row("jdbc:h2:C:/data/x9/sample", "h2:file:sample", "h2", null, -1, "sample"),
        // Wrappers and other vendors.
        row(
            "jdbc:p6spy:mysql://scott:tiger@db:3306/shop",
            "mysql://db:3306/shop",
            "mysql",
            "db",
            3306,
            "shop"),
        row(
            "jdbc:otel:postgresql://pg:5432/shop?password=s3cret",
            "postgresql://pg:5432/shop",
            "postgresql",
            "pg",
            5432,
            "shop"),
        row(
            "jdbc:informix-sqli://ifx:1533/stores:INFORMIXSERVER=x;user=scott;password=tiger",
            "informix-sqli://ifx:1533/stores",
            "informix-sqli",
            "ifx",
            1533,
            "stores"),
        row("jdbc:sqlite:/home/scott/app.db", "sqlite:app.db", "sqlite", null, -1, "app.db"),
        // An '@' in a parameter value after the path is not a user info.
        row(
            "jdbc:mysql://db:3306/shop?user=admin@srv",
            "mysql://db:3306/shop",
            "mysql",
            "db",
            3306,
            "shop"),
        // Passwords holding ( ? @ / : : quoted in the Oracle form, raw in the authority forms; an
        // unquoted '?' in the Oracle form.
        oracleRow("jdbc:oracle:thin:scott/pa?ss@db.example.com:1521/svc"),
        oracleRow("jdbc:oracle:thin:scott/pass#?1@db.example.com:1521/svc"),
        oracleRow(
            "jdbc:oracle:thin:scott/pa?ss@(DESCRIPTION=(ADDRESS=(PROTOCOL=TCP)"
                + "(HOST=db.example.com)(PORT=1521))(CONNECT_DATA=(SERVICE_NAME=svc)))"),
        row(
            "jdbc:tibero:thin:dbuser/pa?ss@tb1.example:8629:tibero",
            "tibero://tb1.example:8629/tibero",
            "tibero",
            "tb1.example",
            8629,
            "tibero"),
        oracleRow("jdbc:oracle:thin:scott/\"pa(ss\"@db.example.com:1521/svc"),
        oracleRow("jdbc:oracle:thin:scott/\"pa?ss\"@db.example.com:1521/svc"),
        oracleRow("jdbc:oracle:thin:scott/\"p@ss(1\"@db.example.com:1521/svc"),
        oracleRow("jdbc:oracle:thin:scott/\"pa/ss\"@//db.example.com:1521/svc"),
        oracleRow("jdbc:oracle:thin:scott/\"pa:ss\"@db.example.com:1521/svc?connect_timeout=5"),
        oracleRow(
            "jdbc:oracle:thin:scott/\"p(ss\"@(DESCRIPTION=(ADDRESS=(PROTOCOL=TCP)"
                + "(HOST=db.example.com)(PORT=1521))(CONNECT_DATA=(SERVICE_NAME=svc)))"),
        oracleRow("jdbc:oracle:oci:\"scott\"/\"p@ss/(1?\"@db.example.com:1521/svc"),
        // An unquoted '?' in the password behind an Oracle user name (quoted, or with '#').
        oracleRow("jdbc:oracle:thin:\"scott\"/pa?ss@db.example.com:1521/svc"),
        oracleRow("jdbc:oracle:thin:c##scott/pa?ss@db.example.com:1521/svc"),
        row(
            "jdbc:oracle:thin:scott/\"pa:ss\"@db.example.com:1521:ORCL",
            "oracle://db.example.com:1521/ORCL",
            "oracle",
            "db.example.com",
            1521,
            "ORCL"),
        row(
            "jdbc:tibero:thin:dbuser/\"pa(ss\"@tb1.example:8629:tibero",
            "tibero://tb1.example:8629/tibero",
            "tibero",
            "tb1.example",
            8629,
            "tibero"),
        row(
            "jdbc:tibero:thin:dbuser/\"p@ss?1/x:y\"@tb1.example:8629:tibero",
            "tibero://tb1.example:8629/tibero",
            "tibero",
            "tb1.example",
            8629,
            "tibero"),
        row(
            "jdbc:mysql://scott:pw(1@db:3306/shop",
            "mysql://db:3306/shop",
            "mysql",
            "db",
            3306,
            "shop"),
        row(
            "jdbc:mysql://scott:pw?1@db:3306/shop?useSSL=true",
            "mysql://db:3306/shop",
            "mysql",
            "db",
            3306,
            "shop"),
        row(
            "jdbc:mariadb://dbuser:pw@1/2:3(4?5@maria.example:3307/orders",
            "mariadb://maria.example:3307/orders",
            "mariadb",
            "maria.example",
            3307,
            "orders"),
        row(
            "jdbc:postgresql://scott:pw/1:2@pg.example:5433/shop?ssl=true",
            "postgresql://pg.example:5433/shop",
            "postgresql",
            "pg.example",
            5433,
            "shop"),
        row(
            "jdbc:sqlserver://scott:pw(1?2/3:4@ms1:1434;databaseName=Shop",
            "sqlserver://ms1:1434/Shop",
            "sqlserver",
            "ms1",
            1434,
            "Shop"),
        row(
            "jdbc:Altibase://sys:pw:1/2(3?4@alt1:20300/mydb",
            "altibase://alt1:20300/mydb",
            "altibase",
            "alt1",
            20300,
            "mydb"),
        row(
            "jdbc:cubrid:cub1:33000:demodb:dbuser:pw(1@2/3?4:?altHosts=cub2:33000",
            "cubrid://cub1:33000/demodb",
            "cubrid",
            "cub1",
            33000,
            "demodb"),
        row(
            "jdbc:db2://db2inst1:pw(1?2/3:4@db2a:50001/SAMPLE:currentSchema=X;",
            "db2://db2a:50001/SAMPLE",
            "db2",
            "db2a",
            50001,
            "SAMPLE"));
  }

  /** An Oracle URL whose target is {@code oracle://db.example.com:1521/svc}. */
  private static Object[] oracleRow(String url) {
    return row(url, "oracle://db.example.com:1521/svc", "oracle", "db.example.com", 1521, "svc");
  }

  private static Object[] row(
      String url, String text, String vendor, String host, int port, String database) {
    return new Object[] {url, text, vendor, host, port, database};
  }

  @ParameterizedTest(name = "T-AG-035 {1}")
  @MethodSource("urls")
  void parses(String url, String text, String vendor, String host, int port, String database) {
    JdbcUrlParser.Target target = JdbcUrlParser.parse(url);
    assertThat(target.text()).isEqualTo(text);
    assertThat(JdbcUrlParser.targetText(url)).isEqualTo(text);
    assertThat(target.vendor()).isEqualTo(vendor);
    assertThat(target.host()).isEqualTo(host);
    assertThat(target.port()).isEqualTo(port);
    assertThat(target.database()).isEqualTo(database);
    assertThat(target.embedded()).isEqualTo(host == null);
    for (String secret : SECRETS) {
      assertThat(target.text()).as("credential %s", secret).doesNotContain(secret);
    }
    assertThat(target.text()).doesNotContain("password").doesNotContain("?").doesNotContain(";");
  }

  @Test
  @DisplayName("T-AG-035 the nine vendors of 10 §6.3 and H2 are all recognized")
  void everyVendor() {
    String[] vendors = {
      JdbcUrlParser.ORACLE,
      JdbcUrlParser.MYSQL,
      JdbcUrlParser.MARIADB,
      JdbcUrlParser.POSTGRESQL,
      JdbcUrlParser.SQLSERVER,
      JdbcUrlParser.TIBERO,
      JdbcUrlParser.ALTIBASE,
      JdbcUrlParser.CUBRID,
      JdbcUrlParser.DB2,
      JdbcUrlParser.H2
    };
    java.util.Set<String> seen = new java.util.HashSet<String>();
    for (Object[] row : urls()) {
      seen.add((String) row[2]);
    }
    assertThat(seen).contains(vendors);
  }

  @Test
  @DisplayName(
      "unreadable URLs give 'unknown' and never any part of the input; parts are cut at 128"
          + " characters")
  void unreadable() {
    for (String url :
        new String[] {
          null, "", "mysql://scott:tiger@db/shop", "jdbc:", "jdbc:x", "jdbc::y", "not a url tiger"
        }) {
      assertThat(JdbcUrlParser.targetText(url)).as(String.valueOf(url)).isEqualTo("unknown");
    }
    StringBuilder longHost = new StringBuilder();
    for (int i = 0; i < 300; i++) {
      longHost.append('h');
    }
    JdbcUrlParser.Target target =
        JdbcUrlParser.parse("jdbc:postgresql://" + longHost + ":5432/shop");
    assertThat(target.host()).hasSize(JdbcUrlParser.MAX_PART_CHARS);
    assertThat(JdbcUrlParser.parse("jdbc:mysql://db:99999/shop").port())
        .as("an invalid port is the default")
        .isEqualTo(3306);
  }

  @Test
  @DisplayName(
      "T-AG-035b fail closed: an unterminated quoted Oracle or Tibero password, an unquoted '('"
          + " before the Oracle '@', an Oracle '?' before the '@' with an '=' between or no Oracle"
          + " user name in front, an authority '@' in front of the path that ends no user info, or"
          + " a part that still holds a credential character, leaves the vendor alone")
  void credentialsFailClosed() {
    String[][] cases = {
      {"jdbc:oracle:thin:scott/\"pass@db:1521/svc", "oracle"},
      {"jdbc:oracle:thin:scott/\"pa(ss@(DESCRIPTION=(ADDRESS=(HOST=db)(PORT=1521)))", "oracle"},
      {"jdbc:tibero:thin:dbuser/\"pass@tb1:8629:tibero", "tibero"},
      // Read past the credential syntax: a quote or '@' left in the host or the database.
      {"jdbc:oracle:thin:@\"scott@db\":1521:svc", "oracle"},
      {"jdbc:db2://db2inst1:pw=1@db2a:50001/SAMPLE", "db2"},
      {"jdbc:postgresql:\"scott@shop\"", "postgresql"},
      // An unquoted '(' before the Oracle '@'.
      {"jdbc:oracle:thin:scott/p(a?ss@db:1521/svc", "oracle"},
      // No leading '@': a '?' before the '@' with an '=' between them, or with no Oracle user
      // name in front, may start parameters whose value holds the '@' (WO-13 N-1).
      {"jdbc:oracle:thin:db:1521/svc?password=s3c@ret", "oracle"},
      {"jdbc:oracle:thin:db.example.com:1521/svc?x=a@b", "oracle"},
      {"jdbc:oracle:thin:db/svc?x=a@b", "oracle"},
      {"jdbc:tibero:thin:tb1.example:8629:tibero?pw=a@evil", "tibero"},
      {"jdbc:oracle:thin:scott/p?a=b@db:1521/svc", "oracle"},
      {"jdbc:oracle:thin:db:1521/svc?x@ret:1521/svc", "oracle"},
      // A '?' or ';' before an '=' in an authority password: no '@' ends a user info, one stands
      // in front of the path.
      {"jdbc:mysql://scott:p?a=b@db:3306/shop", "mysql"},
      {"jdbc:mysql://scott:12?a=b@db:3306/shop", "mysql"},
      {"jdbc:postgresql://scott:p?x=y@pg:5432/shop", "postgresql"},
      {"jdbc:mariadb://scott:p;a=b@m1:3306/shop", "mariadb"},
      // Without a path an '@' in a parameter value cannot be told from a password (intended).
      {"jdbc:mysql://h1:3306?user=a@b", "mysql"},
    };
    for (String[] c : cases) {
      JdbcUrlParser.Target target = JdbcUrlParser.parse(c[0]);
      assertThat(target.text()).as(c[0]).isEqualTo(c[1]);
      assertThat(target.vendor()).isEqualTo(c[1]);
      assertThat(target.host()).isNull();
      assertThat(target.port()).isEqualTo(-1);
      assertThat(target.database()).isEmpty();
      for (String secret :
          new String[] {
            "scott",
            "pass",
            "pa(ss",
            "dbuser",
            "pw=",
            "db2inst1",
            "pa?ss",
            "pass#",
            "p(a",
            "p?a",
            "12?",
            "pa",
            "s3c",
            "ret",
            "evil"
          }) {
        assertThat(target.text()).as(c[0]).doesNotContain(secret);
      }
    }
  }

  @Test
  @Tag("req:F-A03")
  @DisplayName(
      "DbIdentity: the DB_TARGET dictionary text of a URL, the same id for the same target"
          + " whatever the credentials, and the SQL dialect per id")
  void dbIdentity() {
    Dictionary dictionary = new Dictionary();
    DbIdentity identity = new DbIdentity(dictionary);
    long a = identity.id("jdbc:mysql://scott:tiger@db:3306/shop?useSSL=true");
    long b = identity.id("jdbc:mysql://dbuser:hunter2@db:3306/shop");
    assertThat(a).isEqualTo(b).isEqualTo(Dictionary.hash64("mysql://db:3306/shop"));
    assertThat(dictionary.text(a)).isEqualTo("mysql://db:3306/shop");
    assertThat(dictionary.kind(a)).isEqualTo(DictKind.DB_TARGET);
    assertThat(identity.dialectOf(a)).isEqualTo(SqlDialect.MYSQL);
    long pg = identity.id("jdbc:postgresql://pg/shop");
    assertThat(identity.dialectOf(pg)).isEqualTo(SqlDialect.POSTGRESQL);
    assertThat(identity.dialectOf(0)).isEqualTo(SqlDialect.UNKNOWN);
    assertThat(identity.dialectOf(12345L)).isEqualTo(SqlDialect.UNKNOWN);
    assertThat(identity.id(null)).isZero();
    for (int i = 0; i < DbIdentity.MAX_TARGETS + 10; i++) {
      identity.id("jdbc:postgresql://pg" + i + "/shop");
    }
    assertThat(identity.size()).isLessThanOrEqualTo(DbIdentity.MAX_TARGETS);
  }
}
