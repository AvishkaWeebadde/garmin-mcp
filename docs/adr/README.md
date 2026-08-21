# Architecture Decision Records

One decision per file, numbered, never renumbered. A superseded ADR stays in place with its status changed and a pointer forward — the record of *why we thought that at the time* is the point.

Format: Context / Decision / Consequences. Keep them short. If an ADR needs more than a screen, it is probably two decisions.

| # | Title | Status |
|---|---|---|
| [0001](0001-claude-authors-the-java-implementation.md) | Claude authors the Java implementation | Accepted |
| [0002](0002-spring-boot-4.1.0-as-parent.md) | Spring Boot 4.1.0 as parent, not 4.0.x | Accepted |
| [0003](0003-camelcase-wire-field-names.md) | camelCase wire field names | Accepted |
| [0004](0004-nulls-always-present.md) | Nulls always present, never omitted | Accepted |
| [0005](0005-bysport-as-ordered-array.md) | `bySport` as an ordered array, not a map | Accepted |
| [0006](0006-error-channel-split.md) | Two error channels: protocol vs `isError` | Accepted |
| [0007](0007-maven-wrapper-build-in-wsl.md) | Maven Wrapper, build in WSL on JDK 21 | Accepted |
| [0008](0008-explicit-output-schema-generation.md) | `generateOutputSchema = true` set explicitly | Accepted |
| [0009](0009-accept-2025-11-25-protocol-ceiling.md) | Accept the Java SDK's 2025-11-25 ceiling | Accepted |
| [0010](0010-lowercase-enum-constants.md) | Lowercase Java enum constants for `Sport` | Accepted |
| [0011](0011-schema-nullable-annotation.md) | `@Schema(nullable = true)` on every nullable field | Accepted |
| [0012](0012-fit-file-provider.md) | `.FIT` file provider as the first real `ActivityProvider` | Accepted |
