/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.api.openapi;

import io.argos.server.auth.AppAccessPolicy;
import io.argos.server.auth.AuthConfiguration;
import io.argos.server.auth.HandlerRoleSource;
import io.argos.server.auth.HandlerRoles;
import io.argos.server.auth.RoleMatrix;
import io.argos.server.auth.session.PublicOrigin;
import io.argos.server.bootstrap.StartupSequence;
import io.argos.server.config.ArgosProperties;
import io.argos.server.config.ServerBuild;
import io.argos.server.testing.auth.ApiResponse;
import io.argos.server.testing.auth.ApiTestClient;
import io.argos.server.web.security.ApiFilter;
import io.argos.servertest.security.HandlerReachedMarker;
import io.argos.servertest.security.HeaderAuthentication;
import io.argos.servertest.security.matrix.OpenApiSpec;
import io.argos.servertest.security.matrix.PermissionMatrix;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.mockito.Mockito;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.beans.factory.config.DependencyDescriptor;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.ContextAnnotationAutowireCandidateResolver;
import org.springframework.context.annotation.FilterType;
import org.springframework.context.annotation.Import;
import org.springframework.core.type.AnnotationMetadata;
import org.springframework.core.type.classreading.MetadataReader;
import org.springframework.core.type.classreading.MetadataReaderFactory;
import org.springframework.core.type.filter.AssignableTypeFilter;
import org.springframework.core.type.filter.TypeFilter;
import org.springframework.stereotype.Component;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * The web layer of the server on loopback, without any database, for the contract suite ({@code
 * check} needs no Docker, 18 §2.1, dev-setup §3): real Tomcat, every filter, Spring Security,
 * Spring MVC, every controller of the server, the role interceptor, {@code RoleMatrix} and
 * springdoc.
 *
 * <p>What the scan loads ({@link WebLayer}): every {@code @Controller} of {@code io.argos.server}
 * in any package ({@code @RestController} among them), every {@code @RestControllerAdvice}, {@code
 * ApiFilter} and {@code HandlerRoleSource}, the components of the {@code web} and {@code api}
 * packages and the few support beans it names. {@code
 * PermissionMatrixContractTest#everyControllerIsLoaded} fails when a production controller is
 * missing. Everything a controller or filter needs beyond that (services, repositories) is a
 * Mockito stub ({@link StubsForMissingBeans}), created on demand, so an endpoint that a later work
 * order adds in a controller is in the document and in the matrix without any change here. A
 * handler registered without a controller (a functional route or an own handler mapping) is not
 * loaded; the server has none. This suite therefore asserts authorisation and the document only,
 * never what a handler does with data: that is the job of the integration and security suites,
 * which use real PostgreSQL and ClickHouse (the session run of the matrix among them). Identities
 * come from {@link HeaderAuthentication}.
 */
public final class ContractServer implements AutoCloseable {

  private static final SecureRandom RANDOM = new SecureRandom();

  private final ConfigurableApplicationContext context;
  private final String baseUrl;

  private ContractServer(ConfigurableApplicationContext context, String baseUrl) {
    this.context = context;
    this.baseUrl = baseUrl;
  }

  /**
   * Starts the web layer.
   *
   * @param extraSources test-only controllers and configuration
   * @throws RuntimeException when the application does not start, e.g. {@code RoleMatrix} refuses a
   *     handler
   */
  public static ContractServer start(Class<?>... extraSources) {
    int port = freePort();
    String baseUrl = "http://127.0.0.1:" + port;
    Map<String, Object> properties = new LinkedHashMap<>();
    properties.put("server.address", "127.0.0.1");
    properties.put("server.port", Integer.toString(port));
    properties.put("management.server.port", "-1");
    properties.put("argos.web.public-url", baseUrl);
    // Never connected: the data sources are not part of this application.
    properties.put("argos.postgres.url", "jdbc:postgresql://127.0.0.1:1/unused");
    // Generated requests must meet the role layer, not the limits.
    properties.put("argos.ratelimit.login-per-ip-per-minute", "1000000");
    properties.put("argos.ratelimit.api-per-user-per-second", "1000000");
    properties.put("argos.ratelimit.api-burst-per-user", "1000000");
    properties.put("argos.ratelimit.export-per-user-per-minute", "1000000");
    properties.put("argos.ratelimit.csp-report-per-ip-per-minute", "1000000");
    List<Class<?>> sources = new ArrayList<>();
    sources.add(WebLayerApplication.class);
    sources.addAll(List.of(extraSources));
    SpringApplication app = new SpringApplication(sources.toArray(Class<?>[]::new));
    app.setDefaultProperties(properties);
    return new ContractServer(app.run(), baseUrl);
  }

  /**
   * A client with a CSRF cookie and a double-submit token, at the server's origin, as an identity:
   * {@code role} is the {@value HeaderAuthentication#ROLE_HEADER} value, null for anonymous.
   */
  public ApiTestClient client(String role) {
    ApiTestClient client = ApiTestClient.of(baseUrl);
    byte[] token = new byte[32];
    RANDOM.nextBytes(token);
    client
        .cookies()
        .put(
            ApiTestClient.CSRF_COOKIE,
            Base64.getUrlEncoder().withoutPadding().encodeToString(token),
            "/");
    if (role != null) {
      client.header(HeaderAuthentication.ROLE_HEADER, role);
    }
    return client;
  }

  /** The OpenAPI document as a viewer reads it. */
  public OpenApiSpec spec() {
    ApiResponse response = client("viewer").get("/api/v1/openapi");
    if (response.status() != 200) {
      throw new IllegalStateException("No OpenAPI document: " + response);
    }
    return OpenApiSpec.parse(response.body());
  }

  /**
   * The operations whose handler may refuse a generated request here: those of {@link
   * PermissionMatrix#ownCredentials()}, plus the ones that refuse only because a stub answers:
   * {@code GET /auth/me} finds no user record behind the test identity. The session run of the
   * matrix has real records, and there the second kind stays strict.
   */
  public static Set<String> handlerRefusals() {
    Set<String> refusals = new HashSet<>(PermissionMatrix.ownCredentials());
    refusals.add("GET /api/v1/auth/me");
    return refusals;
  }

  /** The application context. */
  public ConfigurableApplicationContext context() {
    return context;
  }

  @Override
  public void close() {
    context.close();
  }

  private static int freePort() {
    try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
      return socket.getLocalPort();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  /**
   * The application of this server. Not a {@code @Component}: this suite is the only one that sees
   * the class.
   */
  @EnableAutoConfiguration(
      excludeName = {
        "org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration",
        "org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration"
      })
  @EnableConfigurationProperties(ArgosProperties.class)
  @ComponentScan(
      basePackages = "io.argos.server",
      useDefaultFilters = false,
      includeFilters = @ComponentScan.Filter(type = FilterType.CUSTOM, classes = WebLayer.class))
  @Import({
    StartupSequence.class,
    HeaderAuthentication.class,
    HandlerReachedMarker.class,
    StubsForMissingBeans.class
  })
  public static class WebLayerApplication {

    /** The CSRF filter compares against the real origin: never a stub, or every POST is 403. */
    @Bean
    PublicOrigin publicOrigin(ArgosProperties properties) {
      return new PublicOrigin(properties);
    }
  }

  /**
   * What the scan picks up: every controller ({@code @Controller}, meta-annotations included, in
   * any package) and API filter, and the web layer around them.
   */
  public static final class WebLayer implements TypeFilter {

    private static final Set<String> SUPPORT =
        Set.of(
            AppAccessPolicy.class.getName(),
            AuthConfiguration.class.getName(),
            HandlerRoles.class.getName(),
            RoleMatrix.class.getName(),
            ServerBuild.class.getName());
    private static final TypeFilter API_FILTER = new AssignableTypeFilter(ApiFilter.class);
    private static final TypeFilter ROLE_SOURCE = new AssignableTypeFilter(HandlerRoleSource.class);

    @Override
    public boolean match(MetadataReader reader, MetadataReaderFactory factory) throws IOException {
      AnnotationMetadata metadata = reader.getAnnotationMetadata();
      if (!metadata.isAnnotated(Component.class.getName())) {
        return false;
      }
      String name = metadata.getClassName();
      if (name.contains("ContractTest$")) {
        // A test-only controller of this suite: only the test that passes it as a source has it.
        return false;
      }
      return name.startsWith("io.argos.server.web.")
          || name.startsWith("io.argos.server.api.")
          || SUPPORT.contains(name)
          // Meta-annotations count: @RestController, and a plain @Controller in any package.
          || metadata.isAnnotated(Controller.class.getName())
          || metadata.isAnnotated(RestControllerAdvice.class.getName())
          || API_FILTER.match(reader, factory)
          || ROLE_SOURCE.match(reader, factory);
    }
  }

  /**
   * Answers a dependency on an Argos type that has no bean with a Mockito stub: a method of a stub
   * returns null, an empty collection or false, so a handler reports a failure of its own or fails
   * with a 500, which is not what the matrix looks at. The role layer runs before any of this.
   */
  public static final class StubsForMissingBeans implements BeanFactoryPostProcessor {

    private static final Map<Class<?>, Object> STUBS = new ConcurrentHashMap<>();

    /** The stubbed types of every server started in this JVM. */
    public static Set<Class<?>> stubbedTypes() {
      return Set.copyOf(STUBS.keySet());
    }

    @Override
    public void postProcessBeanFactory(ConfigurableListableBeanFactory beanFactory)
        throws BeansException {
      DefaultListableBeanFactory factory = (DefaultListableBeanFactory) beanFactory;
      factory.setAutowireCandidateResolver(
          new ContextAnnotationAutowireCandidateResolver() {
            @Override
            public Object getLazyResolutionProxyIfNecessary(
                DependencyDescriptor descriptor, String beanName) {
              Object lazy = super.getLazyResolutionProxyIfNecessary(descriptor, beanName);
              if (lazy != null) {
                return lazy;
              }
              Class<?> type = descriptor.getDependencyType();
              boolean argosType = type.getName().startsWith("io.argos.server.");
              if (!argosType || type.isEnum()) {
                return null;
              }
              if (factory.getBeanNamesForType(type, true, false).length > 0) {
                return null;
              }
              return STUBS.computeIfAbsent(type, Mockito::mock);
            }
          });
    }
  }
}
