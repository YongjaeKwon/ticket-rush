package com.ticketing.payment.adapter.out.persistence;

import com.ticketing.payment.application.port.out.PaymentStore;
import com.ticketing.payment.domain.Payment;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/** payment 테이블 기록. 시도 하나 = 한 행, 갱신 없음이라 JPA 매핑 없이 JdbcClient로 충분하다. */
@Component
class JdbcPaymentStoreAdapter implements PaymentStore {

    private final JdbcClient jdbc;

    JdbcPaymentStoreAdapter(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void save(Payment payment) {
        jdbc.sql("""
                        INSERT INTO payment (reservation_id, amount, status, pg_tx_id, created_at)
                        VALUES (:reservationId, :amount, :status, :pgTxId, :createdAt)
                        """)
                .param("reservationId", payment.reservationId())
                .param("amount", payment.amount())
                .param("status", payment.status().name())
                .param("pgTxId", payment.pgTxId())
                .param("createdAt", payment.createdAt())
                .update();
    }
}
