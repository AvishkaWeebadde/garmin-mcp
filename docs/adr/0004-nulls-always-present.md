# 0004 — Nulls always present, never omitted

**Status**: Accepted · 2026-08-05

## Context

An activity recorded without a heart-rate strap has no average heart rate. Three encodings are available: omit the key, emit `null`, or emit a zero value.

The two runtimes default differently, and both defaults are invisible at the call site:

- Jackson serialises `null` fields **by default**, but flips to omitting them the moment anyone adds `@JsonInclude(NON_NULL)` — a change that reads like tidying up.
- Go's `encoding/json` omits nothing by default either, but `omitempty` is so idiomatic that it tends to be applied reflexively — and it conflates `null`, `0`, and `""`, which for `averageHeartrateBpm` are three different facts.

Left unstated, this produces a Phase 3 diff that looks like a bug in one implementation but is really a defaulting difference neither author noticed.

## Decision

Every field declared in an output schema is always present. Unknown, unmeasured, or not-applicable values are `null`. Arrays are never null; empty is `[]`.

Concretely: no `@JsonInclude(NON_NULL)` anywhere in the Java tree, and no `omitempty` on any contract-bearing struct field in Go.

One deliberate exception, recorded in the contract: `bySport` omits sports with no activity. That is an absent *array element*, not an absent field.

## Consequences

Responses are slightly larger and carry keys that are always null for the stub provider. Accepted.

Nullable fields must be boxed in Java — `Integer`, not `int`. A primitive `int` cannot express "not recorded" and would silently emit `0`, which under this contract means "measured, and it was zero". The distinction is enforced by type choice, not by discipline.

This is the rule most likely to be broken by a well-meaning later edit. It is the first item on the Phase 3 conformance checklist for that reason.
