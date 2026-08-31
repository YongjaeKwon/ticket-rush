# 0002. Spring Boot 4.0 + Modulith 2.0 (over 3.5/1.4)
Date: 2026-08-28 · Stage: 1 · Status: decided · [한국어](0002-spring-boot-4-modulith-2.md)

## Context
ARCHITECTURE 4-1 says "latest stable Spring Boot; record the version in an ADR."
Candidates: Boot 3.5 + Modulith 1.4 (more material) vs. Boot 4.0 + Modulith 2.0 (current stable).

## Options
- **Boot 4.0.3 + Modulith 2.0.5**: the current stable pairing (Modulith 2.0 is compiled
  against Boot 4.0). No 3.x end-of-life worries within the project's lifetime. Downsides:
  most articles still target 3.x, and the reorganized starters (web → webmvc, Flyway split
  into its own module) require some learning.
- Boot 3.5 + Modulith 1.4: plenty of material, but a dated starting point for a new project
  and a future 4.x migration cost.

## Decision
Boot 4.0.3 + Modulith 2.0.5. Starting a new project on the latest stable line has the lowest
total cost, and the reorganized starter structure is itself a learning point.

## Consequences
Gained: current stable stack, no migration debt. Lost: search results are scarce — compensated
by checking official docs via context7. Revisit if a dependency (springdoc etc.) lacks Boot 4 support.
