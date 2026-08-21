package dev.fitmcp.provider;

import com.garmin.fit.FileIdMesg;
import com.garmin.fit.FitDecoder;
import com.garmin.fit.FitMessages;
import com.garmin.fit.SessionMesg;
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
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.stream.Stream;
import java.util.zip.GZIPInputStream;

/**
 * Reads activities from a directory of {@code .FIT} files — a Strava bulk export, or raw
 * Garmin files, or both. See the design decisions logged in CLAUDE.md (2026-08-21).
 *
 * <p><strong>Index once.</strong> Every file is decoded a single time at construction and
 * held in memory as {@link ActivityDetail}. FIT decoding is not free and {@code
 * listActivities} is a range scan; re-parsing per call would re-decode the whole archive
 * constantly. The cost is that this is a <em>snapshot</em>: a new export needs a restart.
 * That is the trade accepted when choosing {@code .FIT} over Strava's (now paid) live API.
 *
 * <p><strong>Ordering and limiting are not done here.</strong> Like the stub, this returns
 * range-filtered results in index order; the tool layer imposes the contract's ordering.
 *
 * <p>Selected by {@code fitmcp.provider=fit}; the stub is the default. The files directory
 * is {@code fitmcp.fit.directory}.
 */
@Component
@ConditionalOnProperty(name = "fitmcp.provider", havingValue = "fit")
public class FitFileActivityProvider implements ActivityProvider {

    private static final Logger log = LoggerFactory.getLogger(FitFileActivityProvider.class);

    private final List<ActivityDetail> activities;

    public FitFileActivityProvider(@Value("${fitmcp.fit.directory}") String directory) {
        Path dir = Path.of(directory);
        if (!Files.isDirectory(dir)) {
            // Fail fast at startup rather than silently answer every query with nothing.
            // A missing directory is a misconfiguration, not "the source is unreachable".
            throw new IllegalStateException(
                    "fitmcp.fit.directory is not a directory: " + dir.toAbsolutePath());
        }
        this.activities = index(dir);
        log.info("Indexed {} activities from FIT files in {}",
                activities.size(), dir.toAbsolutePath());
    }

    private static List<ActivityDetail> index(Path dir) {
        List<ActivityDetail> out = new ArrayList<>();
        try (Stream<Path> paths = Files.list(dir)) {
            paths.filter(FitFileActivityProvider::isFitFile)
                    .sorted()
                    .forEach(p -> indexFile(p, out));
        } catch (IOException e) {
            throw new IllegalStateException("cannot list FIT directory: " + dir.toAbsolutePath(), e);
        }
        return List.copyOf(out);
    }

    /**
     * Decode one file and append its activities. One bad file must not sink the whole
     * index: a partial index still answers most queries, so parse failures are logged and
     * skipped rather than propagated. {@link ProviderUnavailableException} is reserved for
     * the source itself being gone, which for a local directory means startup, not here.
     */
    private static void indexFile(Path path, List<ActivityDetail> out) {
        try (InputStream raw = Files.newInputStream(path);
             InputStream in = isGzip(path) ? new GZIPInputStream(raw) : raw) {

            FitMessages messages = new FitDecoder().decode(in);

            // One activity per session, not per file. Transition legs of a multisport
            // event are dropped: they are gaps between activities, not activities.
            List<SessionMesg> sessions = messages.getSessionMesgs().stream()
                    .filter(s -> s.getSport() != com.garmin.fit.Sport.TRANSITION)
                    .toList();
            if (sessions.isEmpty()) {
                return;
            }

            String baseId = deriveBaseId(path, messages.getFileIdMesgs());
            boolean multisport = sessions.size() > 1;
            for (int i = 0; i < sessions.size(); i++) {
                // Bare id for the ordinary single-session file; an ordinal suffix only for
                // genuine multisport, where one filename cannot identify N legs.
                String id = multisport ? baseId + "-" + i : baseId;
                out.add(toDetail(id, sessions.get(i)));
            }
        } catch (Exception e) {
            log.warn("skipping unreadable FIT file {}: {}", path.getFileName(), e.toString());
        }
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

        return activities.stream()
                .filter(a -> !a.startTime().isBefore(from) && a.startTime().isBefore(toExclusive))
                .filter(a -> sport == null || a.sport() == sport)
                .map(FitFileActivityProvider::toSummary)
                .toList();
    }

    @Override
    public Optional<ActivityDetail> getActivity(String activityId) {
        return activities.stream()
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
