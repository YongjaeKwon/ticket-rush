package com.ticketing.shared.web;

import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;

/** 상태를 바꾸는 예매 API에만 멱등 필터를 건다. */
@Configuration
class IdempotencyFilterConfig {

    @Bean
    FilterRegistrationBean<IdempotencyFilter> idempotencyFilter(StringRedisTemplate redis) {
        var registration = new FilterRegistrationBean<>(new IdempotencyFilter(redis));
        registration.addUrlPatterns("/api/reservations", "/api/reservations/*");
        return registration;
    }
}
