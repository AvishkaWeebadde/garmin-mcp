# 0008 — `generateOutputSchema = true` set explicitly

**Status**: Accepted · 2026-08-05

## Context

The contract requires every tool to publish an `outputSchema` and return `structuredContent`.

Spring AI's annotation does not do this by default. Read from the bytecode rather than the documentation:

```
public abstract boolean generateOutputSchema();
    AnnotationDefault: false
```

So `@McpTool` without further configuration produces a tool with **no** `outputSchema`, and a result with a text block only.

## Decision

Set `generateOutputSchema = true` explicitly on all three `@McpTool` methods, rather than relying on any default.

## Consequences

Had this been left off, `tools/list` would have omitted `outputSchema` entirely and `tools/call` would have returned no `structuredContent`. Against a Go implementation that derives output schemas from return types, this would have surfaced in Phase 3 as a large and confusing divergence with a boring cause.

Writing it explicitly on every method — even though one value would do — is deliberate. The flag is contract-bearing, and a default that flips in a later Spring AI release should not be able to silently change our wire format.

What the generated schema actually *contains* is a separate question and a genuine Phase 3 subject. Spring AI derives it from the Java return type; the Go SDK derives it from a Go struct. Both are 2025-06-18+ features present in both protocol revisions, so any difference here is framework drift, not spec drift — see `contract/tools.md` §4.
