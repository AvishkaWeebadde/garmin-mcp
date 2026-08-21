# 0011 — `@Schema(nullable = true)` on every nullable field

**Status**: Accepted · 2026-08-05

## Context

ADR-0004 requires that unmeasured values serialise as `null` and are never omitted. Contract v1 §1.4 makes it normative.

Spring AI's schema generator emits `{"type":"integer"}` for a boxed `Integer` and has no notion that it might be null. The MCP SDK then validates outgoing `structuredContent` against that generated schema and rejects the payload. Measured, before the fix:

```
Tool (getActivity) output validation failed:
  /averageHeartrateBpm: null found, integer expected
  /calories:            null found, integer expected
  /maxHeartrateBpm:     null found, integer expected
```

`getActivity("stub-2")` — an ordinary ride recorded without a heart-rate strap — returned `isError: true`. So did every list call covering it. The server was unusable for real data while being entirely correct by the contract.

The generator has no `nullable` support of its own — there is no such string in its bytecode. It does, however, install victools' `Swagger2Module` and read `io.swagger.v3.oas.annotations.media.Schema`, and `swagger-annotations-jakarta` is already on the runtime classpath.

## Decision

Annotate every nullable record component with `@Schema(nullable = true)`, and declare `io.swagger.core.v3:swagger-annotations-jakarta` as a direct dependency rather than leaning on the transitive one.

## Consequences

This annotation is **load-bearing, not documentation**. Removing it does not degrade the schema cosmetically; it breaks the tool at runtime for any activity with an unmeasured field. Both domain records say so in their Javadoc, because this is exactly the kind of "unused annotation" a later cleanup would delete.

A Swagger/OpenAPI annotation is now a compile-time dependency of an MCP server that serves no HTTP and has no OpenAPI document. That is incidental complexity imposed by the framework, and it is recorded as finding F-003.

The Go side needs no equivalent: `*int` is nullable in its schema derivation by construction. This is one of the clearer ergonomics differences the project set out to find, and it surfaced in Phase 1 without Go existing yet.
