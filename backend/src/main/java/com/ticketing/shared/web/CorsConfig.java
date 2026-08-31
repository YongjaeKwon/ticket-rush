package com.ticketing.shared.web;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * 웹 프론트는 SSE를 Next 서버를 거치지 않고 Spring에 직결한다 (ARCHITECTURE 9절).
 * 브라우저가 다른 포트(:3000)에서 직접 부르므로 CORS 허용이 필요하다.
 */
@Configuration
class CorsConfig implements WebMvcConfigurer {

    private final String allowedOrigin;

    CorsConfig(@Value("${cors.allowed-origin:http://localhost:3000}") String allowedOrigin) {
        this.allowedOrigin = allowedOrigin;
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/api/**")
                .allowedOrigins(allowedOrigin)
                .allowedMethods("GET", "POST", "DELETE")
                .allowedHeaders("*");
    }
}
