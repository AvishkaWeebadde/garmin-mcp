# 0012 — `.FIT` file provider as the first real `ActivityProvider`

**Status**: Accepted · 2026-08-21

## Context

Phase 1's plan was a real provider "Strava or `.FIT`" behind the `ActivityProvider` seam. Strava OAuth was the assumed choice: `CLAUDE.md` recorded "Strava OAuth 2 works today and covers most fields".

That assumption expired. As of 2026-06-01 Strava's Standard-tier API requires an active Strava subscription (~$11.99/mo, per developer, no free tier). The free path is the bulk export — a `.zip` of `.FIT` files every athlete can download — which feeds the `fit-file` adapter already named in the architecture. A `.FIT` reader is therefore the free choice *and* it exercises a seam we already committed to. Strava-OAuth is deferred, not dropped.

`.FIT` is a binary format with its own CRC and message-definition scheme; the official Garmin FIT Java SDK (`com.garmin:fit`, on Maven Central) decodes it. Nothing FIT-shaped may cross the seam — the tool layer stays ignorant of the source.

## Decision

Implement `FitFileActivityProvider`: index a directory of `.fit` / `.fit.gz` files once at construction, and answer the interface from memory. It is **source-agnostic** — a Strava export or raw Garmin files. The data-shaping rules, each a place where divergence hides:

- **Granularity**: one activity per FIT `session`, not per file. `transition` sessions are dropped (they are gaps, not activities). A multisport file yields N single-sport activities.
- **`activityId`**: primary is the Strava export filename's bare numeric activity id — immutable, unique, traceable, and equal to what a future Strava adapter would emit. Fallback for non-Strava files is a `file_id` composite (`time_created` + `serial_number`); never `session.start_time`, which users edit. Multisport legs get a `<baseId>-<ordinal>` suffix — the only case that leaves the bare-id path.
- **`sport`**: an explicit FIT→`Sport` table keyed on `sport` (not `sub_sport`): `running→run`, `cycling`/`e_biking→ride`, `swimming→swim`, `walking`/`hiking→walk`, everything else `→other`. `hiking→walk` is deliberate.
- **Null policy**: absent HR/calories serialise `null`, never `0`; absent distance/ascent are `0.0`; pace is derived (null at zero distance); missing moving-time falls back to elapsed, clamped `≤` duration.

Selection: `@ConditionalOnProperty(fitmcp.provider)` — `stub` is the default (`matchIfMissing`), `fit` is opt-in — plus `fitmcp.fit.directory`. A missing directory fails startup rather than answering every query with nothing.

## Consequences

The provider is a **snapshot**, not live: a new export needs a restart. That is the cost accepted for avoiding both the subscription and the out-of-band OAuth token flow the stdio server cannot host.

Three divergences against a future Strava adapter are now expected **by design**, not bugs: id-equality holds only where Strava named the file numerically; the sport table collapses Strava's richer taxonomy the same way (so `Hike` must map to `walk` there too); and a multisport event may be one activity here and N — or one — via Strava's API. Documented so Phase 3 reads them as findings.

Verified by six encode-decode tests: synthetic FIT built with the SDK's `FileEncoder`, decoded by the provider, mapping asserted. This covers the field mapping, null policy, sport table, multisport ordinals, and id fallback — none of which a compile catches. **Still unproven against a real Strava export** (exotic `sub_sport`, `.fit.gz` in the wild, manufacturer quirks); that is the remaining gap.

This provider was written by Claude at explicit request — the second such exception, governed by [ADR-0001](0001-claude-authors-the-java-implementation.md) and carrying the same cost.
