package dev.fitmcp.domain;

/**
 * Per-sport rollup inside a period summary. Contract v1 §3.
 *
 * <p>These are emitted as an ordered array rather than a map keyed by sport. Go
 * randomises map iteration order, so a map would produce a spurious diff on every
 * Phase 3 run. See docs/adr/0005.
 */
public record SportTotals(
        Sport sport,
        int activityCount,
        double distanceMeters,
        long durationSeconds) {
}
