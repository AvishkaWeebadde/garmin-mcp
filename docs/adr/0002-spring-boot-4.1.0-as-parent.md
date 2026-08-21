# 0002 — Spring Boot 4.1.0 as parent, not 4.0.x

**Status**: Accepted · 2026-08-05

## Context

`CLAUDE.md` pinned "Spring AI 2.0.0 GA requires Spring Boot 4.0". Maven Central has both 4.0.7 and 4.1.0 published.

Reading the published pom rather than trusting the note:

```
spring-ai-starter-mcp-server:2.0.0
  └── org.springframework.boot:spring-boot-starter:4.1.0
```

Spring AI 2.0.0 was built and released against Boot **4.1.0**. Its transitive `spring-ai-autoconfigure-mcp-server-common` likewise pulls `spring-boot-autoconfigure:4.1.0` and `spring-web:7.0.8`.

## Decision

Use `spring-boot-starter-parent:4.1.0` as the Maven parent, with `spring-ai-bom:2.0.0` imported for dependency management.

## Consequences

Declaring 4.0.7 as parent would not have failed loudly. It would have let the parent's dependency management pin Boot artifacts to 4.0.7 while Spring AI's transitives ask for 4.1.0 — the classic near-miss where the build succeeds and something misbehaves at runtime.

The pinned fact in `CLAUDE.md` is now corrected to say 4.1.0. This is the second pinned fact that drifted, after the go-sdk version; the "verify, do not answer from memory" instruction is earning its place.
