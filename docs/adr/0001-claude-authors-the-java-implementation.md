# 0001 — Claude authors the Java implementation

**Status**: Accepted · 2026-08-05

## Context

`CLAUDE.md` states plainly: *"I am writing this code. You are not."* The project's stated purpose is learning MCP and learning Go, and code written for you teaches you less than code you wrote.

The same agreement carves out an exception: *"unless I explicitly ask."* On 2026-08-05 that exception was invoked for the whole Java implementation.

## Decision

Claude writes the Phase 1 Java implementation. The working agreement is otherwise unchanged and still governs Phase 2 (Go) and all review.

Constraints accepted alongside it:

- Contract first. `contract/tools.md` was written and frozen before any Java code, because an implementation with nothing to conform to defeats the project's purpose.
- Code optimised for reviewability over cleverness — this is meant to be read, argued with, and rewritten.
- Every non-obvious decision gets an ADR, so the reasoning is inspectable rather than embedded in code that arrived fully formed.

## Consequences

Java is the language already known, so the learning cost is lower here than it would be in Go — the transferable content of Phase 1 is the *contract* and the *protocol*, both of which survive being handed over.

The risk is real and worth naming: Phase 3's value depends on understanding both implementations well enough to judge whether a divergence is spec drift, framework drift, or a mistake. If the Java side is a black box by then, Phase 3 degrades into "the JSON differs." Mitigation is the ADR trail plus review, not the code itself.

Phase 2 Go remains hand-written under the original agreement. If that changes, it needs its own ADR.
