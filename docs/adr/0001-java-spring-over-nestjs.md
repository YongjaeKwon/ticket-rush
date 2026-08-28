# 0001. 백엔드 언어 — Java/Spring (NestJS 대신)
날짜: 2026-08-28 · 단계: 1 · 상태: 결정

## 문맥
프론트 개발자의 학습·포트폴리오 프로젝트. 학습 목표는 Kafka, 동시성 제어, DDD/헥사고날. 후보는 Java/Spring과 NestJS(TypeScript 풀스택).

## 선택지
- **Java 21 + Spring Boot**: Kafka Java 클라이언트가 레퍼런스 구현이라 학습 자료가 전부 이쪽. 진짜 스레드로 동시성 실험이 직관적, JPA 낙관적/비관적 락 기본 제공. Modulith·Resilience4j·ArchUnit·Testcontainers 조합 성숙. 지원 대상(금융·SI)의 백엔드 표준. 단점: 프론트와 언어가 갈려 타입 공유는 OpenAPI 생성에 의존.
- **NestJS**: 웹·앱과 TypeScript 하나로 통일, 타입 직접 공유, 개발 속도. 단점: Kafka는 kafkajs 의존(유지보수 느림), 단일 스레드라 경합 실험은 다중 인스턴스로 우회해야 함, Node 위주 회사가 아니면 차별점이 약함.

## 결정
Java/Spring. 학습 목표 세 가지의 레퍼런스가 Java에 있고, 이미 Spring Boot 경험이 있으며, 지원 회사 스택과 맞는다. TS 서버 경험은 Next.js BFF로 확보한다.

## 결과
얻는 것: Kafka·동시성·DDD 학습 깊이, 면접 시장 적합성. 잃는 것: 타입을 손으로 못 공유(→ OpenAPI 자동 생성으로 보완). 다시 볼 조건: 지원 대상이 Node 중심 회사로 바뀌면.
