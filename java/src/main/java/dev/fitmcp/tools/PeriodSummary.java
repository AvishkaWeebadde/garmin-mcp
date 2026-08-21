package dev.fitmcp.tools;

import dev.fitmcp.domain.SportTotals;

import java.util.List;

/**
 * Result shape for {@code summarizePeriod}. Contract v1 §2.3.
 *
 * <p>{@code startDate} and {@code endDate} are echoed back as plain strings rather than
 * {@code LocalDate}, so the wire form is fixed by this type instead of by whatever date
 * format the serialiser happens to prefer.
 *
 * @param bySport ascending by sport; sports with no activity in the range are omitted
 */
public record PeriodSummary(
        String startDate,
        String endDate,
        int activityCount,
        double totalDistanceMeters,
        long totalDurationSeconds,
        double totalElevationGainMeters,
        List<SportTotals> bySport) {
}
