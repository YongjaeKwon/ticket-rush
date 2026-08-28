package com.ticketing.catalog.adapter.in.web;

import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.filter.ShallowEtagHeaderFilter;

/** 좌석 배치·상태 응답에 컨텐츠 기반 ETag를 붙인다 → If-None-Match 시 304. */
@Configuration
class CatalogWebConfig {

    @Bean
    FilterRegistrationBean<ShallowEtagHeaderFilter> catalogEtagFilter() {
        var registration = new FilterRegistrationBean<>(new ShallowEtagHeaderFilter());
        registration.addUrlPatterns("/api/schedules/*");
        return registration;
    }
}
