package dev.fitmcp.provider;

import dev.fitmcp.domain.ActivityDetail;
import dev.fitmcp.domain.ActivitySummary;
import dev.fitmcp.domain.Sport;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

/**
 * Fixed, deterministic activity data.
 *
 * <p>The values here are normative and mirror {@code contract/stub-dataset.json}. The Go
 * implementation must return exactly the same activities: Phase 3 diffs wire JSON from
 * the two servers, and if their stub data differs then every comparison is noise.
 *
 * <p>If you change a value here, change the JSON and the Go stub in the same commit.
 */
@Component
public class StubActivityProvider implements ActivityProvider {

    private static final List<ActivityDetail> ACTIVITIES = List.of(
            new ActivityDetail("stub-1", "Morning run", Sport.run,
                    Instant.parse("2026-07-02T06:12:33Z"),
                    3120L, 8000.0, 152,
                    3000L, 42.0, 171,
                    ActivityDetail.derivePace(3000L, 8000.0), 610),

            // No heart-rate strap: averageHeartrateBpm and maxHeartrateBpm are null,
            // not omitted and not zero. See docs/adr/0004.
            new ActivityDetail("stub-2", "Commute", Sport.ride,
                    Instant.parse("2026-07-03T07:45:00Z"),
                    1800L, 12500.5, null,
                    1700L, 88.5, null,
                    ActivityDetail.derivePace(1700L, 12500.5), null),

            new ActivityDetail("stub-3", "Pool session", Sport.swim,
                    Instant.parse("2026-07-05T18:20:10Z"),
                    2700L, 2000.0, 128,
                    2400L, 0.0, 145,
                    ActivityDetail.derivePace(2400L, 2000.0), 430),

            // Zero distance: pace is undefined, so averagePaceSecondsPerKm is null.
            new ActivityDetail("stub-4", "Strength", Sport.other,
                    Instant.parse("2026-07-08T17:00:00Z"),
                    3600L, 0.0, 110,
                    3600L, 0.0, 138,
                    ActivityDetail.derivePace(3600L, 0.0), 300),

            new ActivityDetail("stub-5", "Long run", Sport.run,
                    Instant.parse("2026-07-12T05:30:00Z"),
                    7500L, 21097.5, 148,
                    7200L, 315.0, 168,
                    ActivityDetail.derivePace(7200L, 21097.5), 1520),

            new ActivityDetail("stub-6", "Evening walk", Sport.walk,
                    Instant.parse("2026-07-14T19:05:45Z"),
                    2400L, 2600.0, null,
                    2280L, 12.0, null,
                    ActivityDetail.derivePace(2280L, 2600.0), 120));

    @Override
    public List<ActivitySummary> listActivities(LocalDate startInclusive, LocalDate endInclusive, Sport sport) {
        Instant from = startInclusive.atStartOfDay(ZoneOffset.UTC).toInstant();
        // Inclusive end: everything strictly before the start of the following day.
        Instant toExclusive = endInclusive.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant();

        return ACTIVITIES.stream()
                .filter(a -> !a.startTime().isBefore(from) && a.startTime().isBefore(toExclusive))
                .filter(a -> sport == null || a.sport() == sport)
                .map(StubActivityProvider::toSummary)
                .toList();
    }

    @Override
    public Optional<ActivityDetail> getActivity(String activityId) {
        return ACTIVITIES.stream()
                .filter(a -> a.activityId().equals(activityId))
                .findFirst();
    }

    private static ActivitySummary toSummary(ActivityDetail d) {
        return new ActivitySummary(
                d.activityId(), d.name(), d.sport(), d.startTime(),
                d.durationSeconds(), d.distanceMeters(), d.averageHeartrateBpm());
    }
}
