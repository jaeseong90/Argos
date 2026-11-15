/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.servertest.security.matrix;

import io.argos.server.auth.Role;
import io.argos.server.testing.auth.ApiResponse;
import io.argos.servertest.security.HandlerReachedMarker;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;

/**
 * The permission matrix generated from the OpenAPI document (T-SEC-49, T-API-010, 16 §7.10, 14
 * §7.1): one request per operation and identity (anonymous, viewer, operator, admin). What is
 * expected comes from the operation's {@code x-argos-role} only, never from a per-endpoint table,
 * so an endpoint that a later work order adds is covered as soon as it is in the document:
 *
 * <table>
 *   <caption>Expectations</caption>
 *   <tr><th>Identity</th><th>Operation</th><th>Expected</th></tr>
 *   <tr><td>any</td><td>{@code public}</td><td>handler reached, not 401/403</td></tr>
 *   <tr><td>anonymous</td><td>not public</td><td>401, handler not reached</td></tr>
 *   <tr><td>role includes it</td><td>not public</td><td>handler reached, not 401/403</td></tr>
 *   <tr><td>lower role</td><td>not public</td><td>403 {@code forbidden}, handler not
 *       reached</td></tr>
 * </table>
 *
 * <p>An allowed cell is also "2xx or a 4xx other than 403" (14 §7.1): a 5xx there fails the run
 * under {@link ServerErrors#FAIL}, the policy of a run against real handlers. {@link
 * ServerErrors#TOLERATE} is for a web layer whose handlers run on stubs, where a 500 says nothing
 * about the role layer; each run states its policy in its report.
 *
 * <p>"Handler reached" is {@link HandlerReachedMarker}. One exception, listed with its reason in
 * {@value #OWN_CREDENTIALS_RESOURCE}: a handler that checks a credential of the request itself
 * (login: the password; refresh: the refresh cookie; setup: the setup token) may answer 401 or 403
 * to the generated values. It must still have been reached, so the answer is the handler's and not
 * the role layer's. An entry for an operation that no longer exists fails the run.
 */
public final class PermissionMatrix {

  /** Operations whose handler checks a credential of the request itself. */
  public static final String OWN_CREDENTIALS_RESOURCE = "permission-matrix-own-credentials.txt";

  /** The identities, in table order; {@code ANONYMOUS} has no role. */
  public enum Identity {
    ANONYMOUS(null),
    VIEWER(Role.VIEWER),
    OPERATOR(Role.OPERATOR),
    ADMIN(Role.ADMIN);

    private final Role role;

    Identity(Role role) {
      this.role = role;
    }

    /** The user role, null for {@link #ANONYMOUS}. */
    public Role role() {
      return role;
    }

    /** The column title. */
    public String title() {
      return name().toLowerCase(Locale.ROOT);
    }
  }

  /** Sends a generated request as one identity. */
  @FunctionalInterface
  public interface Sender {

    /** The response to {@code request}; handles whatever the identity needs (cookies, login). */
    ApiResponse send(Identity identity, RequestFactory.Request request);
  }

  /** What a 5xx in an allowed cell means. */
  public enum ServerErrors {
    /**
     * A failure: an allowed request is answered with 2xx or a 4xx other than 403 (14 §7.1). For
     * runs against real handlers and real databases.
     */
    FAIL,
    /**
     * Not judged: the handlers run on stubs, whose answers can make a handler fail with a 500 that
     * says nothing about the role layer, which "handler reached" judges on its own.
     */
    TOLERATE
  }

  /** What the role layer has to do for an identity at an operation. */
  public enum Expected {
    ALLOW,
    DENY_401,
    DENY_403;

    /** The expectation of {@code identity} at an operation of role {@code required}. */
    public static Expected of(Identity identity, Role required) {
      if (required == Role.PUBLIC) {
        return ALLOW;
      }
      if (identity.role() == null) {
        return DENY_401;
      }
      return identity.role().includes(required) ? ALLOW : DENY_403;
    }
  }

  /**
   * One cell of the table.
   *
   * @param expected what the role layer has to do
   * @param status the status received
   * @param reached whether the handler was reached
   * @param problem what is wrong, or null
   */
  public record Cell(Expected expected, int status, boolean reached, String problem) {}

  /**
   * One row of the table.
   *
   * @param operation the operation
   * @param role its {@code x-argos-role}
   * @param cells per identity
   */
  public record Row(OpenApiSpec.Operation operation, String role, Map<Identity, Cell> cells) {}

  /**
   * The outcome of a run.
   *
   * @param rows one row per operation
   * @param failures one line per wrong cell or wrong operation
   * @param serverErrors what a 5xx in an allowed cell meant in this run
   */
  public record Result(List<Row> rows, List<String> failures, ServerErrors serverErrors) {

    /** Writes the endpoint x role table. */
    public void writeReport(Path file, String title) {
      StringBuilder out = new StringBuilder();
      out.append("# ").append(title).append("\n\n");
      out.append(
          "Generated from the OpenAPI document (T-SEC-49, T-API-010). Each cell: `allowed` or"
              + " `denied` as `x-argos-role` demands, then the status received.\n\n");
      String serverErrorPolicy =
          serverErrors == ServerErrors.FAIL
              ? "A 5xx in an allowed cell is a failure (14 §7.1: allowed is 2xx or a 4xx other than"
                  + " 403)."
              : "A 5xx in an allowed cell is not judged in this run: the handlers run on stubs. The"
                  + " real-session run of the security suite judges it.";
      out.append(serverErrorPolicy).append("\n\n");
      out.append("| Operation | x-argos-role | x-argos-audit |");
      for (Identity identity : Identity.values()) {
        out.append(' ').append(identity.title()).append(" |");
      }
      out.append("\n|---|---|---|---|---|---|---|\n");
      for (Row row : rows) {
        out.append("| `")
            .append(row.operation().key())
            .append("` | ")
            .append(row.role())
            .append(" | ")
            .append(row.operation().audit().orElse("-"))
            .append(" |");
        for (Identity identity : Identity.values()) {
          Cell cell = row.cells().get(identity);
          out.append(' ').append(cell == null ? "-" : describe(cell)).append(" |");
        }
        out.append('\n');
      }
      out.append("\nOperations: ")
          .append(rows.size())
          .append(". Failures: ")
          .append(failures.size())
          .append(".\n");
      failures.forEach(failure -> out.append("- ").append(failure).append('\n'));
      try {
        Files.createDirectories(file.getParent());
        Files.writeString(file, out.toString(), StandardCharsets.UTF_8);
      } catch (IOException e) {
        throw new UncheckedIOException(e);
      }
    }

    private static String describe(Cell cell) {
      String word = cell.expected() == Expected.ALLOW ? "allowed" : "denied";
      return word + " " + cell.status() + (cell.problem() == null ? "" : " (WRONG)");
    }
  }

  private PermissionMatrix() {}

  /**
   * Runs the matrix.
   *
   * @param spec the document
   * @param sender sends a request as an identity
   * @param ownCredentials operations whose handler checks a credential itself ({@link
   *     #ownCredentials()})
   * @param serverErrors what a 5xx in an allowed cell means
   * @return the table and every failure
   */
  public static Result run(
      OpenApiSpec spec, Sender sender, Set<String> ownCredentials, ServerErrors serverErrors) {
    RequestFactory requests = new RequestFactory(spec);
    List<Row> rows = new ArrayList<>();
    List<String> failures = new ArrayList<>();
    Set<String> known = new java.util.HashSet<>();
    for (OpenApiSpec.Operation operation : spec.operations()) {
      known.add(operation.key());
      Optional<Role> required = role(operation);
      if (required.isEmpty()) {
        failures.add(operation.key() + ": no valid x-argos-role");
        rows.add(new Row(operation, operation.role().orElse("(none)"), Map.of()));
        continue;
      }
      boolean own = ownCredentials.contains(operation.key());
      Map<Identity, Cell> cells = new LinkedHashMap<>();
      for (Identity identity : Identity.values()) {
        ApiResponse response = sender.send(identity, requests.request(operation));
        Cell cell = judge(Expected.of(identity, required.get()), response, own, serverErrors);
        cells.put(identity, cell);
        if (cell.problem() != null) {
          failures.add(operation.key() + " as " + identity.title() + ": " + cell.problem());
        }
      }
      rows.add(new Row(operation, required.get().extensionValue(), cells));
    }
    for (String entry : ownCredentials) {
      if (!known.contains(entry)) {
        failures.add(OWN_CREDENTIALS_RESOURCE + " lists an unknown operation: " + entry);
      }
    }
    return new Result(rows, failures, serverErrors);
  }

  /** The decision for one response; {@link Cell#problem()} is null when it is right. */
  static Cell judge(
      Expected expected, ApiResponse response, boolean ownCredential, ServerErrors serverErrors) {
    int status = response.status();
    boolean reached = response.header(HandlerReachedMarker.HEADER).isPresent();
    String code = response.errorCode().orElse("-");
    String problem = null;
    switch (expected) {
      case ALLOW -> {
        if (!reached) {
          problem = "must reach the handler, got " + status + " " + code + " before it";
        } else if ((status == 401 || status == 403) && !ownCredential) {
          problem = "must not be " + status + " " + code;
        } else if (status >= 500 && serverErrors == ServerErrors.FAIL) {
          problem = "must not be 5xx, got " + status + " " + code;
        }
      }
      case DENY_401 -> {
        if (status != 401) {
          problem = "must be 401, got " + status + " " + code;
        } else if (reached) {
          problem = "401 came from the handler, the role check let it through";
        }
      }
      case DENY_403 -> {
        if (status != 403 || !"forbidden".equals(code)) {
          problem = "must be 403 forbidden, got " + status + " " + code;
        } else if (reached) {
          problem = "403 came from the handler, the role check let it through";
        }
      }
    }
    return new Cell(expected, status, reached, problem);
  }

  /** The role an operation requires, empty when {@code x-argos-role} is missing or unknown. */
  public static Optional<Role> role(OpenApiSpec.Operation operation) {
    return operation
        .role()
        .flatMap(
            value -> {
              for (Role role : Role.values()) {
                if (role.extensionValue().equals(value)) {
                  return Optional.of(role);
                }
              }
              return Optional.empty();
            });
  }

  /** The operations of {@value #OWN_CREDENTIALS_RESOURCE}, with their reasons, sorted. */
  public static Map<String, String> ownCredentialReasons() {
    Map<String, String> entries = new TreeMap<>();
    try (InputStream in = PermissionMatrix.class.getResourceAsStream(OWN_CREDENTIALS_RESOURCE);
        BufferedReader reader =
            new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
      String line;
      while ((line = reader.readLine()) != null) {
        String entry = line.strip();
        if (entry.isEmpty() || entry.startsWith("#")) {
          continue;
        }
        int tab = entry.indexOf('\t');
        entries.put(entry.substring(0, tab), entry.substring(tab + 1).strip());
      }
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    return entries;
  }

  /** The operations of {@value #OWN_CREDENTIALS_RESOURCE}. */
  public static Set<String> ownCredentials() {
    return ownCredentialReasons().keySet();
  }
}
