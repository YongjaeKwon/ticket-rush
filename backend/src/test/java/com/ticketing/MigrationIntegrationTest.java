package com.ticketing;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 마이그레이션이 약속한 스키마를 실제로 만드는지 — 실물 MySQL에 Flyway만 돌려 확인한다.
 * (스프링 컨텍스트 없이 가볍게. ddl-auto: validate는 JPA 엔티티가 있는 테이블만 보므로
 *  payment·processed_event처럼 아직 엔티티가 없는 테이블은 여기서 지킨다)
 */
@Testcontainers
class MigrationIntegrationTest {

    @Container
    static final MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.4");

    @BeforeAll
    static void migrate() {
        Flyway.configure()
                .dataSource(mysql.getJdbcUrl(), mysql.getUsername(), mysql.getPassword())
                .load()
                .migrate();
    }

    @Test
    void V4가_payment_테이블을_계약대로_만든다() throws Exception {
        // 이름|타입|NULL 허용 — 특히 pg_tx_id는 거절·실패 행에서 비므로 NULL이어야 한다
        assertThat(columnsOf("payment")).containsExactly(
                "id|bigint|no",
                "reservation_id|bigint|no",
                "amount|int|no",
                "status|varchar(20)|no",
                "pg_tx_id|varchar(200)|yes",
                "created_at|datetime(6)|no");
        // 예매별 시도 이력 조회용 인덱스
        assertThat(indexColumnsOf("payment", "idx_payment_reservation"))
                .containsExactly("reservation_id");
    }

    @Test
    void V4가_processed_event를_계약대로_만든다() throws Exception {
        // event_id는 UUID 문자열(정확히 36자)을 담는다
        assertThat(columnsOf("processed_event")).containsExactly(
                "consumer|varchar(100)|no",
                "event_id|varchar(36)|no",
                "processed_at|datetime(6)|no");
        // 멱등의 핵심 — 같은 (컨슈머, 이벤트)는 두 번 insert될 수 없다
        assertThat(indexColumnsOf("processed_event", "PRIMARY"))
                .containsExactly("consumer", "event_id");
    }

    /** "이름|타입(길이·정밀도 포함)|null 허용" 형태로 컬럼 목록을 뽑는다 — 전부 소문자로 정규화 */
    private List<String> columnsOf(String table) throws Exception {
        return query("""
                SELECT CONCAT(COLUMN_NAME, '|', COLUMN_TYPE, '|', IS_NULLABLE)
                FROM information_schema.COLUMNS
                WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ? ORDER BY ORDINAL_POSITION
                """, table, null);
    }

    private List<String> indexColumnsOf(String table, String index) throws Exception {
        return query("""
                SELECT COLUMN_NAME FROM information_schema.STATISTICS
                WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ? AND INDEX_NAME = ?
                ORDER BY SEQ_IN_INDEX
                """, table, index);
    }

    private List<String> query(String sql, String arg1, String arg2) throws Exception {
        try (Connection connection = DriverManager.getConnection(
                mysql.getJdbcUrl(), mysql.getUsername(), mysql.getPassword());
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, arg1);
            if (arg2 != null) {
                statement.setString(2, arg2);
            }
            List<String> values = new ArrayList<>();
            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next()) {
                    values.add(rs.getString(1).toLowerCase());
                }
            }
            return values;
        }
    }
}
