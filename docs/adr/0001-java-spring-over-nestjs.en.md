# 0001. Backend language — Java/Spring (over NestJS)
Date: 2026-08-28 · Stage: 1 · Status: decided · [한국어](0001-java-spring-over-nestjs.md)

## Context
A learning/portfolio project by a frontend developer. Learning goals are Kafka, concurrency
control, and DDD/hexagonal architecture. Candidates: Java/Spring vs. NestJS (full-stack TypeScript).

## Options
- **Java 21 + Spring Boot**: the Kafka Java client is the reference implementation, so most
  learning material lives here. Real threads make concurrency experiments intuitive, and JPA
  ships optimistic/pessimistic locking. The Modulith·Resilience4j·ArchUnit·Testcontainers
  ecosystem is mature, and it matches the backend standard of target employers (finance/SI).
  Downside: the frontend uses a different language, so type sharing depends on OpenAPI generation.
- **NestJS**: one TypeScript codebase across web/app, direct type sharing, faster iteration.
  Downsides: Kafka depends on kafkajs (slow maintenance), the single-threaded model forces
  contention experiments through multiple instances, and it differentiates less outside
  Node-centric companies.

## Decision
Java/Spring. The reference material for all three learning goals lives in Java, prior Spring
Boot experience exists, and it matches target employers. TypeScript server experience comes
from the Next.js BFF instead.

## Consequences
Gained: depth in Kafka/concurrency/DDD, interview-market fit. Lost: hand-shared types
(compensated by OpenAPI generation). Revisit if the target employers shift to Node-centric companies.
