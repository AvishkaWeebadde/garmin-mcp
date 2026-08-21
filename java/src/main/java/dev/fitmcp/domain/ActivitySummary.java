package dev.fitmcp.domain;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;

/**
 * Summary view of one activity. Contract v1 §3.
 *
 * <p>{@code averageHeartrateBpm} is a boxed {@link Integer} on purpose. A primitive
 * {@code int} cannot express "not recorded" and would silently emit {@code 0}, which
 * under this contract means "measured, and it was zero". See docs/adr/0004.
 *
 * <p>{@code @Schema(nullable = true)} is load-bearing, not documentation. Spring AI's
 * schema generator emits {@code {"type":"integer"}} for a boxed {@code Integer} and has
 * no notion that it might be null; the MCP SDK then validates outgoing payloads against
 * that schema and rejects the null. The annotation is the only supported lever that
 * widens the generated type. See docs/adr/0011.
 *
 * @param activityId          opaque identifier; never parsed
 * @param name                provider-supplied title; may be empty, never null
 * @param sport               closed enum, §1.5
 * @param startTime           serialised as RFC 3339 UTC with a {@code Z} offset
 * @param durationSeconds     elapsed time, not moving time
 * @param distanceMeters      metres; 0 for activities with no distance
 * @param averageHeartrateBpm null when not recorded
 */
public record ActivitySummary(
        String activityId,
        String name,
        Sport sport,
        Instant startTime,
        long durationSeconds,
        double distanceMeters,
        @Schema(nullable = true) Integer averageHeartrateBpm) {
}
