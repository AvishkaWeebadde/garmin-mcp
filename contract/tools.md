# fitmcp tool contract

```
contract: v1
status:   frozen
frozen:   2026-08-05
```

This document is the shared specification that **both** the Java and Go implementations satisfy. It is written once, before either implementation. Where the two servers produce different JSON for the same call, this document decides which one is wrong — or, if it is silent, the gap in this document is itself the finding.

Do not change a tool signature in one implementation without changing this file and the other implementation.

---

## 1. Conventions

These apply to every tool. Most divergence between two implementations comes from leaving these unstated.

### 1.1 Field naming

All wire field names are **camelCase** — inputs and outputs alike.

Rationale is recorded in [ADR-0003](../docs/adr/0003-camelcase-wire-field-names.md). Briefly: Go requires explicit struct tags whatever we choose, so camelCase costs Go nothing, while snake_case would force either non-idiomatic Java parameter names or a layer of `@JsonProperty` noise on every record component.

### 1.2 Units

| Quantity | Unit | JSON type | Notes |
|---|---|---|---|
| Distance | metres | number | May be fractional. Never kilometres. |
| Duration | seconds | integer | Never minutes, never ISO-8601 durations. |
| Elevation | metres | number | Gain only; may be fractional. |
| Heart rate | beats per minute | integer | |
| Pace | seconds per kilometre | number | Derived; may be fractional. |
| Energy | kilocalories | integer | |

There are no unit suffixes to guess at: every field carrying a unit names it (`distanceMeters`, `durationSeconds`, `averageHeartrateBpm`).

### 1.3 Time

- **Instants** are RFC 3339 with an explicit `Z` offset, always normalised to UTC: `2026-07-14T06:12:33Z`. Never local time, never a bare offset like `+05:30`, never epoch seconds.
- **Dates** (used only in tool inputs) are `YYYY-MM-DD` and are interpreted as UTC calendar dates.
- A date range is **inclusive on both ends**. `startDate: 2026-07-01, endDate: 2026-07-01` selects activities that began at any instant on 2026-07-01 UTC.

### 1.4 Null policy

**Every field declared in an output schema is always present in the response.** A value that is unknown, unmeasured, or not applicable is JSON `null`. Fields are never omitted.

This is the single rule most likely to be violated by accident, and the one most worth enforcing: Jackson and Go's `encoding/json` disagree by default (`@JsonInclude` vs `omitempty`), and the disagreement is silent. An activity recorded without a heart-rate strap must serialise as `"averageHeartrateBpm": null` in both implementations, not as an absent key in one of them.

Arrays are never null. An empty result is `[]`.

### 1.5 Identifiers and enums

- `activityId` is an **opaque string**. It is not guaranteed numeric, and implementations must not parse it. The stub provider happens to use `"stub-1"`-style values; the Strava adapter will use numeric strings. Neither is part of the contract.
- `sport` is a lowercase enum with a **closed** set: `run`, `ride`, `swim`, `walk`, `other`. Anything the provider reports that does not map onto the first four maps onto `other`. Implementations must not invent new members.

### 1.6 Ordering

Ordering is part of the contract, because unspecified ordering produces a diff that looks like a bug.

- `listActivities.activities` — **descending by `startTime`** (most recent first). Ties broken by `activityId` ascending, lexicographic.
- `summarizePeriod.bySport` — **ascending by `sport`**, lexicographic on the enum string.

### 1.7 Error semantics

Two distinct channels, and the difference matters:

| Condition | Channel |
|---|---|
| Unknown tool name | JSON-RPC protocol error |
| Input fails schema validation (wrong type, missing required, out of range) | JSON-RPC protocol error, raised by the framework |
| `activityId` not found | Tool result with `isError: true` |
| `endDate` earlier than `startDate` | Tool result with `isError: true` |
| Provider unreachable or failing | Tool result with `isError: true` |

Rationale: the first two are the caller getting the *protocol* wrong; the last three are the *tool* running correctly and having nothing good to report. A model consuming this server can recover from the second kind by rewording its request, which it can only do if it receives the message rather than a transport-level failure.

Error results carry a single text content block, no `structuredContent`. The message text is contractual:

| Case | Text |
|---|---|
| Not found | `activity not found: <activityId>` |
| Bad range | `invalid date range: endDate <endDate> is before startDate <startDate>` |
| Provider failure | `provider unavailable: <reason>` |

### 1.8 Structured output

Every tool declares an `outputSchema` and returns `structuredContent`. Each result **also** carries a `content` array with one `text` block, holding a human-readable rendering. The text block is *not* contractual — its exact wording may differ between implementations and that difference is not a finding. `structuredContent` is contractual in full.

---

## 2. Tools

Exactly three. The surface is deliberately small: Phase 3's cost scales with it, and a fourth tool would exercise no protocol behaviour the first three do not.

---

### 2.1 `listActivities`

**Description** (contractual — this string is sent to the model, and differences in it change model behaviour):

> List activities that started within an inclusive date range, most recent first. Returns summary information only; use getActivity for the full detail of one activity.

**Input schema**

```json
{
  "$schema": "https://json-schema.org/draft/2020-12/schema",
  "type": "object",
  "properties": {
    "startDate": {
      "type": "string",
      "format": "date",
      "pattern": "^\\d{4}-\\d{2}-\\d{2}$",
      "description": "First day of the range, inclusive, as a UTC calendar date."
    },
    "endDate": {
      "type": "string",
      "format": "date",
      "pattern": "^\\d{4}-\\d{2}-\\d{2}$",
      "description": "Last day of the range, inclusive, as a UTC calendar date."
    },
    "sport": {
      "type": ["string", "null"],
      "enum": ["run", "ride", "swim", "walk", "other", null],
      "description": "Restrict results to one sport. Null or omitted means all sports."
    },
    "limit": {
      "type": "integer",
      "minimum": 1,
      "maximum": 100,
      "default": 20,
      "description": "Maximum number of activities to return."
    }
  },
  "required": ["startDate", "endDate"],
  "additionalProperties": false
}
```

**Output schema**

```json
{
  "$schema": "https://json-schema.org/draft/2020-12/schema",
  "type": "object",
  "properties": {
    "activities": {
      "type": "array",
      "items": { "$ref": "#/$defs/activitySummary" }
    },
    "count": {
      "type": "integer",
      "description": "Number of activities in this response."
    },
    "truncated": {
      "type": "boolean",
      "description": "True if the range held more activities than limit allowed."
    }
  },
  "required": ["activities", "count", "truncated"],
  "additionalProperties": false
}
```

`count` is always `activities.length`. It is redundant on purpose: it is a cheap check that two implementations agree about what they think they returned.

---

### 2.2 `getActivity`

**Description**

> Get the full detail of a single activity by its identifier.

**Input schema**

```json
{
  "$schema": "https://json-schema.org/draft/2020-12/schema",
  "type": "object",
  "properties": {
    "activityId": {
      "type": "string",
      "minLength": 1,
      "description": "Opaque identifier of the activity, as returned by listActivities."
    }
  },
  "required": ["activityId"],
  "additionalProperties": false
}
```

**Output schema**

The `activityDetail` object defined in §3, inline at the top level.

If no activity has that id, this is an `isError: true` result per §1.7 — not an empty object, not a null field.

---

### 2.3 `summarizePeriod`

**Description**

> Summarise total training volume over an inclusive date range, with a per-sport breakdown.

**Input schema**

```json
{
  "$schema": "https://json-schema.org/draft/2020-12/schema",
  "type": "object",
  "properties": {
    "startDate": {
      "type": "string",
      "format": "date",
      "pattern": "^\\d{4}-\\d{2}-\\d{2}$",
      "description": "First day of the range, inclusive, as a UTC calendar date."
    },
    "endDate": {
      "type": "string",
      "format": "date",
      "pattern": "^\\d{4}-\\d{2}-\\d{2}$",
      "description": "Last day of the range, inclusive, as a UTC calendar date."
    }
  },
  "required": ["startDate", "endDate"],
  "additionalProperties": false
}
```

**Output schema**

```json
{
  "$schema": "https://json-schema.org/draft/2020-12/schema",
  "type": "object",
  "properties": {
    "startDate": { "type": "string" },
    "endDate": { "type": "string" },
    "activityCount": { "type": "integer" },
    "totalDistanceMeters": { "type": "number" },
    "totalDurationSeconds": { "type": "integer" },
    "totalElevationGainMeters": { "type": "number" },
    "bySport": {
      "type": "array",
      "items": { "$ref": "#/$defs/sportTotals" }
    }
  },
  "required": [
    "startDate", "endDate", "activityCount",
    "totalDistanceMeters", "totalDurationSeconds",
    "totalElevationGainMeters", "bySport"
  ],
  "additionalProperties": false
}
```

`bySport` is an **array, not a map**. A map would be the obvious modelling choice and is deliberately rejected: Go map iteration order is randomised, Java's `HashMap` order is arbitrary-but-stable, and JSON object key order is not semantically meaningful anyway. An ordered array (§1.6) makes the Phase 3 diff meaningful instead of noisy. Sports with no activity in the range are **omitted** from the array — this is the one place where absence is meaningful, and it is an array element rather than a field, so §1.4 does not apply.

---

## 3. Shared object definitions

### `activitySummary`

| Field | Type | Nullable | Notes |
|---|---|---|---|
| `activityId` | string | no | Opaque. |
| `name` | string | no | Provider-supplied title. May be empty string, never null. |
| `sport` | string enum | no | §1.5. |
| `startTime` | string | no | RFC 3339, UTC, `Z`. |
| `durationSeconds` | integer | no | Elapsed time, not moving time. |
| `distanceMeters` | number | no | `0` for activities with no distance. |
| `averageHeartrateBpm` | integer | **yes** | Null when not recorded. |

### `activityDetail`

Every field of `activitySummary`, plus:

| Field | Type | Nullable | Notes |
|---|---|---|---|
| `movingTimeSeconds` | integer | no | ≤ `durationSeconds`. |
| `elevationGainMeters` | number | no | `0` when flat or unmeasured. |
| `maxHeartrateBpm` | integer | **yes** | Null when not recorded. |
| `averagePaceSecondsPerKm` | number | **yes** | Null when `distanceMeters` is 0. |
| `calories` | integer | **yes** | Null when not reported. |

`averagePaceSecondsPerKm` is derived as `movingTimeSeconds / (distanceMeters / 1000)`. Both implementations must derive it rather than trusting a provider field, so that the two agree. Rounding is **not** applied; emit the full double.

### `sportTotals`

| Field | Type | Nullable | Notes |
|---|---|---|---|
| `sport` | string enum | no | §1.5. |
| `activityCount` | integer | no | |
| `distanceMeters` | number | no | |
| `durationSeconds` | integer | no | |

---

## 4. Spec-version-dependent surface

Marked explicitly so that Phase 3 can separate **spec drift** from **framework drift**. The Java server speaks MCP `2025-11-25` (verified: `io.modelcontextprotocol.spec.ProtocolVersions` in mcp-core 2.0.0 declares no later constant). The Go server speaks `2026-07-28`.

Expect these to differ for reasons that are *not* implementation bugs:

- **Session lifecycle.** Java performs an `initialize` handshake and negotiates a protocol version. The Go server, stateless, has none. Any diff in the opening exchange is spec drift.
- **`Mcp-Session-Id`.** Present on the 2025-11-25 HTTP transport, removed in 2026-07-28. Not applicable to Java here since Java is stdio, but do not treat its absence on the Go side as a finding.
- **Tool schema dialect.** 2026-07-28 defines tool schemas against full JSON Schema 2020-12. The 2025-11-25 Java SDK may emit a narrower subset, and may drop or rewrite keywords this contract uses — `$defs`, `$ref`, `pattern`, `additionalProperties`, union types like `["string", "null"]`. **Where the Java server cannot express a schema keyword, that is spec/SDK drift and gets recorded, not worked around by weakening this contract.**
- **`outputSchema` / `structuredContent`.** Available from 2025-06-18 onward, so both should support it. Divergence here is a genuine framework finding, not spec drift.

Everything not listed in this section is expected to be byte-identical between the two servers, modulo the non-contractual text block of §1.8 and JSON object key ordering.

---

## 5. Conformance checklist

A run of Phase 3 should check, for each of the three tools:

1. `tools/list` — name, title, description string, `inputSchema`, `outputSchema`.
2. `tools/call` happy path — `structuredContent` deep-equal.
3. `tools/call` empty result — `[]`, `count: 0`, `truncated: false`.
4. `tools/call` with a null-valued optional field present in the source data — confirms §1.4 on both sides.
5. Each of the three §1.7 error cases — `isError` flag and exact message text.
