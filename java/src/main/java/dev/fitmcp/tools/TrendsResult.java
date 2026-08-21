package dev.fitmcp.tools;

import java.util.List;

/**
 * Result shape for {@code getTrends}. Contract v2 §2.4.
 *
 * <p>A trend is {@code summarizePeriod} repeated over consecutive calendar buckets, so each
 * bucket is a {@link PeriodSummary} whose {@code startDate} / {@code endDate} are the
 * bucket's boundaries. Deliberately reuses that shape rather than inventing a parallel one.
 *
 * @param bucket  the granularity applied, {@code "week"} or {@code "month"}
 * @param buckets one summary per bucket, ascending by {@code startDate}; empty buckets
 *                within the range are present with zero totals and an empty {@code bySport}
 */
public record TrendsResult(
        String bucket,
        List<PeriodSummary> buckets) {
}
