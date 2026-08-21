package dev.fitmcp.provider;

import com.garmin.fit.DateTime;
import com.garmin.fit.FileEncoder;
import com.garmin.fit.FileIdMesg;
import com.garmin.fit.Fit;
import com.garmin.fit.SessionMesg;
import com.garmin.fit.Sport;
import dev.fitmcp.domain.ActivityDetail;
import dev.fitmcp.domain.ActivitySummary;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies the FIT provider by <em>encoding</em> synthetic .FIT files with the same SDK it
 * decodes, then asserting the mapping. No personal data and no committed binaries: the
 * fixtures are built in a temp directory per test.
 *
 * <p>This is where the contract-conformance risk actually lives — field mapping, the
 * null-vs-zero policy, the sport table, multisport ordinals, and the id fallback — none of
 * which a compile can catch.
 */
class FitFileActivityProviderTest {

    private static final LocalDate WIDE_FROM = LocalDate.parse("2000-01-01");
    private static final LocalDate WIDE_TO = LocalDate.parse("2100-01-01");

    @Test
    void singleRunningSession_mapsEveryField(@TempDir Path dir) throws Exception {
        Instant start = Instant.parse("2026-07-02T06:12:33Z");
        // Strava export names files by the numeric activity id, so the id should be bare.
        writeFit(dir, "1234567890.fit", 100L, start,
                session(Sport.RUNNING, start, 3120f, 3000f, 8000f, 42, (short) 152, (short) 171, 610));

        FitFileActivityProvider provider = new FitFileActivityProvider(dir.toString());

        ActivityDetail d = provider.getActivity("1234567890").orElseThrow();
        assertThat(d.activityId()).isEqualTo("1234567890");
        assertThat(d.name()).isEmpty();                       // FIT sessions carry no title
        assertThat(d.sport()).isEqualTo(dev.fitmcp.domain.Sport.run);
        assertThat(d.startTime()).isEqualTo(start);
        assertThat(d.durationSeconds()).isEqualTo(3120L);     // total_elapsed_time
        assertThat(d.movingTimeSeconds()).isEqualTo(3000L);   // total_timer_time
        assertThat(d.distanceMeters()).isEqualTo(8000.0);
        assertThat(d.elevationGainMeters()).isEqualTo(42.0);
        assertThat(d.averageHeartrateBpm()).isEqualTo(152);
        assertThat(d.maxHeartrateBpm()).isEqualTo(171);
        assertThat(d.calories()).isEqualTo(610);
        assertThat(d.averagePaceSecondsPerKm()).isEqualTo(ActivityDetail.derivePace(3000L, 8000.0));
    }

    @Test
    void absentOptionalFields_areNullOrZeroPerContract(@TempDir Path dir) throws Exception {
        Instant start = Instant.parse("2026-07-08T17:00:00Z");
        // No timer, no distance, no ascent, no HR, no calories.
        writeFit(dir, "222.fit", 1L, start,
                session(Sport.WALKING, start, 3600f, null, null, null, null, null, null));

        FitFileActivityProvider provider = new FitFileActivityProvider(dir.toString());
        ActivityDetail d = provider.getActivity("222").orElseThrow();

        // Null when not recorded — never a silent zero.
        assertThat(d.averageHeartrateBpm()).isNull();
        assertThat(d.maxHeartrateBpm()).isNull();
        assertThat(d.calories()).isNull();
        // Zero when the field is a measurement that simply was not present.
        assertThat(d.distanceMeters()).isEqualTo(0.0);
        assertThat(d.elevationGainMeters()).isEqualTo(0.0);
        // No timer -> moving falls back to elapsed (still <= duration).
        assertThat(d.movingTimeSeconds()).isEqualTo(3600L);
        // Pace is undefined at zero distance.
        assertThat(d.averagePaceSecondsPerKm()).isNull();
    }

    @Test
    void hikingMapsToWalk(@TempDir Path dir) throws Exception {
        Instant start = Instant.parse("2026-07-10T08:00:00Z");
        writeFit(dir, "333.fit", 1L, start,
                session(Sport.HIKING, start, 5400f, 5400f, 9000f, 300, null, null, null));

        FitFileActivityProvider provider = new FitFileActivityProvider(dir.toString());
        assertThat(provider.getActivity("333").orElseThrow().sport())
                .isEqualTo(dev.fitmcp.domain.Sport.walk);
    }

    @Test
    void multisportFile_dropsTransitions_andEmitsOrdinalIds(@TempDir Path dir) throws Exception {
        Instant swimStart = Instant.parse("2026-07-15T07:00:00Z");
        Instant bikeStart = Instant.parse("2026-07-15T07:20:00Z");
        Instant runStart = Instant.parse("2026-07-15T08:30:00Z");
        Instant t1 = Instant.parse("2026-07-15T07:18:00Z");

        // swim, T1 (transition), bike, run — the transition must not surface as an activity.
        writeFit(dir, "999.fit", 7L, swimStart,
                session(Sport.SWIMMING, swimStart, 1080f, 1080f, 1500f, 0, null, null, null),
                session(Sport.TRANSITION, t1, 120f, 120f, null, null, null, null, null),
                session(Sport.CYCLING, bikeStart, 4200f, 4100f, 40000f, 350, null, null, null),
                session(Sport.RUNNING, runStart, 2700f, 2650f, 10000f, 60, null, null, null));

        FitFileActivityProvider provider = new FitFileActivityProvider(dir.toString());
        List<ActivitySummary> all = provider.listActivities(WIDE_FROM, WIDE_TO, null);

        assertThat(all).hasSize(3);
        assertThat(all).extracting(ActivitySummary::activityId)
                .containsExactly("999-0", "999-1", "999-2");   // ordinal, transition excluded
        assertThat(all).extracting(ActivitySummary::sport)
                .containsExactly(dev.fitmcp.domain.Sport.swim,
                        dev.fitmcp.domain.Sport.ride,
                        dev.fitmcp.domain.Sport.run);
    }

    @Test
    void nonNumericFilename_fallsBackToFileIdComposite(@TempDir Path dir) throws Exception {
        Instant start = Instant.parse("2026-07-20T06:00:00Z");
        // A raw Garmin-style name, not a Strava numeric id -> fallback to file_id.
        writeFit(dir, "activity_export.fit", 42L, start,
                session(Sport.RUNNING, start, 1800f, 1800f, 5000f, 10, null, null, null));

        FitFileActivityProvider provider = new FitFileActivityProvider(dir.toString());
        List<ActivitySummary> all = provider.listActivities(WIDE_FROM, WIDE_TO, null);

        assertThat(all).hasSize(1);
        String id = all.get(0).activityId();
        assertThat(id).matches("\\d+-42");            // <fit_time_created>-<serial>
        assertThat(id).isNotEqualTo("activity_export");
    }

    @Test
    void listActivities_appliesDateAndSportFilters(@TempDir Path dir) throws Exception {
        Instant july = Instant.parse("2026-07-02T06:00:00Z");
        Instant august = Instant.parse("2026-08-02T06:00:00Z");
        writeFit(dir, "10.fit", 1L, july,
                session(Sport.RUNNING, july, 1800f, 1800f, 5000f, 10, null, null, null));
        writeFit(dir, "20.fit", 2L, august,
                session(Sport.CYCLING, august, 3600f, 3600f, 20000f, 50, null, null, null));

        FitFileActivityProvider provider = new FitFileActivityProvider(dir.toString());

        // Date range excludes the August ride.
        assertThat(provider.listActivities(
                LocalDate.parse("2026-07-01"), LocalDate.parse("2026-07-31"), null))
                .extracting(ActivitySummary::activityId).containsExactly("10");

        // Sport filter selects only the ride, across a wide range.
        assertThat(provider.listActivities(WIDE_FROM, WIDE_TO, dev.fitmcp.domain.Sport.ride))
                .extracting(ActivitySummary::activityId).containsExactly("20");
    }

    // --- fixture builders -----------------------------------------------------------

    private static void writeFit(Path dir, String filename, long serial, Instant created,
                                 SessionMesg... sessions) {
        File file = dir.resolve(filename).toFile();
        FileEncoder encoder = new FileEncoder(file, Fit.ProtocolVersion.V2_0);

        // A FIT file must open with a file_id message; ACTIVITY is the type the decoder
        // expects for recorded activities.
        FileIdMesg fileId = new FileIdMesg();
        fileId.setType(com.garmin.fit.File.ACTIVITY);
        fileId.setManufacturer(1);
        fileId.setProduct(1);
        fileId.setSerialNumber(serial);
        fileId.setTimeCreated(new DateTime(java.util.Date.from(created)));
        encoder.write(fileId);

        for (SessionMesg s : sessions) {
            encoder.write(s);
        }
        encoder.close();
    }

    /** A session message; pass null for any field that should be absent (unrecorded). */
    private static SessionMesg session(Sport sport, Instant start, Float elapsed, Float timer,
                                       Float distance, Integer ascent, Short avgHr, Short maxHr,
                                       Integer calories) {
        SessionMesg s = new SessionMesg();
        s.setSport(sport);
        s.setStartTime(new DateTime(java.util.Date.from(start)));
        if (elapsed != null) s.setTotalElapsedTime(elapsed);
        if (timer != null) s.setTotalTimerTime(timer);
        if (distance != null) s.setTotalDistance(distance);
        if (ascent != null) s.setTotalAscent(ascent);
        if (avgHr != null) s.setAvgHeartRate(avgHr);
        if (maxHr != null) s.setMaxHeartRate(maxHr);
        if (calories != null) s.setTotalCalories(calories);
        return s;
    }
}
