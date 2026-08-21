package dev.fitmcp.tools;

import dev.fitmcp.domain.Sport;
import dev.fitmcp.provider.StubActivityProvider;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * getTrends over the frozen stub dataset (six activities, all July 2026):
 * stub-1 run 07-02, stub-2 ride 07-03, stub-3 swim 07-05, stub-4 other 07-08,
 * stub-5 run 07-12, stub-6 walk 07-14.
 */
class ActivityToolsTrendsTest {

    private final ActivityTools tools = new ActivityTools(new StubActivityProvider());

    @Test
    void weekBuckets_areCalendarAligned_includingEmptyOnes() {
        TrendsResult result = tools.getTrends(
                LocalDate.parse("2026-07-01"), LocalDate.parse("2026-07-31"), BucketUnit.week);

        assertThat(result.bucket()).isEqualTo("week");
        List<PeriodSummary> b = result.buckets();

        // ISO weeks (Monday) overlapping 07-01..07-31: 06-29, 07-06, 07-13, 07-20, 07-27.
        assertThat(b).extracting(PeriodSummary::startDate)
                .containsExactly("2026-06-29", "2026-07-06", "2026-07-13", "2026-07-20", "2026-07-27");
        assertThat(b.get(0).endDate()).isEqualTo("2026-07-05");   // Monday + 6

        // Distribution across the weeks; empty weeks are present with zero totals.
        assertThat(b).extracting(PeriodSummary::activityCount)
                .containsExactly(3, 2, 1, 0, 0);
        assertThat(b.stream().mapToInt(PeriodSummary::activityCount).sum()).isEqualTo(6);

        // First week holds run/ride/swim -> bySport ascending by sport string: ride, run, swim.
        assertThat(b.get(0).bySport()).extracting(t -> t.sport())
                .containsExactly(Sport.ride, Sport.run, Sport.swim);

        // Empty weeks: zero totals, empty (never null) bySport.
        assertThat(b.get(3).totalDistanceMeters()).isZero();
        assertThat(b.get(3).bySport()).isEmpty();
    }

    @Test
    void monthBuckets_spanCalendarMonths() {
        TrendsResult result = tools.getTrends(
                LocalDate.parse("2026-06-15"), LocalDate.parse("2026-08-10"), BucketUnit.month);

        assertThat(result.bucket()).isEqualTo("month");
        assertThat(result.buckets()).extracting(PeriodSummary::startDate)
                .containsExactly("2026-06-01", "2026-07-01", "2026-08-01");
        assertThat(result.buckets()).extracting(PeriodSummary::endDate)
                .containsExactly("2026-06-30", "2026-07-31", "2026-08-31");
        // All six stub activities fall in July.
        assertThat(result.buckets()).extracting(PeriodSummary::activityCount)
                .containsExactly(0, 6, 0);
    }

    @Test
    void nullBucket_defaultsToWeek() {
        TrendsResult result = tools.getTrends(
                LocalDate.parse("2026-07-01"), LocalDate.parse("2026-07-07"), null);
        assertThat(result.bucket()).isEqualTo("week");
    }

    @Test
    void reversedRange_isInvalid() {
        assertThatThrownBy(() -> tools.getTrends(
                LocalDate.parse("2026-07-31"), LocalDate.parse("2026-07-01"), BucketUnit.week))
                .isInstanceOf(ToolFailureException.class);
    }
}
