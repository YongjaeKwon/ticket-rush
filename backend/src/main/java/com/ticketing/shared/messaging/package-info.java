/**
 * Outbox 기록·릴레이 + 멱등 장부. OutboxWriter(발행 입구)와 ProcessedEventLedger(장부 SQL)는
 * 발행·소비하는 모듈들(reservation·payment·queue)이 함께 쓰므로 의도적으로 공개한다.
 */
@org.springframework.modulith.NamedInterface("messaging")
package com.ticketing.shared.messaging;
