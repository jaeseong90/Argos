/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.test.chaos;

import java.util.Set;
import java.util.TreeSet;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * How the XLog API names the instances of its points ({@code GET /api/v1/apps/{id}/xlog}, {@code
 * data}): {@code instance_id[]} per point and {@code dictionary.instance} (id to name) from
 * PostgreSQL. A point whose instance id has no dictionary entry is shown by the UI as an unknown
 * instance (ui {@code selectionSummary}: name null; 17 §12 "알 수 없는 인스턴스(id)").
 *
 * @param pointInstances the distinct instance ids of the points
 * @param named the ids the dictionary names
 */
public record InstanceView(Set<Long> pointInstances, Set<Long> named) {

  private static final JsonMapper JSON = JsonMapper.builder().build();

  /** Copies. */
  public InstanceView {
    pointInstances = Set.copyOf(pointInstances);
    named = Set.copyOf(named);
  }

  /** Parses the {@code data} member. */
  public static InstanceView of(JsonNode data) {
    Set<Long> points = new TreeSet<>();
    for (JsonNode id : data.path("instance_id")) {
      points.add(id.asLong());
    }
    Set<Long> named = new TreeSet<>();
    for (String key : data.path("dictionary").path("instance").propertyNames()) {
      try {
        named.add(Long.parseLong(key));
      } catch (NumberFormatException e) {
        // not an id
      }
    }
    return new InstanceView(points, named);
  }

  /** Parses JSON text of the {@code data} member (tests). */
  public static InstanceView parse(String json) {
    return of(JSON.readTree(json));
  }

  /** The point instance ids without a name: shown as unknown instances. */
  public Set<Long> unknown() {
    Set<Long> u = new TreeSet<>(pointInstances);
    u.removeAll(named);
    return u;
  }

  /** Whether there are points and every one of them is shown as an unknown instance. */
  public boolean allUnknown() {
    return !pointInstances.isEmpty() && unknown().equals(new TreeSet<>(pointInstances));
  }
}
