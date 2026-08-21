# 0003 — camelCase wire field names

**Status**: Accepted · 2026-08-05

## Context

The contract must fix one casing convention for every JSON field name, input and output. snake_case is common in MCP servers in the wild; camelCase is common in JVM and TypeScript ecosystems.

The asymmetry that decides it:

- **Go** never gets naming for free. Exported struct fields are `PascalCase`, so every field needs an explicit `json:"..."` tag regardless of which convention we pick. Cost of either choice: identical.
- **Java** does get naming for free, from record component names. Choosing snake_case throws that away and forces one of: non-idiomatic identifiers (`String start_date`), a `@JsonProperty` on every component, or a naming strategy configured on an `ObjectMapper` we do not fully control — the MCP SDK ships its own via `mcp-json-jackson3`, so a Spring-level `spring.jackson.*` property is not guaranteed to reach it.

Tool **input** names make this sharper. Spring AI derives input schema property names from reflected method parameter names. Matching a snake_case contract would mean declaring Java parameters as `start_date`, or introducing a wrapper object purely to rename things.

## Decision

All wire field names are camelCase: `startDate`, `distanceMeters`, `averageHeartrateBpm`.

## Consequences

Java records and method parameters map to the wire with no annotations and no naming strategy, which removes a whole class of "the mapper we configured was not the mapper that ran" bugs.

Go carries explicit `json` tags. That was always true and is not a cost of this decision.

Relying on reflected parameter names requires `-parameters` at compile time. The Spring Boot parent enables it; if the parent is ever dropped, that flag must be set by hand or input schemas will silently degrade to `arg0`, `arg1`.
