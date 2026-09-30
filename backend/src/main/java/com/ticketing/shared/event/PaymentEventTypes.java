package com.ticketing.shared.event;

/**
 * 결제 결과 이벤트 타입명 — payment(발행)와 reservation(소비)이 공유하는 교차 모듈 계약.
 * 문자열을 각자 들고 있으면 한쪽만 바꾸는 표류가 조용히 소비를 죽인다 — 상수로 컴파일 타임에 고정한다.
 */
public final class PaymentEventTypes {

    public static final String PAYMENT_APPROVED = "PaymentApproved";
    public static final String PAYMENT_DECLINED = "PaymentDeclined";
    public static final String PAYMENT_FAILED = "PaymentFailed";

    private PaymentEventTypes() {
    }
}
