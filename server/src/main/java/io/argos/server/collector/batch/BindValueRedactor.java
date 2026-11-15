/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.collector.batch;

import io.argos.proto.v1.ProfileBatch;
import io.argos.proto.v1.ProfileStep;
import io.argos.proto.v1.StepType;
import io.argos.proto.v1.TransactionProfile;

/**
 * ADR-0010 C-148: until the envelope encryption of {@code detail_enc} exists (WO-71, 16 §6.7, 02
 * §5.2), the server does not keep the bind parameter values of a session whose {@code
 * AgentHello.sql_param_mode} is {@code SQL_PARAM_FULL}. The {@code detail} of every step that
 * carries them is cleared at receipt, before the WAL append, so the values never reach the disk
 * (fail closed). The ack is unchanged: the steps stay, only their detail is gone (not an item
 * drop).
 *
 * <p>The steps that carry bind values (10 §6.3 "바인드 파라미터", proto/README.md {@code
 * profile_step.detail}): the {@code SQL} step, whose detail is the parameter string of {@code
 * execute*} and of the first parameter set of {@code executeBatch} when the mode is not {@code
 * masked}; and, failing closed, a {@code SUMMARY} step that summarizes SQL steps ({@code status} =
 * the C-1 number of {@code SQL}, ADR-0010 C-141), although the agent sends no detail on summary
 * steps. Other step types (connection wait, commit, rollback, outgoing HTTP call, method, async,
 * error, stack, message) carry no bind values and keep their detail.
 *
 * <p>Stateless and thread-safe.
 */
public final class BindValueRedactor {

  /** Label of {@code argos_collector_redacted_total{reason}} for the cleared steps. */
  public static final String FULL_DETAIL_UNENCRYPTED = "full_detail_unencrypted";

  private BindValueRedactor() {}

  /**
   * {@code batch} with the {@code detail} of every step that carries bind values cleared, and the
   * number of steps whose non-empty detail was cleared. The same instance when nothing changes.
   */
  public static Redacted redact(ProfileBatch batch) {
    int cleared = 0;
    for (TransactionProfile p : batch.getProfilesList()) {
      for (ProfileStep s : p.getStepsList()) {
        if (carriesBindValues(s) && !s.getDetail().isEmpty()) {
          cleared++;
        }
      }
    }
    if (cleared == 0) {
      return new Redacted(batch, 0);
    }
    ProfileBatch.Builder out = batch.toBuilder();
    for (int i = 0; i < out.getProfilesCount(); i++) {
      TransactionProfile p = out.getProfiles(i);
      boolean touched = false;
      TransactionProfile.Builder profile = null;
      for (int j = 0; j < p.getStepsCount(); j++) {
        ProfileStep s = p.getSteps(j);
        if (carriesBindValues(s) && !s.getDetail().isEmpty()) {
          if (!touched) {
            profile = p.toBuilder();
            touched = true;
          }
          profile.setSteps(j, s.toBuilder().clearDetail());
        }
      }
      if (touched) {
        out.setProfiles(i, profile);
      }
    }
    return new Redacted(out.build(), cleared);
  }

  /** Whether the detail of {@code step} may hold bind parameter values (see the class comment). */
  public static boolean carriesBindValues(ProfileStep step) {
    return step.getType() == StepType.SQL
        || (step.getType() == StepType.SUMMARY && step.getStatus() == StepType.SQL_VALUE);
  }

  /**
   * The outcome of {@link #redact}.
   *
   * @param batch the batch to store
   * @param cleared steps whose detail was cleared
   */
  public record Redacted(ProfileBatch batch, int cleared) {}
}
