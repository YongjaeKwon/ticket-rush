package com.ticketing;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

import java.util.TimeZone;

@SpringBootApplication
public class TicketingApplication {

    static {
        // 시각은 전부 UTC (ARCHITECTURE 4-3). JVM 기본 시간대가 KST면
        // JDBC 드라이버·Hibernate가 DATETIME을 오가며 ±9시간을 끼워 넣는다 — 원천 차단.
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
    }

    public static void main(String[] args) {
        SpringApplication.run(TicketingApplication.class, args);
    }
}
