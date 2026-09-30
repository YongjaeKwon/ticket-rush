package com.ticketing.shared.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.servers.Server;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * openapi.json의 servers를 상대 경로 "/"로 고정한다.
 * 기본값은 요청 Host에서 만들어져, 8080이 아닌 포트에서 재생성하면 servers 한 줄 때문에
 * CI의 계약 diff 검사가 깨졌다. 웹·앱은 API 주소를 환경변수로 받으므로 이 값을 쓰지 않는다.
 */
@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI openApi() {
        return new OpenAPI()
                .info(new Info().title("OpenAPI definition").version("v0"))
                .servers(List.of(new Server().url("/")));
    }
}
