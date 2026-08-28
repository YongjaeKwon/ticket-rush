plugins {
    java
    id("org.springframework.boot") version "4.0.3"
    id("io.spring.dependency-management") version "1.1.7"
}

group = "com.ticketing"
version = "0.0.1-SNAPSHOT"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

repositories {
    mavenCentral()
}

dependencyManagement {
    imports {
        mavenBom("org.springframework.modulith:spring-modulith-bom:2.0.5")
        // Boot 4 BOM은 Testcontainers 버전을 관리하지 않는다
        mavenBom("org.testcontainers:testcontainers-bom:1.21.3")
    }
}

dependencies {
    // 웹 — Boot 4부터 spring-boot-starter-web은 deprecated, webmvc가 정식 이름
    implementation("org.springframework.boot:spring-boot-starter-webmvc")
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.springframework.boot:spring-boot-starter-data-redis")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    // Boot 4 모듈화로 Flyway 자동 설정이 별도 모듈로 분리됨
    implementation("org.springframework.boot:spring-boot-flyway")
    implementation("org.springframework.modulith:spring-modulith-starter-core")
    // 이벤트 로그(event_publication)는 JDBC 레지스트리 — 공식 DDL을 Flyway로 관리 (V3)
    implementation("org.springframework.modulith:spring-modulith-starter-jdbc")

    runtimeOnly("com.mysql:mysql-connector-j")
    runtimeOnly("org.flywaydb:flyway-mysql")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    // Boot 4에서 TestRestTemplate는 별도 모듈로 분리됨 (RestTemplateBuilder는 restclient 모듈)
    testImplementation("org.springframework.boot:spring-boot-resttestclient")
    testImplementation("org.springframework.boot:spring-boot-restclient")
    testImplementation("org.springframework.boot:spring-boot-testcontainers")
    testImplementation("org.springframework.modulith:spring-modulith-starter-test")
    testImplementation("org.testcontainers:mysql")
    testImplementation("org.testcontainers:junit-jupiter")
    testImplementation("com.tngtech.archunit:archunit-junit5:1.4.1")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.withType<Test> {
    useJUnitPlatform()
}
