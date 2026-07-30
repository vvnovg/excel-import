val integrationTest: SourceSet by sourceSets.creating {
    compileClasspath += sourceSets["main"].output + sourceSets["test"].output
    runtimeClasspath += output + compileClasspath
}

val performanceTest: SourceSet by sourceSets.creating {
    compileClasspath += sourceSets["main"].output + sourceSets["test"].output
    runtimeClasspath += output + compileClasspath
}

configurations {
    named("integrationTestImplementation") { extendsFrom(configurations["testImplementation"]) }
    named("integrationTestRuntimeOnly") { extendsFrom(configurations["testRuntimeOnly"]) }
    named("performanceTestImplementation") { extendsFrom(configurations["testImplementation"]) }
    named("performanceTestRuntimeOnly") { extendsFrom(configurations["testRuntimeOnly"]) }
}

dependencies {
    api(libs.poi)
    api(libs.poi.ooxml)
    api(libs.jakarta.validation)
    api(libs.slf4j.api)

    implementation(libs.hibernate.validator)

    compileOnly(libs.postgresql)

    testImplementation(libs.postgresql)
    testImplementation(libs.mockito)
    testImplementation(libs.mockito.junit)
    testRuntimeOnly(libs.logback.classic)
    // POI логирует через log4j-api; без моста тесты печатают
    // "ERROR Log4j API could not find a logging provider". Мост маршрутизирует
    // log4j2 -> SLF4J -> logback (уже testRuntimeOnly выше). Только для тестов,
    // в main рантайм не добавляется.
    testRuntimeOnly(libs.log4j.to.slf4j)

    "integrationTestImplementation"(libs.testcontainers.postgresql)
    "integrationTestImplementation"(libs.testcontainers.junit)
    "integrationTestImplementation"(libs.postgresql)
}

val integrationTestTask = tasks.register<Test>("integrationTest") {
    description = "Интеграционные тесты на Testcontainers PostgreSQL. Требует Docker."
    group = "verification"
    testClassesDirs = integrationTest.output.classesDirs
    classpath = integrationTest.runtimeClasspath
    shouldRunAfter(tasks.named("test"))
}

tasks.named("check") {
    dependsOn(integrationTestTask)
}

tasks.register<Test>("performanceTest") {
    description = "Перф-тесты. В check не входит, запускается вручную."
    group = "verification"
    testClassesDirs = performanceTest.output.classesDirs
    classpath = performanceTest.runtimeClasspath
    maxHeapSize = "256m"
}
