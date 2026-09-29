package com.ticketing.payment.application.port.out;

import com.ticketing.payment.domain.Payment;

/** 결제 시도 기록 저장 (payment 테이블). */
public interface PaymentStore {

    void save(Payment payment);
}
