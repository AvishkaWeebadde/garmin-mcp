package dev.fitmcp.tools;

import dev.fitmcp.domain.ActivitySummary;

import java.util.List;

/**
 * Result shape for {@code listActivities}. Contract v1 §2.1.
 *
 * @param activities ordered descending by startTime; never null, empty is {@code []}
 * @param count      always {@code activities.size()}. Redundant on purpose: it is a cheap
 *                   check that two implementations agree about what they think they returned
 * @param truncated  true if the range held more activities than {@code limit} allowed
 */
public record ListActivitiesResult(
        List<ActivitySummary> activities,
        int count,
        boolean truncated) {

    public static ListActivitiesResult of(List<ActivitySummary> activities, boolean truncated) {
        return new ListActivitiesResult(activities, activities.size(), truncated);
    }
}
