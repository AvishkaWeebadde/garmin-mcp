# 0006 — Two error channels: protocol vs `isError`

**Status**: Accepted · 2026-08-05

## Context

MCP offers two ways for a tool call to go wrong: a JSON-RPC protocol error, or a successful result carrying `isError: true`. Servers in the wild use them inconsistently, and picking arbitrarily would make the Java/Go comparison meaningless — both could be internally consistent and still disagree.

## Decision

Split on **who got it wrong**.

| Condition | Channel |
|---|---|
| Unknown tool name | Protocol error |
| Input fails schema validation | Protocol error (framework-raised) |
| `activityId` not found | `isError: true` |
| `endDate` before `startDate` | `isError: true` |
| Provider unreachable | `isError: true` |

The first two are the *caller* violating the protocol. The last three are the tool executing correctly and having nothing good to report.

Message text for the three `isError` cases is contractual and fixed verbatim in `contract/tools.md` §1.7.

## Consequences

The model consuming this server can recover from a bad date range by rewording, because it receives the message as tool output rather than a transport failure. That is the practical argument for the split and it is the one that matters.

`endDate` before `startDate` is the interesting case. It is arguably a schema violation, and JSON Schema cannot express the cross-field constraint, so it would otherwise fall through to whatever each implementation happens to do. Assigning it explicitly to `isError` removes the ambiguity.

Fixing the message strings makes them testable, and makes a wording difference in Phase 3 a real finding. The cost is that changing a message is a contract change requiring both implementations to move together.
