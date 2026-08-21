package dev.fitmcp.domain;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;

/**
 * Full detail of one activity. Contract v1 §3.
 *
 * <p>Records are final, so this cannot extend {@link ActivitySummary}; the shared fields
 * are repeated deliberately rather than hidden behind an interface. The contract is the
 * thing both implementations conform to, and a flat record maps onto it without a layer
 * of indirection that Go would not have either.
 *
 * <p>On {@code @Schema(nullable = true)}: see {@link ActivitySummary} and docs/adr/0011.
 * It is required for the payload to validate against the generated output schema.
 *
 * @param movingTimeSeconds       always {@code <= durationSeconds}
 * @param elevationGainMeters     0 when flat or unmeasured, never null
 * @param maxHeartrateBpm         null when not recorded
 * @param averagePaceSecondsPerKm derived, not provider-supplied; null when distance is 0
 * @param calories                null when not reported
 */
public record ActivityDetail(
        String activityId,
        String name,
        Sport sport,
        Instant startTime,
        long durationSeconds,
        double distanceMeters,
        @Schema(nullable = true) Integer averageHeartrateBpm,
        long movingTimeSeconds,
        double elevationGainMeters,
        @Schema(nullable = true) Integer maxHeartrateBpm,
        @Schema(nullable = true) Double averagePaceSecondsPerKm,
        @Schema(nullable = true) Integer calories) {

    /**
     * Derives pace rather than trusting a provider field, so that Java and Go agree.
     * Contract v1 §3: no rounding is applied.
     */
    public static Double derivePace(long movingTimeSeconds, double distanceMeters) {
        if (distanceMeters <= 0.0) {
            return null;
        }
        return movingTimeSeconds / (distanceMeters / 1000.0);
    }
}
