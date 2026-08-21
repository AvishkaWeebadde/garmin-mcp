# 0013 — `getTrends` tool, and contract v2

**Status**: Accepted · 2026-08-21

## Context

The user wants Claude to analyse their history and propose training plans from trends. Contract v1 froze the surface at **three** tools with the explicit rationale that "a fourth tool would exercise no protocol behaviour the first three do not."

That rationale is about *protocol* cost, not *features*. The existing tools expose per-activity data and single-period totals, but nothing **time-bucketed** — so a client cannot see a ramp, a dip, or a rest week, which is precisely what trend reasoning and plan-building need.

Two ways to serve the request were considered:

- A **plan-generator** tool that returns a training plan. Rejected: a plan is non-deterministic prose that cannot be pinned in a contract both implementations must satisfy, and it buries reasoning the model does better inside the server.
- A **trends data** tool that returns structured, bucketed history, leaving the planning to the client in conversation. Chosen. It keeps the architecture's line — server exposes data, client reasons — intact.

## Decision

Add **`getTrends`** and bump the contract to **v2** (tools 1–3 byte-identical to v1; version stamp makes the change visible in the diff).

`getTrends(startDate, endDate, bucket)` breaks the range into calendar-aligned buckets (`week` = ISO Monday–Sunday, `month` = 1st–last, UTC) and returns one **`periodSummary`** per bucket — the exact shape `summarizePeriod` returns. `periodSummary` is promoted to a shared object definition (§3). Key rules pinned in §2.4:

- Buckets are calendar-aligned, not `startDate`-aligned, so they are comparable across queries. First/last may extend beyond the range; only in-range activities count.
- **Empty buckets are present** with zero totals and `bySport: []` — a rest week is a trend. (Deliberately unlike an absent *sport*, which is omitted.)
- Buckets ascending by `startDate`; `bySport` ascending by sport as before.

Java reuses `summarize(...)` and `rollUpBySport(...)` so `summarizePeriod` and `getTrends` cannot drift in how they roll up. `BucketUnit` is a lowercase-constant enum, mirroring `Sport` (ADR-0010) to avoid the schema/payload mismatch of F-002.

## Consequences

Phase 3 cost rises by one tool — one more `structuredContent` to diff — but no new protocol surface. Verified: 4 unit tests over the frozen stub (bucketing, empty buckets, month spans, invalid range) plus a live run over the real export producing correct monthly and weekly trends.

**The Go implementation must implement `getTrends` to conform to v2.** The calendar-bucketing rules are the most likely Java/Go divergence point — week/month boundary math and the empty-bucket policy — so they are pinned explicitly rather than left to each language's date library.

This is Claude-authored code under the [ADR-0001](0001-claude-authors-the-java-implementation.md) exception.
