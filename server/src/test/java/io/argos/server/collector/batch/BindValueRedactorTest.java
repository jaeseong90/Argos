/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.collector.batch;

import static org.assertj.core.api.Assertions.assertThat;

import io.argos.proto.v1.ProfileBatch;
import io.argos.proto.v1.ProfileStep;
import io.argos.proto.v1.StepType;
import io.argos.proto.v1.TransactionProfile;
import java.util.EnumSet;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * ADR-0010 C-148: which step details carry bind parameter values (10 §6.3, proto/README.md) and are
 * cleared for a {@code SQL_PARAM_FULL} session; every other field and step stays as received.
 */
@Tag("req:C-148")
class BindValueRedactorTest {

  private static ProfileStep step(int seq, StepType type, int status, String detail) {
    return ProfileStep.newBuilder()
        .setSeq(seq)
        .setType(type)
        .setStatus(status)
        .setDetail(detail)
        .setElapsedUs(1_000 + seq)
        .setRepeatCount(1)
        .build();
  }

  @Test
  @DisplayName(
      "C-148 only SQL steps and SUMMARY steps of SQL steps have their detail cleared; the other step"
          + " types and every other field stay; the cleared count excludes empty details")
  void clearsOnlyBindValueSteps() {
    Set<StepType> carrying = EnumSet.noneOf(StepType.class);
    TransactionProfile.Builder p = TransactionProfile.newBuilder().setTxId(1);
    int seq = 0;
    for (StepType type : StepType.values()) {
      if (type == StepType.UNRECOGNIZED || type == StepType.STEP_TYPE_UNSPECIFIED) {
        continue;
      }
      p.addSteps(step(++seq, type, 0, "detail-" + type));
    }
    p.addSteps(step(++seq, StepType.SUMMARY, StepType.SQL_VALUE, "summary-of-sql"));
    p.addSteps(step(++seq, StepType.SQL, 0, ""));
    ProfileBatch batch =
        ProfileBatch.newBuilder()
            .addProfiles(p)
            .addProfiles(
                TransactionProfile.newBuilder()
                    .setTxId(2)
                    .addSteps(step(1, StepType.METHOD, 0, "m")))
            .build();

    BindValueRedactor.Redacted r = BindValueRedactor.redact(batch);

    assertThat(r.cleared())
        .as("SQL, SUMMARY of SQL (the empty SQL detail is not counted)")
        .isEqualTo(2);
    TransactionProfile out = r.batch().getProfiles(0);
    for (int i = 0; i < out.getStepsCount(); i++) {
      ProfileStep before = batch.getProfiles(0).getSteps(i);
      ProfileStep after = out.getSteps(i);
      if (BindValueRedactor.carriesBindValues(before)) {
        carrying.add(before.getType());
        assertThat(after.getDetail()).as("%s", before.getType()).isEmpty();
        assertThat(after.toBuilder().setDetail(before.getDetail()).build()).isEqualTo(before);
      } else {
        assertThat(after)
            .as("%s (status %d)", before.getType(), before.getStatus())
            .isEqualTo(before);
      }
    }
    assertThat(carrying).containsExactlyInAnyOrder(StepType.SQL, StepType.SUMMARY);
    assertThat(
            BindValueRedactor.carriesBindValues(
                step(1, StepType.SUMMARY, StepType.HTTP_CALL_VALUE, "x")))
        .as("a summary of outgoing calls keeps its detail")
        .isFalse();
    assertThat(r.batch().getProfiles(1)).isEqualTo(batch.getProfiles(1));
  }

  @Test
  @DisplayName("C-148 a batch without bind values is returned as is, nothing counted")
  void nothingToClear() {
    ProfileBatch batch =
        ProfileBatch.newBuilder()
            .addProfiles(
                TransactionProfile.newBuilder()
                    .setTxId(3)
                    .addSteps(step(1, StepType.HTTP_CALL, 0, "GET host/path"))
                    .addSteps(step(2, StepType.SQL, 0, "")))
            .build();
    BindValueRedactor.Redacted r = BindValueRedactor.redact(batch);
    assertThat(r.cleared()).isZero();
    assertThat(r.batch()).isSameAs(batch);
  }
}
