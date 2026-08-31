package com.ticketing;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * 헥사고날 의존 방향을 테스트로 강제한다 (CLAUDE.md 4-2).
 * adapter → application → domain 한 방향. 거꾸로 import하면 빌드가 깨진다.
 */
@AnalyzeClasses(packages = "com.ticketing", importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTest {

    @ArchTest
    static final ArchRule 도메인은_프레임워크를_모른다 =
            noClasses().that().resideInAPackage("..domain..")
                    .should().dependOnClassesThat().resideInAnyPackage(
                            "org.springframework..", "jakarta.persistence..",
                            "tools.jackson..", "com.fasterxml..");

    @ArchTest
    static final ArchRule 도메인은_바깥_계층을_모른다 =
            noClasses().that().resideInAPackage("..domain..")
                    .should().dependOnClassesThat().resideInAnyPackage("..application..", "..adapter..");

    @ArchTest
    static final ArchRule 애플리케이션은_어댑터를_모른다 =
            noClasses().that().resideInAPackage("..application..")
                    .should().dependOnClassesThat().resideInAPackage("..adapter..");
}
