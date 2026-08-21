# 0009 — Accept the Java SDK's 2025-11-25 protocol ceiling

**Status**: Accepted · 2026-08-05

## Context

`CLAUDE.md` assumed the Java side speaks MCP 2025-11-25. Verified from the shipped bytecode of `io.modelcontextprotocol.sdk:mcp-core:2.0.0`:

```java
public interface io.modelcontextprotocol.spec.ProtocolVersions {
  String MCP_2024_11_05 = "2024-11-05";
  String MCP_2025_03_26 = "2025-03-26";
  String MCP_2025_06_18 = "2025-06-18";
  String MCP_2025_11_25 = "2025-11-25";
}
```

There is no `2026-07-28` constant. The Java SDK cannot speak the current spec revision, and no configuration will make it.

The Go side targets 2026-07-28 via go-sdk v1.7.0.

## Decision

Accept the ceiling. The Java server speaks 2025-11-25 and the Go server speaks 2026-07-28, and the resulting divergence is recorded as a Phase 3 finding rather than engineered around.

Rejected alternatives:

- **Pin Go to an older revision** so both match. This would delete the most interesting axis of the comparison. The two implementations exist precisely to span two protocol generations.
- **Hand-roll 2026-07-28 support on the Java side.** That is a project about the Java SDK, not about MCP.
- **Wait for a Java SDK release.** Phase 2 must start within ~3 weeks of Phase 1 finishing or the comparison decays. Waiting on an unscheduled release breaks that.

## Consequences

`contract/tools.md` §4 now enumerates the surface expected to differ for spec reasons — session lifecycle, `Mcp-Session-Id`, and tool schema dialect. Phase 3 classifies each divergence as spec drift, framework drift, or a mistake, and this ADR is what makes the first category legible.

The one to watch is schema dialect. 2026-07-28 defines tool schemas against full JSON Schema 2020-12; the 2025-11-25 SDK may emit a narrower subset. The contract deliberately uses `$defs`, `$ref`, `pattern`, `additionalProperties`, and union types like `["string", "null"]` — if the Java SDK drops or rewrites any of them, **that is the finding**, and the contract does not get weakened to hide it.

Revisit if a Java SDK with 2026-07-28 support ships before Phase 3 is written up. Superseding this ADR then would be a good problem to have.
