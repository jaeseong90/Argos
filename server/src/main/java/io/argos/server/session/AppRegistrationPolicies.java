/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.session;

import io.argos.server.storage.postgres.app.AppRegistrationPolicyRepository;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * The registration policy of an agent token's home app for the Hello's automatic registration
 * (ADR-0010 C-231 (7), 12 §4.4): {@code apps.auto_register} TRUE {@code auto}, FALSE {@code
 * approval}, NULL none, so {@code argos.agent.auto-register.<env>} applies (V013). Read only when a
 * Hello names an unknown (name, env), which needs PostgreSQL anyway (the variant is created there);
 * connects itself to the {@link HelloService} of the collector.
 */
@Component
public final class AppRegistrationPolicies implements HelloService.RegistrationPolicySource {

  private final AppRegistrationPolicyRepository repository;

  /** The source over {@code repository}, connected to the Hello when there is one. */
  public AppRegistrationPolicies(
      AppRegistrationPolicyRepository repository, ObjectProvider<HelloService> hello) {
    this.repository = repository;
    HelloService h = hello.getIfAvailable();
    if (h != null) {
      h.registrationPolicies(this);
    }
  }

  @Override
  public @Nullable Boolean autoRegister(int appId) {
    return repository.autoRegister(appId);
  }
}
