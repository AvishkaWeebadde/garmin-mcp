package dev.fitmcp.provider;

import com.garmin.fit.Decode;
import com.garmin.fit.FileIdMesg;
import com.garmin.fit.FileIdMesgListener;
import com.garmin.fit.MesgBroadcaster;
import com.garmin.fit.SessionMesg;
import com.garmin.fit.SessionMesgListener;
import dev.fitmcp.domain.ActivityDetail;
import dev.fitmcp.domain.ActivitySummary;
import dev.fitmcp.domain.Sport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.stream.Stream;
import java.util.zip.GZIPInputStream;

/**
 * Reads activities from a directory of {@code .FIT} files — a Strava bulk export, or raw
 * Garmin files, or both. See the design decisions logged in CLAUDE.md (2026-08-21).
 *
 * <p><strong>Index once, off the startup path.</strong> Every file is decoded a single time
 * and held in memory. Decoding is done on a background thread so the constructor returns
 * immediately: the MCP server answers {@code initialize} / {@code tools/list} — and the
 * tools appear in the client — while indexing proceeds. The first query blocks on the index
 * (see {@link #activities()}); later queries are instant. This keeps a cold start from
 * making the whole server look absent while it decodes a large export.
 *
 * <p><strong>Snapshot.</strong> A new export needs a restart — the trade accepted when
 * choosing {@code .FIT} over Strava's (now paid) live API.
 *
 * <p><strong>Ordering and limiting are not done here.</strong> Like the stub, this returns
 * range-filtered results in no guaranteed order; the tool layer imposes the contract's
 * ordering. That freedom is also what lets indexing run in parallel.
 *
 * <p>Selected by {@code fitmcp.provider=fit}; the stub is the default. The files directory
 * is {@code fitmcp.fit.directory}.
 */
@Component
@ConditionalOnProperty(name = "fitmcp.provider", havingValue = "fit")
public class FitFileActivityProvider implements ActivityProvider {

    private static final Logger log = LoggerFactory.getLogger(FitFileActivityProvider.class);

    private final CompletableFuture<List<ActivityDetail>> index;

    public FitFileActivityProvider(@Value("${fitmcp.fit.directory}") String directory) {
        Path dir = Path.of(directory);
        if (!Files.isDirectory(dir)) {
            // Fail fast at startup rather than silently answer every query with nothing.
            // A missing directory is a misconfiguration, not "the source is unreachable".
            throw new IllegalStateException(
                    "fitmcp.fit.directory is not a directory: " + dir.toAbsolutePath());
        }
        log.info("Indexing FIT files in {} (background)", dir.toAbsolutePath());
        this.index = CompletableFuture.supplyAsync(() -> buildIndex(dir));
    }

    /**
     * The indexed activities, blocking until the background decode completes. A failure to
     * build the index (the directory became unreadable) surfaces as
     * {@link ProviderUnavailableException} on the query, per the interface contract — a
     * genuine "source unreachable", distinct from an empty result.
     */
    private List<ActivityDetail> activities() {
        try {
            return index.join();
        } catch (CompletionException e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            throw new ProviderUnavailableException("FIT index build failed", cause);
        }
    }

    private static List<ActivityDetail> buildIndex(Path dir) {
        try (Stream<Path> paths = Files.list(dir)) {
            // Parallel: each file decodes independently and the tool layer re-sorts, so
            // index order does not matter here.
            List<ActivityDetail> out = paths
                    .filter(FitFileActivityProvider::isFitFile)
                    .parallel()
                    .flatMap(p -> parseFile(p).stream())
                    .toList();
            log.info("Indexed {} activities from FIT files in {}", out.size(), dir.toAbsolutePath());
            return out;
        } catch (IOException e) {
            throw new UncheckedIOException("cannot list FIT directory: " + dir.toAbsolutePath(), e);
        }
    }

    /**
     * Decode one file into its activities. One bad file must not sink the index: a partial
     * index still answers most queries, so parse failures are logged and the file yields
     * nothing. {@link ProviderUnavailableException} is reserved for the source being gone.
     *
     * <p>Uses the low-level {@link Decode} with listeners for only {@code session} and
     * {@code file_id}. {@code FitDecoder} would materialise and retain every message type —
     * including the thousands of per-second {@code record} messages a FIT file is mostly
     * made of — none of which this provider needs.
     */
    private static List<ActivityDetail> parseFile(Path path) {
        List<SessionMesg> sessionMesgs = new ArrayList<>();
        List<FileIdMesg> fileIdMesgs = new ArrayList<>();
        try (InputStream raw = Files.newInputStream(path);
             InputStream in = isGzip(path) ? new GZIPInputStream(raw) : raw) {

            Decode decode = new Decode();
            MesgBroadcaster broadcaster = new MesgBroadcaster(decode);
            broadcaster.addListener((SessionMesgListener) sessionMesgs::add);
            broadcaster.addListener((FileIdMesgListener) fileIdMesgs::add);
            decode.read(in, broadcaster);
        } catch (Exception e) {
            log.warn("skipping unreadable FIT file {}: {}", path.getFileName(), e.toString());
            return List.of();
        }

        // One activity per session, not per file. Transition legs of a multisport event are
        // dropped: they are gaps between activities, not activities.
        List<SessionMesg> sessions = sessionMesgs.stream()
                .filter(s -> s.getSport() != com.garmin.fit.Sport.TRANSITION)
                .toList();
        if (sessions.isEmpty()) {
            return List.of();
        }

        String baseId = deriveBaseId(path, fileIdMesgs);
        boolean multisport = sessions.size() > 1;
        List<ActivityDetail> out = new ArrayList<>(sessions.size());
        for (int i = 0; i < sessions.size(); i++) {
            // Bare id for the ordinary single-session file; an ordinal suffix only for
            // genuine multisport, where one filename cannot identify N legs.
            String id = multisport ? baseId + "-" + i : baseId;
            out.add(toDetail(id, sessions.get(i)));
        }
        return out;
    }

    /**
     * Activity id, per the scheme in CLAUDE.md. Primary: the Strava export filename, which
     * is the bare Strava activity id (a run of digits) — immutable, unique, and equal to
     * what a future Strava adapter would emit. Fallback for non-Strava files: the {@code
     * file_id} message's {@code time_created} + {@code serial_number}, which survives edits
     * and re-export. Never the session start time — users edit start times.
     */
    private static String deriveBaseId(Path path, List<FileIdMesg> fileIds) {
        String stem = stripFitExtensions(path.getFileName().toString());
        if (stem.matches("\\d+")) {
            return stem;
        }
        FileIdMesg fileId = fileIds.isEmpty() ? null : fileIds.get(0);
        if (fileId != null && fileId.getTimeCreated() != null) {
            Long serial = fileId.getSerialNumber();
            return fileId.getTimeCreated().getTimestamp() + "-" + (serial != null ? serial : "ns");
        }
        return stem; // last resort: whatever the file was named
    }

    private static ActivityDetail toDetail(String id, SessionMesg s) {
        long elapsed = seconds(s.getTotalElapsedTime());
        // Moving time absent means "no pause data"; elapsed is the safe stand-in. Clamp so
        // the contract's movingTimeSeconds <= durationSeconds invariant cannot be violated
        // by a data glitch.
        long moving = s.getTotalTimerTime() != null
                ? Math.min(seconds(s.getTotalTimerTime()), elapsed)
                : elapsed;

        double distance = s.getTotalDistance() != null ? s.getTotalDistance() : 0.0;
        double elevation = s.getTotalAscent() != null ? s.getTotalAscent() : 0.0;

        // Heart rate is null when the sensor was not worn — not 0, which would mean
        // "measured, and it was zero". Calories likewise null when not reported.
        Integer avgHr = s.getAvgHeartRate() != null ? s.getAvgHeartRate().intValue() : null;
        Integer maxHr = s.getMaxHeartRate() != null ? s.getMaxHeartRate().intValue() : null;
        Integer calories = s.getTotalCalories();

        Instant start = s.getStartTime().getDate().toInstant();

        return new ActivityDetail(
                id, "", mapSport(s.getSport()), start,
                elapsed, distance, avgHr,
                moving, elevation, maxHr,
                // Derived here, not trusted from FIT, so Java and Go agree and the
                // null-at-zero-distance rule is applied in exactly one place.
                ActivityDetail.derivePace(moving, distance), calories);
    }

    /**
     * FIT {@code sport} to the contract's closed set. Keyed on {@code sport}, not {@code
     * sub_sport} (which refines within a bucket, e.g. trail vs treadmill, without changing
     * it). This table is shared surface with the future Strava adapter: both must collapse
     * identically or a Phase 3 diff is self-inflicted. {@code hiking -> walk} is deliberate.
     */
    private static Sport mapSport(com.garmin.fit.Sport fit) {
        if (fit == null) {
            return Sport.other;
        }
        return switch (fit) {
            case RUNNING -> Sport.run;
            case CYCLING, E_BIKING -> Sport.ride;
            case SWIMMING -> Sport.swim;
            case WALKING, HIKING -> Sport.walk;
            default -> Sport.other;
        };
    }

    @Override
    public List<ActivitySummary> listActivities(LocalDate startInclusive, LocalDate endInclusive, Sport sport) {
        Instant from = startInclusive.atStartOfDay(ZoneOffset.UTC).toInstant();
        // Inclusive end: everything strictly before the start of the following day.
        Instant toExclusive = endInclusive.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant();

        return activities().stream()
                .filter(a -> !a.startTime().isBefore(from) && a.startTime().isBefore(toExclusive))
                .filter(a -> sport == null || a.sport() == sport)
                .map(FitFileActivityProvider::toSummary)
                .toList();
    }

    @Override
    public Optional<ActivityDetail> getActivity(String activityId) {
        return activities().stream()
                .filter(a -> a.activityId().equals(activityId))
                .findFirst();
    }

    private static ActivitySummary toSummary(ActivityDetail d) {
        return new ActivitySummary(
                d.activityId(), d.name(), d.sport(), d.startTime(),
                d.durationSeconds(), d.distanceMeters(), d.averageHeartrateBpm());
    }

    // FIT durations are Float seconds; the contract carries whole seconds.
    private static long seconds(Float fitSeconds) {
        return fitSeconds != null ? Math.round(fitSeconds.doubleValue()) : 0L;
    }

    private static boolean isFitFile(Path p) {
        String name = p.getFileName().toString().toLowerCase(Locale.ROOT);
        return name.endsWith(".fit") || name.endsWith(".fit.gz");
    }

    private static boolean isGzip(Path p) {
        return p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".gz");
    }

    private static String stripFitExtensions(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".fit.gz")) {
            return name.substring(0, name.length() - ".fit.gz".length());
        }
        if (lower.endsWith(".fit")) {
            return name.substring(0, name.length() - ".fit".length());
        }
        return name;
    }
}
