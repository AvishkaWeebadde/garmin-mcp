package dev.fitmcp.tools;

import dev.fitmcp.domain.ActivityDetail;
import dev.fitmcp.domain.ActivitySummary;
import dev.fitmcp.domain.Sport;
import dev.fitmcp.domain.SportTotals;
import dev.fitmcp.provider.ActivityProvider;
import dev.fitmcp.provider.ProviderUnavailableException;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * The three tools of contract v1.
 *
 * <p>This layer owns ordering, limiting, and error mapping. The provider deliberately does
 * none of those: the contract fixes them, so pushing them behind the seam would mean every
 * future adapter reimplements them identically and one of them eventually gets it wrong.
 *
 * <p>{@code generateOutputSchema = true} is set on every method, never left to the default.
 * Spring AI's default is {@code false} — verified in the annotation bytecode — which would
 * publish tools with no {@code outputSchema} and return no {@code structuredContent}. See
 * docs/adr/0008.
 */
@Component
public class ActivityTools {

    private static final int DEFAULT_LIMIT = 20;

    /**
     * Contract v1 §1.6: descending by startTime, ties broken by activityId ascending.
     * Unspecified ordering produces a Phase 3 diff that looks like a bug.
     */
    private static final Comparator<ActivitySummary> LIST_ORDER =
            Comparator.comparing(ActivitySummary::startTime).reversed()
                    .thenComparing(ActivitySummary::activityId);

    private final ActivityProvider provider;

    public ActivityTools(ActivityProvider provider) {
        this.provider = provider;
    }

    @McpTool(
            name = "listActivities",
            title = "List activities",
            description = "List activities that started within an inclusive date range, most recent first. "
                    + "Returns summary information only; use getActivity for the full detail of one activity.",
            generateOutputSchema = true)
    public ListActivitiesResult listActivities(
            @McpToolParam(description = "First day of the range, inclusive, as a UTC calendar date.",
                    required = true) LocalDate startDate,
            @McpToolParam(description = "Last day of the range, inclusive, as a UTC calendar date.",
                    required = true) LocalDate endDate,
            @McpToolParam(description = "Restrict results to one sport. Null or omitted means all sports.",
                    required = false) Sport sport,
            @McpToolParam(description = "Maximum number of activities to return.",
                    required = false) Integer limit) {

        requireOrderedRange(startDate, endDate);
        int effectiveLimit = (limit == null) ? DEFAULT_LIMIT : limit;

        List<ActivitySummary> matched = fromProvider(
                () -> provider.listActivities(startDate, endDate, sport)).stream()
                .sorted(LIST_ORDER)
                .toList();

        boolean truncated = matched.size() > effectiveLimit;
        List<ActivitySummary> page = truncated ? matched.subList(0, effectiveLimit) : matched;

        return ListActivitiesResult.of(page, truncated);
    }

    @McpTool(
            name = "getActivity",
            title = "Get activity detail",
            description = "Get the full detail of a single activity by its identifier.",
            generateOutputSchema = true)
    public ActivityDetail getActivity(
            @McpToolParam(description = "Opaque identifier of the activity, as returned by listActivities.",
                    required = true) String activityId) {

        return fromProvider(() -> provider.getActivity(activityId))
                .orElseThrow(() -> ToolFailureException.activityNotFound(activityId));
    }

    @McpTool(
            name = "summarizePeriod",
            title = "Summarise a training period",
            description = "Summarise total training volume over an inclusive date range, "
                    + "with a per-sport breakdown.",
            generateOutputSchema = true)
    public PeriodSummary summarizePeriod(
            @McpToolParam(description = "First day of the range, inclusive, as a UTC calendar date.",
                    required = true) LocalDate startDate,
            @McpToolParam(description = "Last day of the range, inclusive, as a UTC calendar date.",
                    required = true) LocalDate endDate) {

        requireOrderedRange(startDate, endDate);

        List<ActivityDetail> details = fromProvider(
                () -> provider.listActivityDetails(startDate, endDate, null));

        return summarize(details, startDate.toString(), endDate.toString());
    }

    @McpTool(
            name = "getTrends",
            title = "Historical training trends",
            description = "Break an inclusive date range into consecutive calendar buckets (weekly or "
                    + "monthly) and summarise training volume in each, with a per-sport breakdown. Use "
                    + "this to see how volume changes over time and to reason about trends when building "
                    + "a plan; each bucket has the same shape as summarizePeriod.",
            generateOutputSchema = true)
    public TrendsResult getTrends(
            @McpToolParam(description = "First day of the range, inclusive, as a UTC calendar date.",
                    required = true) LocalDate startDate,
            @McpToolParam(description = "Last day of the range, inclusive, as a UTC calendar date.",
                    required = true) LocalDate endDate,
            @McpToolParam(description = "Bucket granularity: 'week' (default) or 'month'.",
                    required = false) BucketUnit bucket) {

        requireOrderedRange(startDate, endDate);
        BucketUnit unit = (bucket == null) ? BucketUnit.week : bucket;

        List<ActivityDetail> details = fromProvider(
                () -> provider.listActivityDetails(startDate, endDate, null));

        // Assign each activity to the calendar bucket its UTC start date falls in.
        Map<LocalDate, List<ActivityDetail>> byBucket = details.stream()
                .collect(Collectors.groupingBy(
                        d -> bucketStart(d.startTime().atZone(ZoneOffset.UTC).toLocalDate(), unit)));

        // Emit every bucket that overlaps the range, including empty ones, so the trend line
        // is continuous — rest weeks are as much a trend as ramp weeks.
        List<PeriodSummary> buckets = new ArrayList<>();
        for (LocalDate start = bucketStart(startDate, unit);
             !start.isAfter(endDate);
             start = nextBucket(start, unit)) {
            List<ActivityDetail> inBucket = byBucket.getOrDefault(start, List.of());
            buckets.add(summarize(inBucket, start.toString(), bucketEnd(start, unit).toString()));
        }

        return new TrendsResult(unit.wire(), buckets);
    }

    /**
     * Total volume over a set of activities, echoing the period labels. Shared by
     * {@code summarizePeriod} (one period) and {@code getTrends} (one per bucket) so the two
     * cannot drift in how they roll up.
     */
    private static PeriodSummary summarize(List<ActivityDetail> details, String startLabel, String endLabel) {
        double totalDistance = details.stream().mapToDouble(ActivityDetail::distanceMeters).sum();
        long totalDuration = details.stream().mapToLong(ActivityDetail::durationSeconds).sum();
        double totalElevation = details.stream().mapToDouble(ActivityDetail::elevationGainMeters).sum();

        return new PeriodSummary(
                startLabel, endLabel, details.size(),
                totalDistance, totalDuration, totalElevation,
                rollUpBySport(details));
    }

    // Calendar-aligned buckets (§2.4): weeks are ISO Monday–Sunday, months are 1st–last, UTC.
    // The first/last bucket may extend beyond the query range; only in-range activities count.
    private static LocalDate bucketStart(LocalDate date, BucketUnit unit) {
        return unit == BucketUnit.month
                ? date.withDayOfMonth(1)
                : date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
    }

    private static LocalDate bucketEnd(LocalDate start, BucketUnit unit) {
        return unit == BucketUnit.month
                ? start.plusMonths(1).minusDays(1)
                : start.plusDays(6);
    }

    private static LocalDate nextBucket(LocalDate start, BucketUnit unit) {
        return unit == BucketUnit.month ? start.plusMonths(1) : start.plusWeeks(1);
    }

    /**
     * Contract v1 §1.6 and §2.3: ascending by sport, and sports with no activity in the
     * range are omitted. An {@link EnumMap} keeps accumulation tidy; the sort is applied
     * on the way out rather than relying on enum declaration order, so reordering the
     * enum cannot silently change the wire format.
     */
    private static List<SportTotals> rollUpBySport(List<ActivityDetail> details) {
        Map<Sport, SportTotals> accumulator = new EnumMap<>(Sport.class);

        for (ActivityDetail d : details) {
            accumulator.merge(
                    d.sport(),
                    new SportTotals(d.sport(), 1, d.distanceMeters(), d.durationSeconds()),
                    (a, b) -> new SportTotals(
                            a.sport(),
                            a.activityCount() + b.activityCount(),
                            a.distanceMeters() + b.distanceMeters(),
                            a.durationSeconds() + b.durationSeconds()));
        }

        return accumulator.values().stream()
                .sorted(Comparator.comparing(t -> t.sport().wire()))
                .toList();
    }

    /**
     * Contract v1 §1.7. JSON Schema cannot express a cross-field constraint, so this one
     * cannot be delegated to schema validation and would otherwise fall through to
     * whatever each implementation happens to do.
     */
    private static void requireOrderedRange(LocalDate startDate, LocalDate endDate) {
        if (endDate.isBefore(startDate)) {
            throw ToolFailureException.invalidDateRange(startDate.toString(), endDate.toString());
        }
    }

    /**
     * Translates a provider failure into the contract's wording.
     *
     * <p>Without this the raw provider message would reach the client, and the two
     * implementations would agree on the failure but disagree on how they described it.
     */
    private static <T> T fromProvider(Supplier<T> call) {
        try {
            return call.get();
        } catch (ProviderUnavailableException e) {
            throw ToolFailureException.providerUnavailable(e.getMessage());
        }
    }
}
