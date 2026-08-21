package dev.fitmcp.provider;

import dev.fitmcp.domain.ActivityDetail;
import dev.fitmcp.domain.ActivitySummary;
import dev.fitmcp.domain.Sport;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * The one seam that matters.
 *
 * <p>The tool layer never learns whether data came from a stub, Strava's API, or a
 * parsed {@code .FIT} file. Implementations of this interface are the only place that
 * knows.
 *
 * <p>Deliberately narrow. It exposes range queries and a lookup, and nothing shaped like
 * "give me the raw provider payload" — an escape hatch of that kind would leak provider
 * detail into the tool layer within a week.
 *
 * <p>Provider auth lives outside this interface and outside the MCP process entirely: a
 * one-time out-of-band flow writes a token file that adapters read. The stdio server has
 * no HTTP listener to receive an OAuth callback, and anything else would make the Java
 * and Go implementations structurally different for reasons unrelated to MCP.
 */
public interface ActivityProvider {

    /**
     * Activities that started within an inclusive UTC date range, in no guaranteed order.
     *
     * <p>Ordering and limiting are the tool layer's job, not the provider's — the
     * contract fixes them, and pushing them down here would mean every future adapter
     * has to reimplement them identically.
     *
     * @param sport optional filter; null means all sports
     * @throws ProviderUnavailableException if the underlying source cannot be reached
     */
    List<ActivitySummary> listActivities(LocalDate startInclusive, LocalDate endInclusive, Sport sport);

    /**
     * Full detail for one activity.
     *
     * <p>Returns empty rather than throwing for a genuine miss: "no such activity" is an
     * ordinary answer, and the contract maps it to an {@code isError} tool result rather
     * than a protocol error. Reserve exceptions for the source itself failing.
     *
     * @throws ProviderUnavailableException if the underlying source cannot be reached
     */
    Optional<ActivityDetail> getActivity(String activityId);

    /**
     * Detail for every activity in a range.
     *
     * <p>Needed by {@code summarizePeriod}, which totals elevation gain — a field the
     * summary shape deliberately does not carry.
     *
     * <p>The default implementation is the naive one: list, then fetch each. That is
     * N+1 calls, which is correct but wasteful against a real API. Adapters that can
     * answer this in one request — Strava's activity list already includes elevation —
     * should override it. Kept as a default rather than an abstract method so that
     * adding it did not break the seam's existing implementations.
     *
     * @throws ProviderUnavailableException if the underlying source cannot be reached
     */
    default List<ActivityDetail> listActivityDetails(LocalDate startInclusive, LocalDate endInclusive, Sport sport) {
        return listActivities(startInclusive, endInclusive, sport).stream()
                .map(summary -> getActivity(summary.activityId()))
                .flatMap(Optional::stream)
                .toList();
    }
}
