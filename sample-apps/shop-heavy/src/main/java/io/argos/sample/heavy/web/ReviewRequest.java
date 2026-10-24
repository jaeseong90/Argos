/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.sample.heavy.web;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/** Body of POST /api/heavy/reviews and PUT /api/heavy/reviews/{id} (Bean Validation). */
public record ReviewRequest(
    @Positive long productId,
    @Min(1) @Max(5) int rating,
    @NotBlank @Size(max = 500) String comment) {}
