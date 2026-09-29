/**
 * Outbox 기록·릴레이. OutboxWriter는 발행하는 모듈들(reservation·payment·queue)이 함께 쓰므로 공개한다.
 */
@org.springframework.modulith.NamedInterface("messaging")
package com.ticketing.shared.messaging;
