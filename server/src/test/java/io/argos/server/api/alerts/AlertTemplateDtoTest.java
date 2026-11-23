/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.api.alerts;

import static org.assertj.core.api.Assertions.assertThat;

import io.argos.server.alert.templates.DefaultTemplates;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("req:F-S24")
class AlertTemplateDtoTest {

  @Test
  @DisplayName(
      "T-SV-033 every part of GET /alerts/templates carries the template's key and version in"
          + " defaults (C-228 (6)), like a rule made from it")
  void partsNameTheirTemplate() {
    assertThat(DefaultTemplates.standard().all()).hasSize(14);
    for (DefaultTemplates.Template t : DefaultTemplates.standard().all()) {
      AlertRuleDtos.Template dto = AlertRuleDtos.Template.of(t);
      assertThat(dto.defaults()).as(t.key()).isNotEmpty();
      for (AlertRuleDtos.TemplatePart part : dto.defaults()) {
        assertThat(part.rule().templateKey()).as(t.key()).isEqualTo(t.key());
        assertThat(part.rule().templateVersion()).as(t.key()).isEqualTo(t.version());
      }
    }
  }
}
