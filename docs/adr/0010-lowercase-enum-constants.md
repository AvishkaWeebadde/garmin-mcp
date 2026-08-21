# 0010 — Lowercase Java enum constants for `Sport`

**Status**: Accepted · 2026-08-05

## Context

Contract v1 §1.5 fixes the wire form of `sport` as lowercase: `run`, `ride`, `swim`, `walk`, `other`.

Two components disagree about how a Java enum becomes JSON, and they are both in the request path:

- **The MCP SDK's Jackson mapper** serialises the payload and honours `@JsonValue`, producing `"run"`.
- **Spring AI's schema generator** builds the published schema with victools, and does *not* enable `JacksonOption.FLATTENED_ENUMS_FROM_JSONVALUE`. It reads the constant *names*, producing `enum: ["RUN", …]`.

With conventional `RUN` constants the server contradicts itself, and the SDK's own validation catches it. Measured, before the fix:

```
input : /sport: does not have a value in the enumeration ["RUN","RIDE","SWIM","WALK","OTHER"]
output: /activities/0/sport: does not have a value in the enumeration ["RUN",…]
```

Every single call failed — a client sending the contractual `"run"` was rejected on input, and any response was rejected on output.

## Decision

Name the constants for the wire form: `run`, `ride`, `swim`, `walk`, `other`. Keep `@JsonValue` even though it is now redundant, so a future rename cannot silently change the payload.

Alternatives rejected:

- **Uppercase on the wire.** Contradicts the frozen contract, and would drag Go along with it for a purely Java-side reason.
- **`@Schema(allowableValues = …)` on the enum.** Might work, but relies on unverified victools/Swagger interaction, where lowercase constants are deterministic.
- **Model `sport` as `String`.** Loses the enum constraint from the generated schema, which is one of the more interesting things to compare against Go.

## Consequences

Java naming convention is broken, visibly, in a domain type. This is the correct trade: the alternative is a server that cannot answer a single call.

The underlying inconsistency is recorded as finding F-002 in `notes/divergence.md`. It is a Spring AI defect, not a spec matter — the Go SDK derives schema and payload from the same struct tags and cannot disagree with itself this way.

If Spring AI later enables `FLATTENED_ENUMS_FROM_JSONVALUE`, this ADR can be superseded and the constants restored to convention.
