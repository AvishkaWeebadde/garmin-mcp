# 0005 — `bySport` as an ordered array, not a map

**Status**: Accepted · 2026-08-05

## Context

`summarizePeriod` returns a per-sport breakdown. The obvious model is a map keyed by sport:

```json
"bySport": { "run": { ... }, "ride": { ... } }
```

It is more compact and reads better. It is also unusable for this project's actual deliverable.

- Go randomises map iteration order deliberately. Two calls to the same Go server can emit keys in different orders.
- Java's `HashMap` order is arbitrary but stable, so it will look deterministic in testing and still not match Go.
- JSON object key order carries no meaning, so neither implementation is *wrong* — which is exactly the problem. A diff tool will report a difference that is not a finding, on every single run.

The project's most valuable output is a diff of wire JSON. Anything that generates noise in that diff costs more than its ergonomics are worth.

## Decision

`bySport` is an array of `{ sport, activityCount, distanceMeters, durationSeconds }`, sorted ascending by `sport`. Sports with no activity in the range are omitted.

## Consequences

Consumers index by scanning rather than by key. For a five-member closed enum, this is not a real cost.

The ordering rule is now contractual, so a sort that gets dropped in one implementation is a genuine finding rather than ambiguity — which is the outcome we want.

The same reasoning applies to any future map-shaped field. Default to ordered arrays in this contract.
