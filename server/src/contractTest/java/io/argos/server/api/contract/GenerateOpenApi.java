/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.api.contract;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * {@code ./gradlew :server:generateOpenApi}: writes the committed OpenAPI document ({@link
 * OpenApiDocument}) to the path of its argument (the build passes {@code docs/api/openapi.yaml}).
 * Starts the web layer on loopback without databases; needs no Docker.
 */
public final class GenerateOpenApi {

  private GenerateOpenApi() {}

  /**
   * Generates the document.
   *
   * @param args the output file
   */
  public static void main(String[] args) throws IOException {
    if (args.length != 1) {
      throw new IllegalArgumentException("usage: GenerateOpenApi <output file>");
    }
    Path out = Path.of(args[0]);
    String yaml = OpenApiDocument.generate();
    Path parent = out.toAbsolutePath().getParent();
    if (parent != null) {
      Files.createDirectories(parent);
    }
    Files.writeString(out, yaml, StandardCharsets.UTF_8);
    // The web layer's threads end with its context; nothing else keeps the JVM alive.
    System.out.println("generateOpenApi: wrote " + out + " (" + yaml.length() + " characters)");
  }
}
