# 0007 — Maven Wrapper, build in WSL on JDK 21

**Status**: Accepted · 2026-08-05

## Context

The machine's state as of 2026-08-05:

- Windows: JDK 17.0.10, Maven 3.8.8
- WSL (Ubuntu 24.04): Temurin JDK 21.0.12, **no Maven**

`CLAUDE.md` plans to run the jar in WSL so Claude Desktop can launch it via `wsl.exe`. That leaves a choice about where it gets *built*, and an open question about whether Maven 3.8.8 is new enough for Spring Boot 4.1 — neither `spring-boot-starter-parent` nor `spring-boot-maven-plugin` declares a `<prerequisites>` or enforcer rule in its published pom, so there is no evidence either way.

`sudo` on this WSL install requires a password, so `apt install maven` is not available to an automated step.

## Decision

Commit the Maven Wrapper and build with `./mvnw` inside WSL on JDK 21. The wrapper pins the Maven version in `.mvn/wrapper/maven-wrapper.properties` and downloads it on first use into `~/.m2/wrapper`.

Build and runtime both live in WSL. Windows Maven 3.8.8 and JDK 17 are not used by this project.

## Consequences

The "is 3.8.8 new enough" question is retired rather than answered — the build no longer depends on it. That is a better outcome than a verified answer, because it also survives the next Maven bump.

Build and runtime now use the same JVM, so `maven.compiler.release` matching the runtime is checkable rather than assumed.

Cost: a first build needs network access to fetch the pinned Maven distribution, and the wrapper files are committed to the repo. Both are the normal price of a wrapper and are worth it here.

Spring Boot 4.1's parent sets `<java.version>17</java.version>` as a baseline. Building on 21 with `release=21` is a deliberate step past that baseline, matching `CLAUDE.md`'s "21 recommended". If the Go side ever needs the Java jar to run on 17, this is the knob.
