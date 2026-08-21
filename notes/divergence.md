# Divergence findings

The project's actual deliverable. Each finding is classified:

- **spec drift** — the two protocol revisions genuinely differ (Java 2025-11-25, Go 2026-07-28)
- **framework drift** — the SDKs/frameworks differ within the same spec
- **mistake** — one implementation is simply wrong

Findings F-001…F-009 were all found during **Phase 1**, before any Go code existed. They are Java-side deviations from frozen contract v1, measured from the wire. Phase 3 will re-check each against Go and reclassify where needed.

Wire captures: run `scripts/mcp_probe.py` against the jar.

---

## F-001 — Error messages are prefixed, so contract §1.7 text is unreachable

**Framework drift.** Not fixable through the annotation API.

Contract §1.7 fixes error text verbatim. Spring AI's `AbstractMcpToolMethodCallback.createErrorMessage` is `"Error invoking method: %s"`, and the tool name is appended before the message. Measured:

```
expected: activity not found: nope
actual  : Error invoking method: getActivity\nactivity not found: nope
```

The escape hatch — returning a `CallToolResult` directly, which `convertValueToCallToolResult` passes through untouched — is incompatible with `generateOutputSchema`, which derives the schema from the declared return type. You can have exact error text *or* a generated output schema, not both.

**Status: unresolved, deliberately.** The contract has not been weakened to match. Go is expected to produce the exact text, at which point this becomes a concrete statement about the two frameworks rather than a Java bug report.

---

## F-002 — Schema generator ignores `@JsonValue` on enums

**Framework drift**, arguably a Spring AI defect: the server contradicts *itself*.

The MCP SDK's Jackson mapper honours `@JsonValue` (payload `"run"`). Spring AI's victools-based generator does not enable `JacksonOption.FLATTENED_ENUMS_FROM_JSONVALUE` and reads constant names (schema `["RUN",…]`). The SDK then validates payload against schema and rejects it — on both input and output.

Worked around by naming the Java constants for the wire form. See ADR-0010.

The Go SDK derives schema and payload from the same struct tags and cannot disagree with itself this way.

---

## F-003 — Generated output schemas cannot express nullability without a Swagger annotation

**Framework drift.**

A boxed `Integer` generates `{"type":"integer"}`. The SDK validates output against it, so every `null` is rejected:

```
/averageHeartrateBpm: null found, integer expected
```

`getActivity("stub-2")` — a ride with no heart-rate strap — returned `isError: true`. Fixed with `@Schema(nullable = true)` from `swagger-annotations-jakarta`, the only supported lever. See ADR-0011.

An MCP server that serves no HTTP now has a compile-time dependency on an OpenAPI annotation library. Go needs no equivalent: `*int` is nullable by construction.

---

## F-004 — Schema validation failures are `isError`, not protocol errors

**Contract expectation was wrong for Java.**

Contract §1.7 predicted that a missing required argument would surface as a JSON-RPC protocol error raised by the framework. Measured:

```
{"isError": true, "content":[{"text":"Tool (getActivity) input validation failed:
   Validation failed: JSON schema validation errors: [: required property 'activityId' not found]"}]}
```

Only an **unknown tool name** produces a protocol error. Every other input problem comes back as a successful result with `isError: true`.

Do not amend the contract until Go is measured — if Go raises a protocol error here, §1.7 was right and Java is the outlier. This is the single most likely place for the two to differ on *error channel* rather than error text.

---

## F-005 — Unknown-tool protocol error has inconsistent `message` and `data`

**Framework drift**, cosmetic but odd.

```json
{"code": -32602, "message": "Unknown tool: invalid_tool_name", "data": "Tool not found: noSuchTool"}
```

`data` names the tool that was actually requested; `message` contains the literal string `invalid_tool_name` rather than the requested name. Code `-32602` is *Invalid params*, not *Method not found* (`-32601`) — defensible, since the tool name is a parameter of `tools/call`.

---

## F-006 — `tools/list` emits default tool annotations that are semantically wrong

**Framework drift.**

Every tool is advertised as:

```json
"annotations": {"destructiveHint": true, "readOnlyHint": false, "openWorldHint": true, "title": ""}
```

All three tools are read-only and non-destructive. Spring AI defaults `destructiveHint` to `true` and `readOnlyHint` to `false`, which is the *unsafe* default rather than the neutral one — a client using these hints to gate confirmation prompts would prompt on every read.

Also note `annotations.title` is empty while the top-level `title` is correctly populated from `@McpTool(title = …)`; two title fields exist and only one is filled.

Fixable via `@McpTool(annotations = @McpAnnotations(...))`. **Left unfixed on purpose** so Phase 3 can compare defaults — what each framework advertises when the author says nothing is a more interesting datum than what it advertises when told.

---

## F-007 — Contract schema keywords are silently dropped, and `format` keywords are added

**Framework drift.** Expected per contract §4, and now measured.

Dropped from the generated schemas: `additionalProperties: false`, `pattern`, `minLength`, `minimum`/`maximum`, `default`, and the `$defs`/`$ref` structure — shared object definitions are inlined and duplicated at each use site instead.

Added, unrequested: `"format": "int32"`, `"int64"`, `"double"`, `"date-time"`, `"date"`.

So the published schema is both weaker (no constraints) and more specific (JVM-width formats) than the contract. The `format` values leak Java's type widths onto the wire; Go will leak its own.

---

## F-008 — `limit`'s contractual maximum of 100 is enforced by nobody

**Mistake — mine, and currently live.**

Contract §2.1 declares `"maximum": 100`. The generated schema omits it (F-007), and `ActivityTools` does not clamp. `limit: 100000` is accepted.

Not fixed yet because the fix picks a side: enforcing in code produces `isError`, whereas §1.7 says an out-of-range value should be a framework-raised protocol error — which Java cannot produce anyway (F-004). Worth resolving deliberately rather than reflexively.

---

## F-009 — Spring AI has a `STATELESS` HTTP mode at spec 2025-11-25

**Not drift — a correction to a project assumption.**

`McpServerProperties$ServerProtocol` is `{SSE, STREAMABLE, STATELESS}`. "Stateless" is therefore not exclusively a 2026-07-28 concept; Spring AI offers a stateless HTTP transport while speaking the older revision.

This matters for Phase 2's framing. The Java/Go split is not "stateful vs stateless" — it is two *protocol revisions*, one of which makes statelessness the core rather than a transport option. Worth checking in Phase 3 whether Spring AI's `STATELESS` and go-sdk's `Stateless = true` produce comparable wire behaviour.

---

## FIT-provider findings (against a real Strava export, 2026-08-21)

F-010…F-012 are a different class from the above: found by running the `fit` provider
over a real Strava bulk export (249 activities), not from the wire against the stub. They
anticipate Phase 3's FIT-vs-Strava-provider comparison rather than Java-vs-Go. A new axis
applies: **source drift** — the same activity differs because it came through the `.FIT`
binary path rather than Strava's JSON API.

## F-010 — FIT distances carry float32 quantization noise

**Source drift.** The FIT SDK's `SessionMesg.getTotalDistance()` returns a 32-bit `Float`;
widening it to the contract's `double` exposes the binary fraction. Measured:

```
distanceMeters: 5256.58984375     (a 5.26 km run)
distanceMeters: 1120.6500244140625
```

A Strava-API provider returns already-clean doubles (`5256.6`), so the **same activity will
diverge on every distance — and every derived `averagePaceSecondsPerKm`** — between the two
providers. Not spec or framework drift: a fidelity artifact of the binary source.

**Decision: keep distances raw.** Contract v1 does not specify distance precision, and
rounding here would pick one silently and destroy the divergence that is the point. The FIT
path reports what the file actually holds. Phase 3 records the delta against Strava; it is a
finding, not an error to paper over. (Consistent with pace being emitted unrounded.)

## F-011 — One corrupt export file; skip-and-log held

**Provider behaviour, by design.** Of 188 `.fit.gz`, one (`14155167394.fit.gz`) is
truncated — `FitRuntimeException: Unexpected end of input stream at byte: 63701`. Strava's
export occasionally ships a damaged file. The provider logged it and indexed the other
**187**, rather than failing the whole source. This is the skip-and-log decision (ADR-0012)
paying off on real data: `ProviderUnavailableException` is reserved for the source being
gone, not one bad file.

## F-012 — GPX-only activities are invisible to a FIT-only provider

**Source coverage drift.** The export holds **188 `.fit.gz` + 61 `.gpx` = 249** activities.
The provider reads only FIT, so ~24% of activities — older ones Strava kept only as GPX —
silently do not appear. A Strava-API provider returns all 249. So `listActivities` counts
are provider-dependent, and the gap is structural, not a bug: this athlete's pre-FIT history
is GPX-only. Adding a GPX adapter behind the same seam would close it, if ever wanted.

## Confirmed non-findings

Verified working and contract-conformant on the Java side, so a Phase 3 difference here is a genuine Go finding:

- Ordering: `listActivities` descending by `startTime` (`stub-6, stub-5, …`); `bySport` ascending (`other, ride, run, swim, walk`).
- `count` / `truncated`: `limit: 2` over 6 activities gives `count=2, truncated=true`; empty range gives `[] / 0 / false`.
- Nulls present, never omitted, after ADR-0011.
- Derived pace: `null` at zero distance (stub-4); unrounded double otherwise (`135.9945602175913`).
- `Instant` serialises as RFC 3339 UTC with `Z`, matching §1.3.
- stdout purity: zero non-JSON lines across a full session; logs go to `fitmcp.log`.
