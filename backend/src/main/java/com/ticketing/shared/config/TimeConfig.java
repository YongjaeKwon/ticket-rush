package com.ticketing.shared.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/** 시각은 전부 UTC. 서비스는 Clock을 주입받아 테스트에서 시간을 고정할 수 있다. */
@Configuration
public class TimeConfig {

    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}
