package com.ticketing;

import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModules;

/**
 * Spring Modulith 경계 검증 — 모듈(catalog·queue·reservation·shared)끼리
 * 상대의 내부(domain·adapter 등)를 직접 참조하면 이 테스트가 깨진다.
 * 3단계에서 서비스로 쪼갤 경계가 이미 지켜지고 있다는 증거다.
 */
class ModularityTest {

    private final ApplicationModules modules = ApplicationModules.of(TicketingApplication.class);

    @Test
    void 모듈_경계를_지킨다() {
        modules.verify();
    }
}
