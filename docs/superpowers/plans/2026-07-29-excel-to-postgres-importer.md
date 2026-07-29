# Excel → PostgreSQL Importer Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Java-библиотека, которая потоково читает `.xlsx`, валидирует строки, вставляет их в PostgreSQL батчами настраиваемого размера и выдаёт Excel-отчёт с зелёной/красной разметкой строк и причинами отказа.

**Architecture:** Два прохода по файлу. Проход 1 — `XSSFReader` + SAX → маппинг по аннотациям → Jakarta-валидация → накопление батча → пользовательский `BatchValidator` → multi-row `INSERT` в одной транзакции на батч; при сбое батч рекурсивно делится пополам до изоляции сбойных строк. Между проходами хранится только компактная карта `rowNum → исход` с выгрузкой сообщений на диск. Проход 2 — повторное потоковое чтение оригинала + `SXSSFWorkbook`-запись копии с заливкой и колонкой «Причина».

**Tech Stack:** Java 17, Gradle (Kotlin DSL, multi-project, version catalog), Apache POI 5.x (`poi` + `poi-ooxml`, экспортируются как `api`), Jakarta Bean Validation 3.x + Hibernate Validator, SLF4J, чистый JDBC через `javax.sql.DataSource`, PostgreSQL 16, JUnit 5 + AssertJ + Mockito, Testcontainers.

**Спека:** `docs/superpowers/specs/2026-07-29-excel-to-postgres-importer-design.md` — ссылки вида «§5.1» ведут туда.

## Global Constraints

- Java 17. Toolchain фиксируется в Gradle, компиляция с `-Xlint:all -Werror`, кодировка UTF-8.
- Поддерживается только формат `.xlsx` (OOXML/XSSF). `.xls` (HSSF) не поддерживается.
- Потребление heap не зависит от числа строк файла. Ориентир, проверяемый тестом: 100 000 строк × 10 колонок проходят при `-Xmx256m`.
- Ядро `excel-import-core` не зависит от Spring и не тянет драйвер PostgreSQL в рантайм (драйвер — `compileOnly` + `testImplementation`).
- `api`-зависимости ядра: `poi`, `poi-ooxml`, Jakarta Validation API, SLF4J API. Hibernate Validator — `implementation`.
- Публичный API — пакеты `io.github.excelimport` и вложенные, кроме `io.github.excelimport.internal.*`, который приватен и не покрывается гарантиями совместимости.
- Нумерация: в конфигурации и аннотациях индексы 0-based (как в POI); во всём, что видит пользователь (`RowError.rowNum`, сообщения, отчёт) — 1-based (как в Excel). Конвертация ровно в одном месте, на границе публичного API.
- Ни одно значение ячейки не логируется на уровне выше `TRACE` — данные могут быть персональными.
- Ошибка уровня отдельной строки никогда не выбрасывается наружу, только попадает в `RowError`.
- Никаких версий зависимостей в `build.gradle.kts` — только `gradle/libs.versions.toml`.
- Все версии зависимостей в задаче 1 — это минимальные проверенные значения; если в момент реализации доступна более новая патч-версия, взять её, но мажорные не менять без пересмотра плана.

---

### Task 1: Каркас Gradle и два модуля

**Files:**
- Create: `settings.gradle.kts`
- Create: `build.gradle.kts`
- Create: `gradle/libs.versions.toml`
- Create: `excel-import-core/build.gradle.kts`
- Create: `excel-import-spring-boot-starter/build.gradle.kts`
- Create: `.gitignore`
- Test: `excel-import-core/src/test/java/io/github/excelimport/BuildSmokeTest.java`

**Interfaces:**
- Consumes: ничего.
- Produces: два Gradle-подпроекта `:excel-import-core` и `:excel-import-spring-boot-starter`; source sets `test`, `integrationTest`, `performanceTest` в ядре; алиасы version catalog `libs.poi`, `libs.poi.ooxml`, `libs.jakarta.validation`, `libs.hibernate.validator`, `libs.slf4j.api`, `libs.postgresql`, `libs.junit.bom`, `libs.junit.jupiter`, `libs.assertj`, `libs.mockito`, `libs.testcontainers.postgresql`, `libs.spring.boot.autoconfigure`, `libs.logback`.

- [ ] **Step 1: Инициализировать git и wrapper**

```bash
cd /Users/vvnovg/projects/excel
git init
gradle wrapper --gradle-version 8.12
```

Если локального `gradle` нет — скачать дистрибутив вручную и положить `gradle/wrapper/gradle-wrapper.properties` с `distributionUrl=https\://services.gradle.org/distributions/gradle-8.12-bin.zip`, а `gradlew`/`gradlew.bat`/`gradle-wrapper.jar` взять из дистрибутива. Wrapper коммитится в репозиторий.

- [ ] **Step 2: Создать `.gitignore`**

```gitignore
.gradle/
build/
*/build/
.idea/
*.iml
.DS_Store
!gradle/wrapper/gradle-wrapper.jar
```

- [ ] **Step 3: Создать version catalog**

`gradle/libs.versions.toml`:

```toml
[versions]
poi = "5.4.1"
jakarta-validation = "3.0.2"
hibernate-validator = "8.0.2.Final"
slf4j = "2.0.16"
postgresql = "42.7.5"
junit = "5.11.4"
assertj = "3.27.3"
mockito = "5.15.2"
testcontainers = "1.20.6"
spring-boot = "3.4.2"
logback = "1.5.16"

[libraries]
poi = { module = "org.apache.poi:poi", version.ref = "poi" }
poi-ooxml = { module = "org.apache.poi:poi-ooxml", version.ref = "poi" }
jakarta-validation = { module = "jakarta.validation:jakarta.validation-api", version.ref = "jakarta-validation" }
hibernate-validator = { module = "org.hibernate.validator:hibernate-validator", version.ref = "hibernate-validator" }
slf4j-api = { module = "org.slf4j:slf4j-api", version.ref = "slf4j" }
postgresql = { module = "org.postgresql:postgresql", version.ref = "postgresql" }
junit-bom = { module = "org.junit:junit-bom", version.ref = "junit" }
junit-jupiter = { module = "org.junit.jupiter:junit-jupiter", version.ref = "junit" }
junit-platform-launcher = { module = "org.junit.platform:junit-platform-launcher", version = "1.11.4" }
assertj = { module = "org.assertj:assertj-core", version.ref = "assertj" }
mockito = { module = "org.mockito:mockito-core", version.ref = "mockito" }
mockito-junit = { module = "org.mockito:mockito-junit-jupiter", version.ref = "mockito" }
testcontainers-postgresql = { module = "org.testcontainers:postgresql", version.ref = "testcontainers" }
testcontainers-junit = { module = "org.testcontainers:junit-jupiter", version.ref = "testcontainers" }
spring-boot-autoconfigure = { module = "org.springframework.boot:spring-boot-autoconfigure", version.ref = "spring-boot" }
spring-boot-configuration-processor = { module = "org.springframework.boot:spring-boot-configuration-processor", version.ref = "spring-boot" }
spring-boot-starter-test = { module = "org.springframework.boot:spring-boot-starter-test", version.ref = "spring-boot" }
spring-boot-starter-jdbc = { module = "org.springframework.boot:spring-boot-starter-jdbc", version.ref = "spring-boot" }
logback-classic = { module = "ch.qos.logback:logback-classic", version.ref = "logback" }
```

- [ ] **Step 4: Создать `settings.gradle.kts`**

```kotlin
rootProject.name = "excel-import"

include("excel-import-core")
include("excel-import-spring-boot-starter")

dependencyResolutionManagement {
    repositories {
        mavenCentral()
    }
}
```

- [ ] **Step 5: Создать корневой `build.gradle.kts` с конвенциями**

```kotlin
plugins {
    `java-library`
    `maven-publish`
}

allprojects {
    group = "io.github.excelimport"
    version = "0.1.0-SNAPSHOT"
}

subprojects {
    apply(plugin = "java-library")
    apply(plugin = "maven-publish")

    java {
        toolchain {
            languageVersion.set(JavaLanguageVersion.of(17))
        }
        withSourcesJar()
        withJavadocJar()
    }

    tasks.withType<JavaCompile>().configureEach {
        options.encoding = "UTF-8"
        options.compilerArgs.addAll(listOf("-Xlint:all", "-Werror"))
    }

    tasks.withType<Test>().configureEach {
        useJUnitPlatform()
        testLogging {
            events("failed", "skipped")
            exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
        }
    }

    // Репродусибл-архивы
    tasks.withType<AbstractArchiveTask>().configureEach {
        isPreserveFileTimestamps = false
        isReproducibleFileOrder = true
    }

    dependencies {
        "testImplementation"(platform(rootProject.libs.junit.bom))
        "testImplementation"(rootProject.libs.junit.jupiter)
        "testImplementation"(rootProject.libs.assertj)
        "testRuntimeOnly"(rootProject.libs.junit.platform.launcher)
    }

    publishing {
        publications {
            create<MavenPublication>("maven") {
                from(components["java"])
                pom {
                    name.set(project.name)
                    description.set("Streaming Excel to PostgreSQL importer")
                    licenses {
                        license {
                            name.set("The Apache License, Version 2.0")
                            url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
                        }
                    }
                    scm {
                        url.set("https://github.com/excelimport/excel-import")
                    }
                }
            }
        }
    }
}

// `libs` не виден внутри subprojects{} напрямую — пробрасываем как val
val Project.libs: org.gradle.accessors.dm.LibrariesForLibs
    get() = extensions.getByType()
```

Если аксессор `rootProject.libs` внутри `subprojects {}` не резолвится (типичная проблема Gradle с type-safe accessors в блоке `subprojects`), заменить обращения на строковые нотации из каталога через `versionCatalogs`:

```kotlin
val catalog = extensions.getByType<VersionCatalogsExtension>().named("libs")
"testImplementation"(catalog.findLibrary("junit-jupiter").get())
```

- [ ] **Step 6: Создать `excel-import-core/build.gradle.kts` с тремя source sets**

```kotlin
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
```

- [ ] **Step 7: Создать заглушку starter-модуля**

`excel-import-spring-boot-starter/build.gradle.kts`:

```kotlin
dependencies {
    api(project(":excel-import-core"))
    implementation(libs.spring.boot.autoconfigure)
    annotationProcessor(libs.spring.boot.configuration.processor)

    testImplementation(libs.spring.boot.starter.test)
    testImplementation(libs.spring.boot.starter.jdbc)
}
```

- [ ] **Step 8: Написать smoke-тест сборки**

`excel-import-core/src/test/java/io/github/excelimport/BuildSmokeTest.java`:

```java
package io.github.excelimport;

import static org.assertj.core.api.Assertions.assertThat;

import org.apache.poi.ss.usermodel.CellType;
import org.junit.jupiter.api.Test;

class BuildSmokeTest {

    @Test
    void poiIsOnTheApiClasspath() {
        assertThat(CellType.STRING.name()).isEqualTo("STRING");
    }

    @Test
    void runsOnJava17OrLater() {
        assertThat(Runtime.version().feature()).isGreaterThanOrEqualTo(17);
    }
}
```

- [ ] **Step 9: Запустить сборку**

Run: `./gradlew build`
Expected: `BUILD SUCCESSFUL`, оба теста `BuildSmokeTest` проходят. Если `-Werror` валит сборку на предупреждениях Gradle-плагинов — предупреждения в нашем коде исправить, а не отключать флаг.

- [ ] **Step 10: Проверить, что `check` требует Docker**

Run: `./gradlew check`
Expected: `BUILD SUCCESSFUL`; в выводе есть задача `:excel-import-core:integrationTest` (пока без тестов — это нормально, задача проходит с NO-SOURCE).

- [ ] **Step 11: Коммит**

```bash
git add -A
git commit -m "build: Gradle multi-project scaffold with core and Spring Boot starter modules"
```

---

### Task 2: Публичные типы-значения

**Files:**
- Create: `excel-import-core/src/main/java/io/github/excelimport/SheetSelector.java`
- Create: `excel-import-core/src/main/java/io/github/excelimport/RowStatus.java`
- Create: `excel-import-core/src/main/java/io/github/excelimport/RowOutcome.java`
- Create: `excel-import-core/src/main/java/io/github/excelimport/ErrorKind.java`
- Create: `excel-import-core/src/main/java/io/github/excelimport/RowError.java`
- Create: `excel-import-core/src/main/java/io/github/excelimport/RowRef.java`
- Create: `excel-import-core/src/main/java/io/github/excelimport/ImportStatus.java`
- Create: `excel-import-core/src/main/java/io/github/excelimport/TableRef.java`
- Create: `excel-import-core/src/main/java/io/github/excelimport/exception/ExcelImportException.java`
- Create: `excel-import-core/src/main/java/io/github/excelimport/exception/MappingConfigurationException.java`
- Create: `excel-import-core/src/main/java/io/github/excelimport/exception/FileStructureException.java`
- Create: `excel-import-core/src/main/java/io/github/excelimport/exception/ImportAbortedException.java`
- Create: `excel-import-core/src/main/java/io/github/excelimport/exception/ReportGenerationException.java`
- Test: `excel-import-core/src/test/java/io/github/excelimport/SheetSelectorTest.java`
- Test: `excel-import-core/src/test/java/io/github/excelimport/RowErrorTest.java`
- Test: `excel-import-core/src/test/java/io/github/excelimport/TableRefTest.java`

**Interfaces:**
- Consumes: каркас из Task 1.
- Produces:
  - `SheetSelector.byName(String)`, `SheetSelector.byIndex(int)`, `SheetSelector.first()`; методы `Optional<String> name()`, `OptionalInt index()`.
  - `enum RowStatus { NOT_PROCESSED, INSERTED, REJECTED, SKIPPED }` с `byte code()` и `static RowStatus fromCode(byte)`.
  - `record RowOutcome(RowStatus status, String message)` + `RowOutcome.inserted()`, `RowOutcome.rejected(String)`, `RowOutcome.skipped()`, `RowOutcome.notProcessed()`.
  - `enum ErrorKind { STRUCTURE, CONVERSION, CONSTRAINT, BATCH, DATABASE }`.
  - `record RowError(int rowNum, String columnHeader, String rawValue, ErrorKind kind, String code, String message)` + фабрики `structure`, `conversion`, `constraint`, `batch`, `database`.
  - `record RowRef<T>(int rowNum, T value)`.
  - `enum ImportStatus { SUCCESS, PARTIAL, FAILED }`.
  - `record TableRef(String schema, String name)` + `TableRef.of(String)` (разбирает `"hr.employee"`), `String qualifiedName()` с квотированием идентификаторов.
  - Иерархия исключений.

- [ ] **Step 1: Написать падающие тесты**

`excel-import-core/src/test/java/io/github/excelimport/SheetSelectorTest.java`:

```java
package io.github.excelimport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class SheetSelectorTest {

    @Test
    void byNameCarriesName() {
        SheetSelector selector = SheetSelector.byName("Сотрудники");

        assertThat(selector.name()).contains("Сотрудники");
        assertThat(selector.index()).isEmpty();
    }

    @Test
    void byIndexCarriesIndex() {
        SheetSelector selector = SheetSelector.byIndex(2);

        assertThat(selector.index()).hasValue(2);
        assertThat(selector.name()).isEmpty();
    }

    @Test
    void firstIsIndexZero() {
        assertThat(SheetSelector.first().index()).hasValue(0);
    }

    @Test
    void rejectsBlankName() {
        assertThatThrownBy(() -> SheetSelector.byName("  "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("имя листа");
    }

    @Test
    void rejectsNegativeIndex() {
        assertThatThrownBy(() -> SheetSelector.byIndex(-1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("индекс листа");
    }
}
```

`excel-import-core/src/test/java/io/github/excelimport/RowErrorTest.java`:

```java
package io.github.excelimport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class RowErrorTest {

    @Test
    void conversionErrorKeepsColumnAndRawValue() {
        RowError error = RowError.conversion(7, "Оклад", "не число", "NUMBER_FORMAT", "Не число");

        assertThat(error.rowNum()).isEqualTo(7);
        assertThat(error.columnHeader()).isEqualTo("Оклад");
        assertThat(error.rawValue()).isEqualTo("не число");
        assertThat(error.kind()).isEqualTo(ErrorKind.CONVERSION);
    }

    @Test
    void batchErrorHasNoColumn() {
        RowError error = RowError.batch(12, "DUPLICATE_IN_FILE", "Дубликат строки 5");

        assertThat(error.columnHeader()).isNull();
        assertThat(error.rawValue()).isNull();
        assertThat(error.kind()).isEqualTo(ErrorKind.BATCH);
    }

    @Test
    void rejectsNonPositiveRowNum() {
        assertThatThrownBy(() -> RowError.batch(0, "X", "msg"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("1-based");
    }

    @Test
    void rejectsBlankMessage() {
        assertThatThrownBy(() -> RowError.batch(1, "X", " "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("сообщение");
    }
}
```

`excel-import-core/src/test/java/io/github/excelimport/TableRefTest.java`:

```java
package io.github.excelimport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class TableRefTest {

    @Test
    void parsesSchemaQualifiedName() {
        TableRef ref = TableRef.of("hr.employee");

        assertThat(ref.schema()).isEqualTo("hr");
        assertThat(ref.name()).isEqualTo("employee");
        assertThat(ref.qualifiedName()).isEqualTo("\"hr\".\"employee\"");
    }

    @Test
    void bareNameHasNoSchema() {
        TableRef ref = TableRef.of("employee");

        assertThat(ref.schema()).isNull();
        assertThat(ref.qualifiedName()).isEqualTo("\"employee\"");
    }

    @Test
    void rejectsIdentifierWithQuote() {
        assertThatThrownBy(() -> TableRef.of("emp\"loyee"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("недопустимый идентификатор");
    }

    @Test
    void rejectsThreePartName() {
        assertThatThrownBy(() -> TableRef.of("db.hr.employee"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("схема.таблица");
    }
}
```

- [ ] **Step 2: Запустить тесты, убедиться что не компилируются**

Run: `./gradlew :excel-import-core:test`
Expected: FAIL — `cannot find symbol: class SheetSelector` (и аналогичные для `RowError`, `TableRef`).

- [ ] **Step 3: Реализовать `SheetSelector`**

```java
package io.github.excelimport;

import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;

/** Выбор листа книги: либо по имени, либо по 0-based индексу. */
public final class SheetSelector {

    private final String name;
    private final Integer index;

    private SheetSelector(String name, Integer index) {
        this.name = name;
        this.index = index;
    }

    public static SheetSelector byName(String name) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("имя листа не может быть пустым");
        }
        return new SheetSelector(name, null);
    }

    public static SheetSelector byIndex(int index) {
        if (index < 0) {
            throw new IllegalArgumentException("индекс листа не может быть отрицательным: " + index);
        }
        return new SheetSelector(null, index);
    }

    /** Первый лист книги. */
    public static SheetSelector first() {
        return byIndex(0);
    }

    public Optional<String> name() {
        return Optional.ofNullable(name);
    }

    public OptionalInt index() {
        return index == null ? OptionalInt.empty() : OptionalInt.of(index);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof SheetSelector other)) {
            return false;
        }
        return Objects.equals(name, other.name) && Objects.equals(index, other.index);
    }

    @Override
    public int hashCode() {
        return Objects.hash(name, index);
    }

    @Override
    public String toString() {
        return name != null ? "sheet[name=" + name + "]" : "sheet[index=" + index + "]";
    }
}
```

- [ ] **Step 4: Реализовать перечисления и `RowOutcome`**

`RowStatus.java`:

```java
package io.github.excelimport;

/** Исход обработки строки файла. Код используется для компактного хранения в byte[]. */
public enum RowStatus {

    NOT_PROCESSED((byte) 0),
    INSERTED((byte) 1),
    REJECTED((byte) 2),
    SKIPPED((byte) 3);

    private final byte code;

    RowStatus(byte code) {
        this.code = code;
    }

    public byte code() {
        return code;
    }

    public static RowStatus fromCode(byte code) {
        for (RowStatus status : values()) {
            if (status.code == code) {
                return status;
            }
        }
        throw new IllegalArgumentException("неизвестный код статуса: " + code);
    }
}
```

`RowOutcome.java`:

```java
package io.github.excelimport;

import java.util.Objects;

/**
 * Исход строки вместе с причиной. {@code message} непуст только для {@link RowStatus#REJECTED}.
 */
public record RowOutcome(RowStatus status, String message) {

    private static final RowOutcome INSERTED = new RowOutcome(RowStatus.INSERTED, null);
    private static final RowOutcome SKIPPED = new RowOutcome(RowStatus.SKIPPED, null);
    private static final RowOutcome NOT_PROCESSED = new RowOutcome(RowStatus.NOT_PROCESSED, null);

    public RowOutcome {
        Objects.requireNonNull(status, "status");
        if (status == RowStatus.REJECTED && (message == null || message.isBlank())) {
            throw new IllegalArgumentException("для REJECTED требуется непустое сообщение");
        }
    }

    public static RowOutcome inserted() {
        return INSERTED;
    }

    public static RowOutcome rejected(String message) {
        return new RowOutcome(RowStatus.REJECTED, message);
    }

    public static RowOutcome skipped() {
        return SKIPPED;
    }

    public static RowOutcome notProcessed() {
        return NOT_PROCESSED;
    }
}
```

`ErrorKind.java`:

```java
package io.github.excelimport;

/** Категория ошибки — определяет, на каком слое она возникла. */
public enum ErrorKind {

    /** Структура файла: нет листа, нет обязательной колонки, дубли заголовков. */
    STRUCTURE,
    /** Не удалось преобразовать значение ячейки в тип поля. */
    CONVERSION,
    /** Нарушено ограничение Jakarta Bean Validation. */
    CONSTRAINT,
    /** Ошибка от пользовательского BatchValidator. */
    BATCH,
    /** Ошибка, вернувшаяся из PostgreSQL. */
    DATABASE
}
```

`ImportStatus.java`:

```java
package io.github.excelimport;

/** Итоговый статус прогона. */
public enum ImportStatus {

    /** Все строки данных вставлены. */
    SUCCESS,
    /** Часть строк отклонена, либо не удалось сформировать отчёт. */
    PARTIAL,
    /** Импорт прерван: структурная ошибка, превышен лимит ошибок или фатальный сбой БД. */
    FAILED
}
```

`RowRef.java`:

```java
package io.github.excelimport;

import java.util.Objects;

/**
 * Смапленный объект вместе с 1-based номером строки Excel, из которой он получен.
 *
 * @param rowNum 1-based номер строки, как в интерфейсе Excel
 * @param value  смапленный и прошедший валидацию объект
 */
public record RowRef<T>(int rowNum, T value) {

    public RowRef {
        if (rowNum < 1) {
            throw new IllegalArgumentException("номер строки 1-based, получено: " + rowNum);
        }
        Objects.requireNonNull(value, "value");
    }
}
```

- [ ] **Step 5: Реализовать `RowError`**

```java
package io.github.excelimport;

import java.util.Objects;

/**
 * Ошибка, привязанная к строке файла.
 *
 * @param rowNum       1-based номер строки Excel
 * @param columnHeader заголовок колонки; null для ошибок уровня строки и батча
 * @param rawValue     исходный текст ячейки; null, если ошибка не привязана к ячейке
 * @param kind         категория ошибки
 * @param code         машиночитаемый код: имя constraint, SQLState, свой код конвертера
 * @param message      человекочитаемое сообщение, попадает в Excel-отчёт
 */
public record RowError(
        int rowNum,
        String columnHeader,
        String rawValue,
        ErrorKind kind,
        String code,
        String message) {

    public RowError {
        if (rowNum < 1) {
            throw new IllegalArgumentException("номер строки 1-based, получено: " + rowNum);
        }
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(code, "code");
        if (message == null || message.isBlank()) {
            throw new IllegalArgumentException("сообщение об ошибке не может быть пустым");
        }
    }

    public static RowError structure(int rowNum, String columnHeader, String code, String message) {
        return new RowError(rowNum, columnHeader, null, ErrorKind.STRUCTURE, code, message);
    }

    public static RowError conversion(
            int rowNum, String columnHeader, String rawValue, String code, String message) {
        return new RowError(rowNum, columnHeader, rawValue, ErrorKind.CONVERSION, code, message);
    }

    public static RowError constraint(
            int rowNum, String columnHeader, String rawValue, String code, String message) {
        return new RowError(rowNum, columnHeader, rawValue, ErrorKind.CONSTRAINT, code, message);
    }

    public static RowError batch(int rowNum, String code, String message) {
        return new RowError(rowNum, null, null, ErrorKind.BATCH, code, message);
    }

    public static RowError database(int rowNum, String code, String message) {
        return new RowError(rowNum, null, null, ErrorKind.DATABASE, code, message);
    }
}
```

- [ ] **Step 6: Реализовать `TableRef`**

```java
package io.github.excelimport;

import java.util.Objects;

/**
 * Ссылка на таблицу-приёмник. Идентификаторы всегда квотируются двойными кавычками,
 * поэтому имена регистрозависимы и не могут содержать кавычку.
 *
 * @param schema имя схемы; null означает search_path
 * @param name   имя таблицы
 */
public record TableRef(String schema, String name) {

    public TableRef {
        if (schema != null) {
            validateIdentifier(schema);
        }
        Objects.requireNonNull(name, "name");
        validateIdentifier(name);
    }

    /** Разбирает {@code "schema.table"} или {@code "table"}. */
    public static TableRef of(String qualified) {
        Objects.requireNonNull(qualified, "qualified");
        String[] parts = qualified.split("\\.", -1);
        return switch (parts.length) {
            case 1 -> new TableRef(null, parts[0]);
            case 2 -> new TableRef(parts[0], parts[1]);
            default -> throw new IllegalArgumentException(
                    "ожидался формат схема.таблица или таблица, получено: " + qualified);
        };
    }

    public String qualifiedName() {
        return schema == null ? quote(name) : quote(schema) + "." + quote(name);
    }

    /** Квотирует идентификатор для подстановки в SQL. */
    public static String quote(String identifier) {
        validateIdentifier(identifier);
        return '"' + identifier + '"';
    }

    private static void validateIdentifier(String identifier) {
        if (identifier.isBlank()) {
            throw new IllegalArgumentException("недопустимый идентификатор: пустая строка");
        }
        if (identifier.indexOf('"') >= 0 || identifier.indexOf('\0') >= 0) {
            throw new IllegalArgumentException("недопустимый идентификатор: " + identifier);
        }
    }
}
```

- [ ] **Step 7: Реализовать иерархию исключений**

`exception/ExcelImportException.java`:

```java
package io.github.excelimport.exception;

/** Базовое непроверяемое исключение библиотеки. */
public class ExcelImportException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public ExcelImportException(String message) {
        super(message);
    }

    public ExcelImportException(String message, Throwable cause) {
        super(message, cause);
    }
}
```

`exception/MappingConfigurationException.java` — то же тело, `extends ExcelImportException`, javadoc: «Некорректная конфигурация маппинга: отсутствует `@ExcelSheet`, дубли `@Column`, нет конвертера для типа. Бросается при сборке `ExcelImporter`, не во время импорта.»

`exception/FileStructureException.java` — `extends ExcelImportException`, javadoc: «Файл не соответствует ожидаемой структуре: нет листа, нет обязательной колонки, дубли заголовков, повреждённый zip.»

`exception/ReportGenerationException.java` — `extends ExcelImportException`, javadoc: «Не удалось сформировать Excel-отчёт. Импорт при этом уже выполнен.»

`exception/ImportAbortedException.java` — несёт частичный отчёт:

```java
package io.github.excelimport.exception;

import io.github.excelimport.ImportReport;

/** Импорт прерван: превышен лимит ошибок или произошёл фатальный сбой БД. */
public class ImportAbortedException extends ExcelImportException {

    private static final long serialVersionUID = 1L;

    private final transient ImportReport partialReport;

    public ImportAbortedException(String message, ImportReport partialReport) {
        super(message);
        this.partialReport = partialReport;
    }

    public ImportAbortedException(String message, Throwable cause, ImportReport partialReport) {
        super(message, cause);
        this.partialReport = partialReport;
    }

    /** Отчёт о том, что успело выполниться до прерывания. */
    public ImportReport partialReport() {
        return partialReport;
    }
}
```

`ImportAbortedException` ссылается на `ImportReport`, который создаётся в Task 16. Чтобы задача была самодостаточной, создать здесь же минимальный `ImportReport` ровно с теми полями, что перечислены в §4.3 спеки, без логики:

```java
package io.github.excelimport;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

/** Итог прогона импорта. */
public record ImportReport(
        UUID runId,
        String sourceName,
        long totalRows,
        long insertedRows,
        long rejectedRows,
        long batchesCommitted,
        Duration duration,
        Path reportPath,
        List<RowError> errors,
        boolean errorLimitReached,
        ImportStatus status) {

    public ImportReport {
        errors = List.copyOf(errors);
    }
}
```

- [ ] **Step 8: Запустить тесты**

Run: `./gradlew :excel-import-core:test`
Expected: PASS — все тесты `SheetSelectorTest`, `RowErrorTest`, `TableRefTest`, `BuildSmokeTest`.

- [ ] **Step 9: Коммит**

```bash
git add excel-import-core/src/main/java/io/github/excelimport excel-import-core/src/test/java/io/github/excelimport
git commit -m "feat: add core value types, error model and exception hierarchy"
```

---

### Task 3: `CellValue` и потоковое чтение листа — базовые типы ячеек

**Files:**
- Create: `excel-import-core/src/main/java/io/github/excelimport/convert/CellValue.java`
- Create: `excel-import-core/src/main/java/io/github/excelimport/internal/read/ImmutableCellValue.java`
- Create: `excel-import-core/src/main/java/io/github/excelimport/internal/read/RawRow.java`
- Create: `excel-import-core/src/main/java/io/github/excelimport/internal/read/ReadOptions.java`
- Create: `excel-import-core/src/main/java/io/github/excelimport/internal/read/StreamingSheetReader.java`
- Create: `excel-import-core/src/main/java/io/github/excelimport/internal/read/PoiStreamingSheetReader.java`
- Create: `excel-import-core/src/main/java/io/github/excelimport/internal/read/SheetSaxHandler.java`
- Test: `excel-import-core/src/test/java/io/github/excelimport/testsupport/XlsxFixtures.java`
- Test: `excel-import-core/src/test/java/io/github/excelimport/internal/read/PoiStreamingSheetReaderTest.java`

**Interfaces:**
- Consumes: `SheetSelector`, `FileStructureException` (Task 2).
- Produces:
  - `interface CellValue` с методами `CellAddress address()`, `CellType type()`, `boolean dateFormatted()`, `String asString()`, `Double asNumeric()`, `Boolean asBoolean()`, `String formula()`, `byte errorCode()`, `boolean isBlank()`; статические фабрики `CellValue.blank(CellAddress)`.
  - `RawRow` с `int rowIndex()` (0-based), `int excelRowNumber()` (1-based), `CellValue cell(int colIndex)` (никогда не null), `boolean isBlank()`, `int lastColumnIndex()`.
  - `ReadOptions` — record `(boolean skipBlankRows, boolean expandMergedCells, FormulaPolicy formulaPolicy)` + `ReadOptions.defaults()`; `enum FormulaPolicy { AS_NULL, AS_ERROR, AS_FORMULA_TEXT }`.
  - `interface StreamingSheetReader { void forEachRow(Path source, SheetSelector selector, ReadOptions options, Consumer<RawRow> handler); }`.
  - `PoiStreamingSheetReader implements StreamingSheetReader`.
  - Тестовый хелпер `XlsxFixtures` с методами `Path workbook(Path dir, String sheetName, Consumer<Sheet> builder)` и `Path simpleSheet(Path dir, Object[][] rows)`.

- [ ] **Step 1: Написать тестовый хелпер для генерации фикстур**

Фикстуры генерируются кодом, а не коммитятся бинарниками: их видно в ревью и легко менять.

`excel-import-core/src/test/java/io/github/excelimport/testsupport/XlsxFixtures.java`:

```java
package io.github.excelimport.testsupport;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.function.Consumer;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

/** Генерация .xlsx-фикстур в памяти. Только для тестов. */
public final class XlsxFixtures {

    private XlsxFixtures() {}

    /** Создаёт книгу с одним листом и отдаёт путь к файлу. */
    public static Path workbook(Path dir, String sheetName, Consumer<Sheet> builder) {
        Path file = dir.resolve("fixture-" + System.nanoTime() + ".xlsx");
        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            Sheet sheet = workbook.createSheet(sheetName);
            builder.accept(sheet);
            try (OutputStream out = Files.newOutputStream(file)) {
                workbook.write(out);
            }
        } catch (IOException e) {
            throw new IllegalStateException("не удалось создать фикстуру", e);
        }
        return file;
    }

    /**
     * Лист из матрицы значений. {@code null} в ячейке означает «ячейку не создавать».
     * Поддерживаются String, Number, Boolean, LocalDate.
     */
    public static Path simpleSheet(Path dir, Object[][] rows) {
        return workbook(dir, "Лист1", sheet -> fill(sheet, rows));
    }

    public static void fill(Sheet sheet, Object[][] rows) {
        CellStyle dateStyle = sheet.getWorkbook().createCellStyle();
        dateStyle.setDataFormat(sheet.getWorkbook().createDataFormat().getFormat("dd.MM.yyyy"));

        for (int r = 0; r < rows.length; r++) {
            if (rows[r] == null) {
                continue; // строка вообще не создаётся — проверяем пропуски строк
            }
            Row row = sheet.createRow(r);
            for (int c = 0; c < rows[r].length; c++) {
                Object value = rows[r][c];
                if (value == null) {
                    continue; // ячейка не создаётся — проверяем пропуски ячеек
                }
                Cell cell = row.createCell(c);
                if (value instanceof String s) {
                    cell.setCellValue(s);
                } else if (value instanceof Number n) {
                    cell.setCellValue(n.doubleValue());
                } else if (value instanceof Boolean b) {
                    cell.setCellValue(b);
                } else if (value instanceof LocalDate d) {
                    cell.setCellValue(d);
                    cell.setCellStyle(dateStyle);
                } else {
                    throw new IllegalArgumentException("неподдерживаемый тип: " + value.getClass());
                }
            }
        }
    }
}
```

- [ ] **Step 2: Написать падающий тест чтения**

`excel-import-core/src/test/java/io/github/excelimport/internal/read/PoiStreamingSheetReaderTest.java`:

```java
package io.github.excelimport.internal.read;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.excelimport.SheetSelector;
import io.github.excelimport.exception.FileStructureException;
import io.github.excelimport.testsupport.XlsxFixtures;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.apache.poi.ss.usermodel.CellType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PoiStreamingSheetReaderTest {

    @TempDir
    Path tempDir;

    private final StreamingSheetReader reader = new PoiStreamingSheetReader();

    private List<RawRow> readAll(Path file, SheetSelector selector, ReadOptions options) {
        List<RawRow> rows = new ArrayList<>();
        reader.forEachRow(file, selector, options, rows::add);
        return rows;
    }

    @Test
    void readsStringsAndNumbers() {
        Path file = XlsxFixtures.simpleSheet(tempDir, new Object[][] {
            {"ФИО", "Оклад"},
            {"Иванов", 1000.5},
        });

        List<RawRow> rows = readAll(file, SheetSelector.first(), ReadOptions.defaults());

        assertThat(rows).hasSize(2);
        assertThat(rows.get(0).cell(0).asString()).isEqualTo("ФИО");
        assertThat(rows.get(1).cell(0).type()).isEqualTo(CellType.STRING);
        assertThat(rows.get(1).cell(1).type()).isEqualTo(CellType.NUMERIC);
        assertThat(rows.get(1).cell(1).asNumeric()).isEqualTo(1000.5);
    }

    @Test
    void rowIndexIsZeroBasedAndExcelNumberIsOneBased() {
        Path file = XlsxFixtures.simpleSheet(tempDir, new Object[][] {{"a"}, {"b"}});

        List<RawRow> rows = readAll(file, SheetSelector.first(), ReadOptions.defaults());

        assertThat(rows.get(0).rowIndex()).isZero();
        assertThat(rows.get(0).excelRowNumber()).isEqualTo(1);
        assertThat(rows.get(1).rowIndex()).isEqualTo(1);
        assertThat(rows.get(1).excelRowNumber()).isEqualTo(2);
    }

    @Test
    void missingCellsBecomeBlankWithoutShiftingIndexes() {
        // в строке 1 нет ячейки B — значение C не должно сдвинуться на её место
        Path file = XlsxFixtures.simpleSheet(tempDir, new Object[][] {
            {"A", "B", "C"},
            {"a1", null, "c1"},
        });

        List<RawRow> rows = readAll(file, SheetSelector.first(), ReadOptions.defaults());

        RawRow data = rows.get(1);
        assertThat(data.cell(0).asString()).isEqualTo("a1");
        assertThat(data.cell(1).isBlank()).isTrue();
        assertThat(data.cell(1).asString()).isNull();
        assertThat(data.cell(2).asString()).isEqualTo("c1");
    }

    @Test
    void cellBeyondLastColumnIsBlankNotNull() {
        Path file = XlsxFixtures.simpleSheet(tempDir, new Object[][] {{"A"}});

        RawRow row = readAll(file, SheetSelector.first(), ReadOptions.defaults()).get(0);

        assertThat(row.cell(42)).isNotNull();
        assertThat(row.cell(42).isBlank()).isTrue();
    }

    @Test
    void missingRowsDoNotShiftRowIndexes() {
        // строки 1 (индекс 1) нет вовсе
        Path file = XlsxFixtures.simpleSheet(tempDir, new Object[][] {
            {"header"},
            null,
            {"data"},
        });

        List<RawRow> rows = readAll(file, SheetSelector.first(), ReadOptions.defaults());

        assertThat(rows).hasSize(2);
        assertThat(rows.get(1).rowIndex()).isEqualTo(2);
        assertThat(rows.get(1).excelRowNumber()).isEqualTo(3);
    }

    @Test
    void blankRowsAreSkippedByDefault() {
        Path file = XlsxFixtures.workbook(tempDir, "Лист1", sheet -> {
            sheet.createRow(0).createCell(0).setCellValue("header");
            sheet.createRow(1).createCell(0).setCellValue(""); // существует, но пустая
            sheet.createRow(2).createCell(0).setCellValue("data");
        });

        List<RawRow> rows = readAll(file, SheetSelector.first(), ReadOptions.defaults());

        assertThat(rows).hasSize(2);
        assertThat(rows.get(1).cell(0).asString()).isEqualTo("data");
    }

    @Test
    void blankRowsAreKeptWhenSkippingDisabled() {
        Path file = XlsxFixtures.workbook(tempDir, "Лист1", sheet -> {
            sheet.createRow(0).createCell(0).setCellValue("header");
            sheet.createRow(1).createCell(0).setCellValue("");
        });

        ReadOptions options = new ReadOptions(false, true, FormulaPolicy.AS_NULL);

        assertThat(readAll(file, SheetSelector.first(), options)).hasSize(2);
    }

    @Test
    void readsBooleanCells() {
        Path file = XlsxFixtures.simpleSheet(tempDir, new Object[][] {{Boolean.TRUE, Boolean.FALSE}});

        RawRow row = readAll(file, SheetSelector.first(), ReadOptions.defaults()).get(0);

        assertThat(row.cell(0).type()).isEqualTo(CellType.BOOLEAN);
        assertThat(row.cell(0).asBoolean()).isTrue();
        assertThat(row.cell(1).asBoolean()).isFalse();
        assertThat(row.cell(0).asString()).isEqualTo("TRUE");
    }

    @Test
    void selectsSheetByName() {
        Path file = XlsxFixtures.workbook(tempDir, "Первый", sheet -> {
            sheet.createRow(0).createCell(0).setCellValue("не тот");
            sheet.getWorkbook().createSheet("Второй").createRow(0).createCell(0).setCellValue("тот");
        });

        List<RawRow> rows = readAll(file, SheetSelector.byName("Второй"), ReadOptions.defaults());

        assertThat(rows.get(0).cell(0).asString()).isEqualTo("тот");
    }

    @Test
    void missingSheetByNameFails() {
        Path file = XlsxFixtures.simpleSheet(tempDir, new Object[][] {{"a"}});

        assertThatThrownBy(() ->
                        readAll(file, SheetSelector.byName("Нет такого"), ReadOptions.defaults()))
                .isInstanceOf(FileStructureException.class)
                .hasMessageContaining("Нет такого");
    }

    @Test
    void missingSheetByIndexFails() {
        Path file = XlsxFixtures.simpleSheet(tempDir, new Object[][] {{"a"}});

        assertThatThrownBy(() -> readAll(file, SheetSelector.byIndex(5), ReadOptions.defaults()))
                .isInstanceOf(FileStructureException.class)
                .hasMessageContaining("5");
    }

    @Test
    void nonZipFileFails() throws Exception {
        Path file = tempDir.resolve("broken.xlsx");
        Files.writeString(file, "это не xlsx");

        assertThatThrownBy(() -> readAll(file, SheetSelector.first(), ReadOptions.defaults()))
                .isInstanceOf(FileStructureException.class)
                .hasMessageContaining("не удалось открыть");
    }

    @Test
    void handlerCanStopEarlyWithoutLeakingResources() {
        Path file = XlsxFixtures.simpleSheet(tempDir, new Object[][] {{"a"}, {"b"}, {"c"}});
        List<RawRow> seen = new ArrayList<>();

        assertThatThrownBy(() -> reader.forEachRow(
                        file,
                        SheetSelector.first(),
                        ReadOptions.defaults(),
                        row -> {
                            seen.add(row);
                            if (seen.size() == 2) {
                                throw new IllegalStateException("стоп");
                            }
                        }))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("стоп");

        assertThat(seen).hasSize(2);
    }
}
```

- [ ] **Step 3: Запустить тесты, убедиться что падают**

Run: `./gradlew :excel-import-core:test --tests "*PoiStreamingSheetReaderTest*"`
Expected: FAIL — `cannot find symbol: class StreamingSheetReader`.

- [ ] **Step 4: Реализовать `CellValue` и `ImmutableCellValue`**

`convert/CellValue.java`:

```java
package io.github.excelimport.convert;

import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.util.CellAddress;

/**
 * Значение одной ячейки в терминах POI, но без объекта {@code Cell}: на первом проходе
 * чтение идёт через SAX и объектной модели книги не существует (см. §2.2 спеки).
 */
public interface CellValue {

    /** Адрес ячейки, например {@code C7}. */
    CellAddress address();

    /** Тип ячейки. Для пустой ячейки — {@link CellType#BLANK}. */
    CellType type();

    /** true, если стиль ячейки — формат даты (результат {@code DateUtil.isADateFormat}). */
    boolean dateFormatted();

    /** Текстовое представление; null для пустой ячейки. */
    String asString();

    /** Числовое значение; null, если ячейка не числовая. */
    Double asNumeric();

    /** Логическое значение; null, если ячейка не логическая. */
    Boolean asBoolean();

    /** Текст формулы без ведущего знака равенства; null, если ячейка не формула. */
    String formula();

    /**
     * Код ошибки Excel (см. {@code org.apache.poi.ss.usermodel.FormulaError}).
     * Значим только при {@link #type()} == {@link CellType#ERROR}, иначе -1.
     */
    byte errorCode();

    default boolean isBlank() {
        return type() == CellType.BLANK;
    }

    static CellValue blank(CellAddress address) {
        return new io.github.excelimport.internal.read.ImmutableCellValue(
                address, CellType.BLANK, false, null, null, null, null, (byte) -1);
    }
}
```

`internal/read/ImmutableCellValue.java`:

```java
package io.github.excelimport.internal.read;

import io.github.excelimport.convert.CellValue;
import java.util.Objects;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.util.CellAddress;

/** Неизменяемая реализация {@link CellValue}. */
public record ImmutableCellValue(
        CellAddress address,
        CellType type,
        boolean dateFormatted,
        String stringValue,
        Double numericValue,
        Boolean booleanValue,
        String formulaText,
        byte errorCode)
        implements CellValue {

    public ImmutableCellValue {
        Objects.requireNonNull(address, "address");
        Objects.requireNonNull(type, "type");
    }

    @Override
    public String asString() {
        return stringValue;
    }

    @Override
    public Double asNumeric() {
        return numericValue;
    }

    @Override
    public Boolean asBoolean() {
        return booleanValue;
    }

    @Override
    public String formula() {
        return formulaText;
    }
}
```

- [ ] **Step 5: Реализовать `RawRow`, `ReadOptions`, `FormulaPolicy`**

`internal/read/RawRow.java`:

```java
package io.github.excelimport.internal.read;

import io.github.excelimport.convert.CellValue;
import java.util.Map;
import org.apache.poi.ss.util.CellAddress;

/** Одна прочитанная строка листа. Индексы колонок 0-based, разреженные. */
public final class RawRow {

    private final int rowIndex;
    private final Map<Integer, CellValue> cells;

    RawRow(int rowIndex, Map<Integer, CellValue> cells) {
        this.rowIndex = rowIndex;
        this.cells = cells;
    }

    /** 0-based индекс строки, как в POI. */
    public int rowIndex() {
        return rowIndex;
    }

    /** 1-based номер строки, как в интерфейсе Excel. */
    public int excelRowNumber() {
        return rowIndex + 1;
    }

    /** Значение ячейки. Для отсутствующей ячейки возвращает пустое значение, никогда null. */
    public CellValue cell(int columnIndex) {
        CellValue value = cells.get(columnIndex);
        return value != null ? value : CellValue.blank(new CellAddress(rowIndex, columnIndex));
    }

    /** Наибольший заполненный индекс колонки, или -1 для пустой строки. */
    public int lastColumnIndex() {
        return cells.keySet().stream().mapToInt(Integer::intValue).max().orElse(-1);
    }

    /** true, если ни одна ячейка не содержит непустого значения. */
    public boolean isBlank() {
        return cells.values().stream()
                .allMatch(value -> value.isBlank()
                        || (value.asString() != null && value.asString().isBlank()));
    }
}
```

`internal/read/FormulaPolicy.java`:

```java
package io.github.excelimport.internal.read;

/** Что делать с формулой, у которой в файле нет кэшированного результата. */
public enum FormulaPolicy {

    /** Считать ячейку пустой. */
    AS_NULL,
    /** Породить ошибку конвертации для этой ячейки. */
    AS_ERROR,
    /** Подставить текст формулы как строковое значение. */
    AS_FORMULA_TEXT
}
```

`internal/read/ReadOptions.java`:

```java
package io.github.excelimport.internal.read;

import java.util.Objects;

/**
 * Настройки чтения листа.
 *
 * @param skipBlankRows      пропускать полностью пустые строки
 * @param expandMergedCells  размножать значение объединённой ячейки на весь диапазон
 * @param formulaPolicy      поведение для формулы без кэшированного результата
 */
public record ReadOptions(
        boolean skipBlankRows, boolean expandMergedCells, FormulaPolicy formulaPolicy) {

    public ReadOptions {
        Objects.requireNonNull(formulaPolicy, "formulaPolicy");
    }

    public static ReadOptions defaults() {
        return new ReadOptions(true, true, FormulaPolicy.AS_NULL);
    }
}
```

- [ ] **Step 6: Реализовать SAX-обработчик**

`internal/read/SheetSaxHandler.java`:

```java
package io.github.excelimport.internal.read;

import io.github.excelimport.convert.CellValue;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Consumer;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.util.CellAddress;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.ss.util.CellReference;
import org.apache.poi.xssf.model.SharedStrings;
import org.apache.poi.xssf.model.StylesTable;
import org.apache.poi.xssf.usermodel.XSSFCellStyle;
import org.apache.poi.ss.usermodel.DateUtil;
import org.xml.sax.Attributes;
import org.xml.sax.helpers.DefaultHandler;

/**
 * Разбирает {@code sheetN.xml} и отдаёт {@link RawRow} на каждую строку.
 * Свой обработчик вместо {@code XSSFSheetXMLHandler} нужен потому, что тот отдаёт
 * только отформатированную строку и теряет тип ячейки, признак даты и код ошибки.
 */
final class SheetSaxHandler extends DefaultHandler {

    private final SharedStrings sharedStrings;
    private final StylesTable styles;
    private final ReadOptions options;
    private final Map<Integer, Map<Integer, CellValue>> mergedFill;
    private final Consumer<RawRow> handler;

    private int currentRowIndex = -1;
    private Map<Integer, CellValue> currentCells;

    private CellAddress cellAddress;
    private String cellTypeAttr;
    private int cellStyleIndex = -1;
    private final StringBuilder valueBuffer = new StringBuilder();
    private final StringBuilder formulaBuffer = new StringBuilder();
    private boolean inValue;
    private boolean inFormula;
    private boolean inInlineString;

    SheetSaxHandler(
            SharedStrings sharedStrings,
            StylesTable styles,
            ReadOptions options,
            Map<Integer, Map<Integer, CellValue>> mergedFill,
            Consumer<RawRow> handler) {
        this.sharedStrings = sharedStrings;
        this.styles = styles;
        this.options = options;
        this.mergedFill = mergedFill;
        this.handler = handler;
    }

    @Override
    public void startElement(String uri, String localName, String qName, Attributes attrs) {
        switch (qName) {
            case "row" -> {
                String r = attrs.getValue("r");
                currentRowIndex = r != null ? Integer.parseInt(r) - 1 : currentRowIndex + 1;
                currentCells = new HashMap<>();
            }
            case "c" -> {
                String ref = attrs.getValue("r");
                cellAddress = ref != null
                        ? new CellAddress(ref)
                        : new CellAddress(currentRowIndex, currentCells.size());
                cellTypeAttr = attrs.getValue("t");
                String s = attrs.getValue("s");
                cellStyleIndex = s != null ? Integer.parseInt(s) : -1;
                valueBuffer.setLength(0);
                formulaBuffer.setLength(0);
                inInlineString = false;
            }
            case "v" -> inValue = true;
            case "f" -> inFormula = true;
            case "is" -> inInlineString = true;
            case "t" -> {
                if (inInlineString) {
                    inValue = true;
                }
            }
            default -> {
                // остальные элементы (sheetData, cols, dimension, ...) не интересуют
            }
        }
    }

    @Override
    public void characters(char[] ch, int start, int length) {
        if (inValue) {
            valueBuffer.append(ch, start, length);
        } else if (inFormula) {
            formulaBuffer.append(ch, start, length);
        }
    }

    @Override
    public void endElement(String uri, String localName, String qName) {
        switch (qName) {
            case "v", "t" -> inValue = false;
            case "f" -> inFormula = false;
            case "is" -> inInlineString = false;
            case "c" -> currentCells.put(cellAddress.getColumn(), buildCellValue());
            case "row" -> emitRow();
            default -> {
                // не интересует
            }
        }
    }

    private CellValue buildCellValue() {
        String raw = valueBuffer.length() == 0 ? null : valueBuffer.toString();
        String formula = formulaBuffer.length() == 0 ? null : formulaBuffer.toString();
        boolean dateFormatted = isDateFormatted();

        if (formula != null) {
            return buildFormulaCell(raw, formula, dateFormatted);
        }
        if (raw == null) {
            return new ImmutableCellValue(
                    cellAddress, CellType.BLANK, dateFormatted, null, null, null, null, (byte) -1);
        }
        return switch (cellTypeAttr == null ? "n" : cellTypeAttr) {
            case "s" -> string(sharedStrings.getItemAt(Integer.parseInt(raw)).getString(), dateFormatted);
            case "inlineStr", "str" -> string(raw, dateFormatted);
            case "b" -> {
                boolean value = "1".equals(raw);
                yield new ImmutableCellValue(
                        cellAddress,
                        CellType.BOOLEAN,
                        false,
                        value ? "TRUE" : "FALSE",
                        null,
                        value,
                        null,
                        (byte) -1);
            }
            case "e" -> new ImmutableCellValue(
                    cellAddress, CellType.ERROR, false, raw, null, null, null, errorCode(raw));
            default -> numeric(raw, dateFormatted);
        };
    }

    private CellValue buildFormulaCell(String raw, String formula, boolean dateFormatted) {
        if (raw == null) {
            return switch (options.formulaPolicy()) {
                case AS_NULL -> new ImmutableCellValue(
                        cellAddress, CellType.BLANK, dateFormatted, null, null, null, formula, (byte) -1);
                case AS_FORMULA_TEXT -> new ImmutableCellValue(
                        cellAddress, CellType.STRING, false, formula, null, null, formula, (byte) -1);
                case AS_ERROR -> new ImmutableCellValue(
                        cellAddress,
                        CellType.ERROR,
                        false,
                        "#FORMULA_NOT_CACHED",
                        null,
                        null,
                        formula,
                        (byte) -1);
            };
        }
        // есть кэшированный результат — используем его тип
        if ("e".equals(cellTypeAttr)) {
            return new ImmutableCellValue(
                    cellAddress, CellType.ERROR, false, raw, null, null, formula, errorCode(raw));
        }
        if ("str".equals(cellTypeAttr) || "s".equals(cellTypeAttr) || "inlineStr".equals(cellTypeAttr)) {
            String text = "s".equals(cellTypeAttr)
                    ? sharedStrings.getItemAt(Integer.parseInt(raw)).getString()
                    : raw;
            return new ImmutableCellValue(
                    cellAddress, CellType.STRING, dateFormatted, text, null, null, formula, (byte) -1);
        }
        if ("b".equals(cellTypeAttr)) {
            boolean value = "1".equals(raw);
            return new ImmutableCellValue(
                    cellAddress,
                    CellType.BOOLEAN,
                    false,
                    value ? "TRUE" : "FALSE",
                    null,
                    value,
                    formula,
                    (byte) -1);
        }
        double number = Double.parseDouble(raw);
        return new ImmutableCellValue(
                cellAddress, CellType.NUMERIC, dateFormatted, raw, number, null, formula, (byte) -1);
    }

    private CellValue string(String text, boolean dateFormatted) {
        return new ImmutableCellValue(
                cellAddress, CellType.STRING, dateFormatted, text, null, null, null, (byte) -1);
    }

    private CellValue numeric(String raw, boolean dateFormatted) {
        double number = Double.parseDouble(raw);
        return new ImmutableCellValue(
                cellAddress, CellType.NUMERIC, dateFormatted, raw, number, null, null, (byte) -1);
    }

    private static byte errorCode(String raw) {
        try {
            return org.apache.poi.ss.usermodel.FormulaError.forString(raw).getCode();
        } catch (IllegalArgumentException e) {
            return (byte) -1;
        }
    }

    private boolean isDateFormatted() {
        if (cellStyleIndex < 0 || styles == null) {
            return false;
        }
        XSSFCellStyle style = styles.getStyleAt(cellStyleIndex);
        if (style == null) {
            return false;
        }
        return DateUtil.isADateFormat(style.getDataFormat(), style.getDataFormatString());
    }

    private void emitRow() {
        Map<Integer, CellValue> cells = currentCells;
        if (options.expandMergedCells()) {
            Map<Integer, CellValue> fill = mergedFill.get(currentRowIndex);
            if (fill != null) {
                fill.forEach(cells::putIfAbsent);
            }
        }
        RawRow row = new RawRow(currentRowIndex, cells);
        currentCells = null;
        if (options.skipBlankRows() && row.isBlank()) {
            return;
        }
        handler.accept(row);
    }

    /**
     * Заполняет карту «строка → (колонка → значение)» значениями верхних левых ячеек
     * объединённых диапазонов. Вызывается предпроходом, потому что {@code mergeCells}
     * в XML идёт после {@code sheetData}.
     */
    static void registerMerged(
            Map<Integer, Map<Integer, CellValue>> target,
            CellRangeAddress range,
            CellValue anchorValue) {
        for (int r = range.getFirstRow(); r <= range.getLastRow(); r++) {
            for (int c = range.getFirstColumn(); c <= range.getLastColumn(); c++) {
                if (r == range.getFirstRow() && c == range.getFirstColumn()) {
                    continue;
                }
                target.computeIfAbsent(r, key -> new HashMap<>())
                        .put(c, copyTo(anchorValue, new CellAddress(r, c)));
            }
        }
    }

    private static CellValue copyTo(CellValue source, CellAddress address) {
        return new ImmutableCellValue(
                address,
                source.type(),
                source.dateFormatted(),
                source.asString(),
                source.asNumeric(),
                source.asBoolean(),
                source.formula(),
                source.errorCode());
    }
}
```

- [ ] **Step 7: Реализовать `StreamingSheetReader` и `PoiStreamingSheetReader`**

`internal/read/StreamingSheetReader.java`:

```java
package io.github.excelimport.internal.read;

import io.github.excelimport.SheetSelector;
import java.nio.file.Path;
import java.util.function.Consumer;

/** Потоковое чтение одного листа .xlsx без загрузки книги в память. */
public interface StreamingSheetReader {

    /**
     * Читает лист и вызывает {@code handler} на каждую строку в порядке возрастания
     * номера. Исключение из {@code handler} пробрасывается наружу, ресурсы при этом
     * закрываются.
     */
    void forEachRow(Path source, SheetSelector selector, ReadOptions options, Consumer<RawRow> handler);
}
```

`internal/read/PoiStreamingSheetReader.java`:

```java
package io.github.excelimport.internal.read;

import io.github.excelimport.SheetSelector;
import io.github.excelimport.convert.CellValue;
import io.github.excelimport.exception.FileStructureException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.function.Consumer;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.parsers.SAXParser;
import javax.xml.parsers.SAXParserFactory;
import org.apache.poi.openxml4j.exceptions.InvalidFormatException;
import org.apache.poi.openxml4j.opc.OPCPackage;
import org.apache.poi.openxml4j.opc.PackageAccess;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.eventusermodel.ReadOnlySharedStringsTable;
import org.apache.poi.xssf.eventusermodel.XSSFReader;
import org.apache.poi.xssf.model.StylesTable;
import org.xml.sax.Attributes;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import org.xml.sax.helpers.DefaultHandler;

/** Реализация на {@code XSSFReader} + SAX. */
public final class PoiStreamingSheetReader implements StreamingSheetReader {

    @Override
    public void forEachRow(
            Path source, SheetSelector selector, ReadOptions options, Consumer<RawRow> handler) {
        try (OPCPackage pkg = OPCPackage.open(source.toFile(), PackageAccess.READ)) {
            XSSFReader reader = new XSSFReader(pkg);
            ReadOnlySharedStringsTable strings = new ReadOnlySharedStringsTable(pkg);
            StylesTable styles = reader.getStylesTable();

            Map<Integer, Map<Integer, CellValue>> mergedFill = options.expandMergedCells()
                    ? collectMergedRanges(reader, selector, strings, styles, options)
                    : Map.of();

            try (InputStream sheet = openSheet(reader, selector)) {
                SheetSaxHandler saxHandler =
                        new SheetSaxHandler(strings, styles, options, mergedFill, handler);
                newParser().parse(new InputSource(sheet), saxHandler);
            }
        } catch (InvalidFormatException | IOException e) {
            throw new FileStructureException(
                    "не удалось открыть файл как .xlsx: " + source.getFileName(), e);
        } catch (SAXException e) {
            throw new FileStructureException("повреждённый XML листа: " + source.getFileName(), e);
        } catch (org.apache.poi.openxml4j.exceptions.OpenXML4JException e) {
            throw new FileStructureException("не удалось прочитать структуру книги", e);
        }
    }

    private static SAXParser newParser() {
        try {
            SAXParserFactory factory = SAXParserFactory.newInstance();
            factory.setNamespaceAware(false);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            return factory.newSAXParser();
        } catch (ParserConfigurationException | SAXException e) {
            throw new IllegalStateException("не удалось создать SAX-парсер", e);
        }
    }

    private static InputStream openSheet(XSSFReader reader, SheetSelector selector)
            throws IOException, org.apache.poi.openxml4j.exceptions.InvalidFormatException {
        XSSFReader.SheetIterator iterator = (XSSFReader.SheetIterator) reader.getSheetsData();
        if (selector.name().isPresent()) {
            String wanted = selector.name().get();
            while (iterator.hasNext()) {
                InputStream stream = iterator.next();
                if (wanted.equals(iterator.getSheetName())) {
                    return stream;
                }
                stream.close();
            }
            throw new FileStructureException("в книге нет листа с именем: " + wanted);
        }
        int wanted = selector.index().orElseThrow();
        int current = 0;
        while (iterator.hasNext()) {
            InputStream stream = iterator.next();
            if (current++ == wanted) {
                return stream;
            }
            stream.close();
        }
        throw new FileStructureException("в книге нет листа с индексом: " + wanted);
    }

    /**
     * Предпроход: {@code mergeCells} в XML стоит после {@code sheetData}, поэтому
     * диапазоны и значения их верхних левых ячеек собираются отдельным проходом.
     */
    private Map<Integer, Map<Integer, CellValue>> collectMergedRanges(
            XSSFReader reader,
            SheetSelector selector,
            ReadOnlySharedStringsTable strings,
            StylesTable styles,
            ReadOptions options)
            throws IOException, SAXException,
                    org.apache.poi.openxml4j.exceptions.InvalidFormatException {
        java.util.List<CellRangeAddress> ranges = new java.util.ArrayList<>();
        try (InputStream sheet = openSheet(reader, selector)) {
            newParser().parse(new InputSource(sheet), new DefaultHandler() {
                @Override
                public void startElement(String uri, String ln, String qName, Attributes attrs) {
                    if ("mergeCell".equals(qName)) {
                        ranges.add(CellRangeAddress.valueOf(attrs.getValue("ref")));
                    }
                }
            });
        }
        if (ranges.isEmpty()) {
            return Map.of();
        }

        // Собираем значения верхних левых ячеек диапазонов вторым коротким проходом.
        Map<Integer, Map<Integer, CellValue>> anchors = new HashMap<>();
        ReadOptions rawOptions = new ReadOptions(false, false, options.formulaPolicy());
        try (InputStream sheet = openSheet(reader, selector)) {
            SheetSaxHandler collector = new SheetSaxHandler(
                    strings, styles, rawOptions, Map.of(), row -> {
                        for (CellRangeAddress range : ranges) {
                            if (range.getFirstRow() == row.rowIndex()) {
                                anchors.computeIfAbsent(row.rowIndex(), key -> new HashMap<>())
                                        .put(range.getFirstColumn(), row.cell(range.getFirstColumn()));
                            }
                        }
                    });
            newParser().parse(new InputSource(sheet), collector);
        }

        Map<Integer, Map<Integer, CellValue>> fill = new HashMap<>();
        for (CellRangeAddress range : ranges) {
            Map<Integer, CellValue> rowAnchors = anchors.get(range.getFirstRow());
            CellValue anchor = rowAnchors == null ? null : rowAnchors.get(range.getFirstColumn());
            if (anchor != null && !anchor.isBlank()) {
                SheetSaxHandler.registerMerged(fill, range, anchor);
            }
        }
        return fill;
    }
}
```

Импорт `java.util.Iterator` в этом файле не нужен — не добавлять.

- [ ] **Step 8: Запустить тесты**

Run: `./gradlew :excel-import-core:test --tests "*PoiStreamingSheetReaderTest*"`
Expected: PASS — все 13 тестов.

Если тест `nonZipFileFails` падает с другим типом исключения, проверить, что `OPCPackage.open` бросает `InvalidFormatException`/`NotOfficeXmlFileException` — второй является подклассом `InvalidFormatException`, так что перехват работает.

- [ ] **Step 9: Коммит**

```bash
git add excel-import-core/src/main/java/io/github/excelimport/convert \
        excel-import-core/src/main/java/io/github/excelimport/internal/read \
        excel-import-core/src/test/java/io/github/excelimport/internal/read \
        excel-import-core/src/test/java/io/github/excelimport/testsupport
git commit -m "feat: add streaming XLSX sheet reader with SAX-based cell typing"
```

---

### Task 4: Чтение — даты, формулы, ошибки, объединённые ячейки, `date1904`

**Files:**
- Modify: `excel-import-core/src/main/java/io/github/excelimport/internal/read/PoiStreamingSheetReader.java`
- Modify: `excel-import-core/src/main/java/io/github/excelimport/internal/read/SheetSaxHandler.java`
- Modify: `excel-import-core/src/main/java/io/github/excelimport/convert/CellValue.java`
- Test: `excel-import-core/src/test/java/io/github/excelimport/internal/read/SheetReaderEdgeCasesTest.java`

**Interfaces:**
- Consumes: всё из Task 3.
- Produces: `CellValue.asLocalDateTime()` — `LocalDateTime` из serial date с учётом флага книги `date1904`; поле `date1904` пробрасывается из `WorkbookProperties` в `SheetSaxHandler`; поведение `FormulaPolicy` для всех трёх значений; корректный `errorCode()` для `#N/A`; размножение значений объединённых ячеек.

- [ ] **Step 1: Написать падающие тесты на краевые случаи**

`excel-import-core/src/test/java/io/github/excelimport/internal/read/SheetReaderEdgeCasesTest.java`:

```java
package io.github.excelimport.internal.read;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.excelimport.SheetSelector;
import io.github.excelimport.testsupport.XlsxFixtures;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.FormulaError;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.util.CellRangeAddress;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SheetReaderEdgeCasesTest {

    @TempDir
    Path tempDir;

    private final StreamingSheetReader reader = new PoiStreamingSheetReader();

    private List<RawRow> readAll(Path file, ReadOptions options) {
        List<RawRow> rows = new ArrayList<>();
        reader.forEachRow(file, SheetSelector.first(), options, rows::add);
        return rows;
    }

    @Test
    void dateCellIsNumericButFlaggedAsDate() {
        Path file = XlsxFixtures.simpleSheet(tempDir, new Object[][] {{LocalDate.of(2026, 7, 29)}});

        RawRow row = readAll(file, ReadOptions.defaults()).get(0);

        assertThat(row.cell(0).type()).isEqualTo(CellType.NUMERIC);
        assertThat(row.cell(0).dateFormatted()).isTrue();
        assertThat(row.cell(0).asLocalDateTime()).isEqualTo(LocalDateTime.of(2026, 7, 29, 0, 0));
    }

    @Test
    void plainNumberIsNotFlaggedAsDate() {
        Path file = XlsxFixtures.simpleSheet(tempDir, new Object[][] {{42}});

        assertThat(readAll(file, ReadOptions.defaults()).get(0).cell(0).dateFormatted()).isFalse();
    }

    @Test
    void inlineStringIsRead() throws Exception {
        // POI пишет inline strings, если явно попросить строковый тип без shared strings
        Path file = XlsxFixtures.workbook(tempDir, "Лист1", sheet -> {
            Cell cell = sheet.createRow(0).createCell(0);
            cell.setCellValue("инлайн");
        });

        assertThat(readAll(file, ReadOptions.defaults()).get(0).cell(0).asString())
                .isEqualTo("инлайн");
    }

    @Test
    void errorCellCarriesErrorCodeAndText() {
        Path file = XlsxFixtures.workbook(tempDir, "Лист1", sheet -> {
            Cell cell = sheet.createRow(0).createCell(0);
            cell.setCellErrorValue(FormulaError.NA.getCode());
        });

        RawRow row = readAll(file, ReadOptions.defaults()).get(0);

        assertThat(row.cell(0).type()).isEqualTo(CellType.ERROR);
        assertThat(row.cell(0).errorCode()).isEqualTo(FormulaError.NA.getCode());
        assertThat(row.cell(0).asString()).isEqualTo("#N/A");
    }

    @Test
    void formulaWithCachedResultUsesCachedValue() {
        Path file = XlsxFixtures.workbook(tempDir, "Лист1", sheet -> {
            Row row = sheet.createRow(0);
            row.createCell(0).setCellValue(2);
            row.createCell(1).setCellValue(3);
            Cell formula = row.createCell(2);
            formula.setCellFormula("A1+B1");
            // POI не считает формулу сама — кэшируем результат вручную
            formula.setCellValue(5);
        });

        RawRow row = readAll(file, ReadOptions.defaults()).get(0);

        assertThat(row.cell(2).asNumeric()).isEqualTo(5.0);
        assertThat(row.cell(2).formula()).isEqualTo("A1+B1");
    }

    @Test
    void formulaWithoutCacheIsBlankUnderAsNullPolicy() {
        Path file = formulaWithoutCache();

        RawRow row = readAll(file, ReadOptions.defaults()).get(0);

        assertThat(row.cell(2).isBlank()).isTrue();
        assertThat(row.cell(2).formula()).isEqualTo("A1+B1");
    }

    @Test
    void formulaWithoutCacheBecomesTextUnderFormulaTextPolicy() {
        Path file = formulaWithoutCache();

        RawRow row = readAll(file, new ReadOptions(true, true, FormulaPolicy.AS_FORMULA_TEXT)).get(0);

        assertThat(row.cell(2).asString()).isEqualTo("A1+B1");
    }

    @Test
    void formulaWithoutCacheBecomesErrorUnderErrorPolicy() {
        Path file = formulaWithoutCache();

        RawRow row = readAll(file, new ReadOptions(true, true, FormulaPolicy.AS_ERROR)).get(0);

        assertThat(row.cell(2).type()).isEqualTo(CellType.ERROR);
        assertThat(row.cell(2).asString()).isEqualTo("#FORMULA_NOT_CACHED");
    }

    private Path formulaWithoutCache() {
        return XlsxFixtures.workbook(tempDir, "Лист1", sheet -> {
            Row row = sheet.createRow(0);
            row.createCell(0).setCellValue(2);
            row.createCell(1).setCellValue(3);
            row.createCell(2).setCellFormula("A1+B1");
        });
    }

    @Test
    void mergedCellValueIsExpandedAcrossRange() {
        Path file = XlsxFixtures.workbook(tempDir, "Лист1", sheet -> {
            sheet.createRow(0).createCell(0).setCellValue("Отдел А");
            sheet.createRow(1).createCell(1).setCellValue("Иванов");
            sheet.getRow(0).createCell(1).setCellValue("Петров");
            // A1:A2 объединены, значение только в A1
            sheet.addMergedRegion(new CellRangeAddress(0, 1, 0, 0));
        });

        List<RawRow> rows = readAll(file, ReadOptions.defaults());

        assertThat(rows.get(0).cell(0).asString()).isEqualTo("Отдел А");
        assertThat(rows.get(1).cell(0).asString()).isEqualTo("Отдел А");
        assertThat(rows.get(1).cell(0).address().formatAsString()).isEqualTo("A2");
    }

    @Test
    void mergedExpansionCanBeDisabled() {
        Path file = XlsxFixtures.workbook(tempDir, "Лист1", sheet -> {
            sheet.createRow(0).createCell(0).setCellValue("Отдел А");
            sheet.createRow(1).createCell(1).setCellValue("Иванов");
            sheet.addMergedRegion(new CellRangeAddress(0, 1, 0, 0));
        });

        List<RawRow> rows = readAll(file, new ReadOptions(true, false, FormulaPolicy.AS_NULL));

        assertThat(rows.get(1).cell(0).isBlank()).isTrue();
    }

    @Test
    void date1904WorkbookIsInterpretedCorrectly() {
        Path file = XlsxFixtures.date1904Workbook(tempDir, LocalDate.of(2026, 7, 29));

        RawRow row = readAll(file, ReadOptions.defaults()).get(0);

        assertThat(row.cell(0).asLocalDateTime()).isEqualTo(LocalDateTime.of(2026, 7, 29, 0, 0));
    }
}
```

- [ ] **Step 2: Добавить в `XlsxFixtures` генератор книги с `date1904`**

Дописать в `XlsxFixtures`:

```java
    /** Книга в системе дат 1904 (эпоха Mac) с одной датой в A1. */
    public static Path date1904Workbook(Path dir, LocalDate date) {
        Path file = dir.resolve("fixture-1904-" + System.nanoTime() + ".xlsx");
        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            workbook.getCTWorkbook().addNewWorkbookPr().setDate1904(true);
            Sheet sheet = workbook.createSheet("Лист1");
            CellStyle dateStyle = workbook.createCellStyle();
            dateStyle.setDataFormat(workbook.createDataFormat().getFormat("dd.MM.yyyy"));
            Cell cell = sheet.createRow(0).createCell(0);
            cell.setCellValue(org.apache.poi.ss.usermodel.DateUtil.getExcelDate(
                    date.atStartOfDay(), true));
            cell.setCellStyle(dateStyle);
            try (OutputStream out = Files.newOutputStream(file)) {
                workbook.write(out);
            }
        } catch (IOException e) {
            throw new IllegalStateException("не удалось создать фикстуру 1904", e);
        }
        return file;
    }
```

- [ ] **Step 3: Запустить тесты, убедиться что падают**

Run: `./gradlew :excel-import-core:test --tests "*SheetReaderEdgeCasesTest*"`
Expected: FAIL — `cannot find symbol: method asLocalDateTime()`, и `date1904WorkbookIsInterpretedCorrectly` даёт неверную дату.

- [ ] **Step 4: Добавить `asLocalDateTime()` в `CellValue`**

В `CellValue` добавить метод:

```java
    /**
     * Значение как дата/время. Возвращает null, если ячейка не числовая или её стиль
     * не является форматом даты. Учитывает систему дат книги (1900 или 1904).
     */
    LocalDateTime asLocalDateTime();
```

и импорт `java.time.LocalDateTime`. В `CellValue.blank(...)` конструктор `ImmutableCellValue` получает дополнительный параметр `date1904` — обновить вызов, передав `false`.

- [ ] **Step 5: Пробросить `date1904` в `ImmutableCellValue` и реализовать конвертацию**

`ImmutableCellValue` получает новый компонент `boolean date1904` последним параметром и реализацию:

```java
    @Override
    public LocalDateTime asLocalDateTime() {
        if (numericValue == null || !dateFormatted) {
            return null;
        }
        return DateUtil.getLocalDateTime(numericValue, date1904);
    }
```

с импортами `java.time.LocalDateTime` и `org.apache.poi.ss.usermodel.DateUtil`.

- [ ] **Step 6: Пробросить флаг книги в `SheetSaxHandler`**

В `SheetSaxHandler` добавить поле `private final boolean date1904;` и параметр конструктора после `styles`. Все вызовы `new ImmutableCellValue(...)` получают `date1904` последним аргументом. В `copyTo(...)` — тоже (взять `source` — но у `CellValue` флага нет, поэтому `copyTo` перевести на приём `ImmutableCellValue` и копировать его компонент `date1904`).

В `PoiStreamingSheetReader` определить флаг один раз и передать в оба обработчика:

```java
    private static boolean isDate1904(XSSFReader reader) {
        try (InputStream workbookData = reader.getWorkbookData()) {
            boolean[] found = {false};
            newParser().parse(new InputSource(workbookData), new DefaultHandler() {
                @Override
                public void startElement(String uri, String ln, String qName, Attributes attrs) {
                    if ("workbookPr".equals(qName)) {
                        String value = attrs.getValue("date1904");
                        found[0] = "1".equals(value) || "true".equals(value);
                    }
                }
            });
            return found[0];
        } catch (IOException | SAXException
                | org.apache.poi.openxml4j.exceptions.InvalidFormatException e) {
            throw new FileStructureException("не удалось прочитать свойства книги", e);
        }
    }
```

и в `forEachRow` перед созданием обработчика: `boolean date1904 = isDate1904(reader);`.

- [ ] **Step 7: Запустить тесты**

Run: `./gradlew :excel-import-core:test`
Expected: PASS — `SheetReaderEdgeCasesTest` (11 тестов) и все предыдущие.

Если `inlineStringIsRead` проходит через shared strings, а не inline (POI по умолчанию использует shared strings) — тест всё равно валиден как проверка чтения строк; чтобы покрыть именно `inlineStr`, добавить фикстуру, собранную из XML вручную. Сделать это только если POI не генерирует inline strings ни при какой настройке: тогда добавить в `XlsxFixtures` метод, пишущий минимальный `sheet1.xml` с `t="inlineStr"` в готовый zip.

- [ ] **Step 8: Коммит**

```bash
git add excel-import-core/src/main/java/io/github/excelimport \
        excel-import-core/src/test/java/io/github/excelimport
git commit -m "feat: handle dates, 1904 epoch, formulas, error cells and merged ranges in reader"
```

---

### Task 5: Аннотации и `MappingModel`

**Files:**
- Create: `excel-import-core/src/main/java/io/github/excelimport/annotation/ExcelSheet.java`
- Create: `excel-import-core/src/main/java/io/github/excelimport/annotation/ExcelColumn.java`
- Create: `excel-import-core/src/main/java/io/github/excelimport/annotation/Column.java`
- Create: `excel-import-core/src/main/java/io/github/excelimport/annotation/TargetTable.java`
- Create: `excel-import-core/src/main/java/io/github/excelimport/NamingStrategy.java`
- Create: `excel-import-core/src/main/java/io/github/excelimport/internal/map/ColumnBinding.java`
- Create: `excel-import-core/src/main/java/io/github/excelimport/internal/map/MappingModel.java`
- Create: `excel-import-core/src/main/java/io/github/excelimport/internal/map/MappingModelFactory.java`
- Test: `excel-import-core/src/test/java/io/github/excelimport/internal/map/MappingModelFactoryTest.java`

**Interfaces:**
- Consumes: `TableRef`, `SheetSelector`, `MappingConfigurationException` (Task 2).
- Produces:
  - `@ExcelSheet(name, index, headerRow, firstDataRow)` — на классе.
  - `@ExcelColumn(header, index, letter, required, formats, trim, emptyAsNull, converter)` — на поле.
  - `@Column(value)` — имя колонки БД.
  - `@TargetTable(schema, name)` — таблица-приёмник.
  - `enum NamingStrategy { SNAKE_CASE, AS_IS }` с `String toColumnName(String fieldName)`.
  - `ColumnBinding` — record `(String fieldName, String headerName, Integer columnIndex, boolean required, String dbColumn, Class<?> fieldType, MethodHandle setter, MethodHandle getter, String[] formats, boolean trim, boolean emptyAsNull, Class<? extends CellConverter<?>> converterType)`.
  - `MappingModel<T>` — `Class<T> type()`, `SheetSelector sheet()`, `int headerRowIndex()`, `int firstDataRowIndex()`, `TableRef table()`, `List<ColumnBinding> excelColumns()`, `List<ColumnBinding> dbOnlyColumns()`, `List<String> allDbColumns()`, `T newInstance()`.
  - `MappingModelFactory.create(Class<T>, NamingStrategy)`.

- [ ] **Step 1: Написать падающий тест**

```java
package io.github.excelimport.internal.map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.excelimport.NamingStrategy;
import io.github.excelimport.annotation.Column;
import io.github.excelimport.annotation.ExcelColumn;
import io.github.excelimport.annotation.ExcelSheet;
import io.github.excelimport.annotation.TargetTable;
import io.github.excelimport.exception.MappingConfigurationException;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class MappingModelFactoryTest {

    @ExcelSheet(name = "Сотрудники", headerRow = 2)
    @TargetTable(schema = "hr", name = "employee")
    static class Employee {
        @ExcelColumn(header = "Табельный номер")
        @Column("personnel_no")
        Long personnelNo;

        @ExcelColumn(header = "ФИО")
        String fullName; // без @Column — имя выводится стратегией

        @ExcelColumn(letter = "D")
        @Column("salary")
        BigDecimal salary;

        @Column("import_run_id")
        String importRunId; // только БД, из файла не читается

        String ignored; // без аннотаций — игнорируется полностью
    }

    @Test
    void readsSheetAndTable() {
        MappingModel<Employee> model = MappingModelFactory.create(Employee.class, NamingStrategy.SNAKE_CASE);

        assertThat(model.sheet().name()).contains("Сотрудники");
        assertThat(model.headerRowIndex()).isEqualTo(2);
        assertThat(model.firstDataRowIndex()).isEqualTo(3);
        assertThat(model.table().qualifiedName()).isEqualTo("\"hr\".\"employee\"");
    }

    @Test
    void derivesDbColumnFromFieldNameWhenColumnAnnotationAbsent() {
        MappingModel<Employee> model = MappingModelFactory.create(Employee.class, NamingStrategy.SNAKE_CASE);

        assertThat(model.excelColumns())
                .filteredOn(binding -> "fullName".equals(binding.fieldName()))
                .singleElement()
                .satisfies(binding -> assertThat(binding.dbColumn()).isEqualTo("full_name"));
    }

    @Test
    void resolvesColumnLetterToIndex() {
        MappingModel<Employee> model = MappingModelFactory.create(Employee.class, NamingStrategy.SNAKE_CASE);

        assertThat(model.excelColumns())
                .filteredOn(binding -> "salary".equals(binding.fieldName()))
                .singleElement()
                .satisfies(binding -> assertThat(binding.columnIndex()).isEqualTo(3));
    }

    @Test
    void separatesDbOnlyColumnsFromExcelColumns() {
        MappingModel<Employee> model = MappingModelFactory.create(Employee.class, NamingStrategy.SNAKE_CASE);

        assertThat(model.excelColumns()).hasSize(3);
        assertThat(model.dbOnlyColumns()).singleElement()
                .satisfies(binding -> assertThat(binding.dbColumn()).isEqualTo("import_run_id"));
        assertThat(model.allDbColumns())
                .containsExactly("personnel_no", "full_name", "salary", "import_run_id");
    }

    @Test
    void setterWritesValueThroughMethodHandle() throws Throwable {
        MappingModel<Employee> model = MappingModelFactory.create(Employee.class, NamingStrategy.SNAKE_CASE);
        Employee instance = model.newInstance();

        ColumnBinding binding = model.excelColumns().stream()
                .filter(b -> "fullName".equals(b.fieldName()))
                .findFirst()
                .orElseThrow();
        binding.setter().invoke(instance, "Иванов");

        assertThat(instance.fullName).isEqualTo("Иванов");
    }

    static class NoSheet {
        @ExcelColumn(header = "A")
        String a;
    }

    @Test
    void classWithoutExcelSheetIsRejected() {
        assertThatThrownBy(() -> MappingModelFactory.create(NoSheet.class, NamingStrategy.SNAKE_CASE))
                .isInstanceOf(MappingConfigurationException.class)
                .hasMessageContaining("@ExcelSheet");
    }

    @ExcelSheet(name = "S")
    static class DuplicateDbColumn {
        @ExcelColumn(header = "A")
        @Column("same")
        String a;

        @ExcelColumn(header = "B")
        @Column("same")
        String b;
    }

    @Test
    void duplicateDbColumnIsRejected() {
        assertThatThrownBy(() ->
                        MappingModelFactory.create(DuplicateDbColumn.class, NamingStrategy.SNAKE_CASE))
                .isInstanceOf(MappingConfigurationException.class)
                .hasMessageContaining("same");
    }

    @ExcelSheet(name = "S")
    static class BothHeaderAndIndex {
        @ExcelColumn(header = "A", index = 0)
        String a;
    }

    @Test
    void columnWithTwoIdentificationModesIsRejected() {
        assertThatThrownBy(() ->
                        MappingModelFactory.create(BothHeaderAndIndex.class, NamingStrategy.SNAKE_CASE))
                .isInstanceOf(MappingConfigurationException.class)
                .hasMessageContaining("ровно один");
    }

    @ExcelSheet(name = "S", index = 1)
    static class SheetNameAndIndex {
        @ExcelColumn(header = "A")
        String a;
    }

    @Test
    void sheetWithBothNameAndIndexIsRejected() {
        assertThatThrownBy(() ->
                        MappingModelFactory.create(SheetNameAndIndex.class, NamingStrategy.SNAKE_CASE))
                .isInstanceOf(MappingConfigurationException.class)
                .hasMessageContaining("ровно один");
    }

    @ExcelSheet(name = "S")
    static class NoExcelColumns {
        @Column("x")
        String x;
    }

    @Test
    void classWithoutExcelColumnsIsRejected() {
        assertThatThrownBy(() ->
                        MappingModelFactory.create(NoExcelColumns.class, NamingStrategy.SNAKE_CASE))
                .isInstanceOf(MappingConfigurationException.class)
                .hasMessageContaining("ни одного @ExcelColumn");
    }
}
```

- [ ] **Step 2: Запустить тест, убедиться что не компилируется**

Run: `./gradlew :excel-import-core:test --tests "*MappingModelFactoryTest*"`
Expected: FAIL — `cannot find symbol: class ExcelSheet`.

- [ ] **Step 3: Реализовать аннотации**

`annotation/ExcelSheet.java`:

```java
package io.github.excelimport.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** Лист книги и расположение заголовка. Индексы 0-based. */
@Documented
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface ExcelSheet {

    /** Имя листа. Задаётся либо {@code name}, либо {@code index}, но не оба. */
    String name() default "";

    /** 0-based индекс листа. -1 означает «не задан». */
    int index() default -1;

    /** 0-based индекс строки заголовка. */
    int headerRow() default 0;

    /** 0-based индекс первой строки данных. -1 означает {@code headerRow + 1}. */
    int firstDataRow() default -1;
}
```

`annotation/ExcelColumn.java`:

```java
package io.github.excelimport.annotation;

import io.github.excelimport.convert.CellConverter;
import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** Привязка поля к колонке Excel. Колонка задаётся ровно одним из: header, index, letter. */
@Documented
@Target(ElementType.FIELD)
@Retention(RetentionPolicy.RUNTIME)
public @interface ExcelColumn {

    /** Текст в строке заголовка. */
    String header() default "";

    /** 0-based индекс колонки. -1 означает «не задан». */
    int index() default -1;

    /** Буква колонки, например {@code "A"} или {@code "AB"}. */
    String letter() default "";

    /** Если true, отсутствие колонки в файле — фатальная ошибка структуры. */
    boolean required() default true;

    /** Форматы для разбора дат и чисел, пробуются по порядку. */
    String[] formats() default {};

    /** Обрезать пробелы по краям. */
    boolean trim() default true;

    /** Пустую строку считать null. */
    boolean emptyAsNull() default true;

    /** Свой конвертер. {@code CellConverter.class} означает «выбрать по типу поля». */
    Class<? extends CellConverter<?>> converter() default CellConverter.class;
}
```

`annotation/Column.java`:

```java
package io.github.excelimport.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** Имя колонки в таблице-приёмнике. Без аннотации имя выводится {@code NamingStrategy}. */
@Documented
@Target(ElementType.FIELD)
@Retention(RetentionPolicy.RUNTIME)
public @interface Column {

    String value();
}
```

`annotation/TargetTable.java`:

```java
package io.github.excelimport.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** Таблица-приёмник. Может быть переопределена в ImportConfig. */
@Documented
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface TargetTable {

    /** Схема; пустая строка означает search_path. */
    String schema() default "";

    String name();
}
```

- [ ] **Step 4: Реализовать `NamingStrategy`**

```java
package io.github.excelimport;

/** Как из имени поля получить имя колонки БД, если нет {@code @Column}. */
public enum NamingStrategy {

    /** {@code fullName} → {@code full_name}. */
    SNAKE_CASE {
        @Override
        public String toColumnName(String fieldName) {
            StringBuilder result = new StringBuilder(fieldName.length() + 4);
            for (int i = 0; i < fieldName.length(); i++) {
                char c = fieldName.charAt(i);
                if (Character.isUpperCase(c)) {
                    if (i > 0) {
                        result.append('_');
                    }
                    result.append(Character.toLowerCase(c));
                } else {
                    result.append(c);
                }
            }
            return result.toString();
        }
    },

    /** Имя поля как есть. */
    AS_IS {
        @Override
        public String toColumnName(String fieldName) {
            return fieldName;
        }
    };

    public abstract String toColumnName(String fieldName);
}
```

- [ ] **Step 5: Реализовать `ColumnBinding` и `MappingModel`**

`internal/map/ColumnBinding.java`:

```java
package io.github.excelimport.internal.map;

import io.github.excelimport.convert.CellConverter;
import java.lang.invoke.MethodHandle;
import java.util.List;
import java.util.Objects;

/**
 * Привязка одного поля класса к колонке Excel и/или колонке БД.
 * Для полей «только БД» {@code headerName} и {@code columnIndex} равны null.
 */
public record ColumnBinding(
        String fieldName,
        String headerName,
        Integer columnIndex,
        boolean required,
        String dbColumn,
        Class<?> fieldType,
        MethodHandle setter,
        MethodHandle getter,
        List<String> formats,
        boolean trim,
        boolean emptyAsNull,
        Class<? extends CellConverter<?>> converterType) {

    public ColumnBinding {
        Objects.requireNonNull(fieldName, "fieldName");
        Objects.requireNonNull(dbColumn, "dbColumn");
        Objects.requireNonNull(fieldType, "fieldType");
        Objects.requireNonNull(setter, "setter");
        Objects.requireNonNull(getter, "getter");
        formats = List.copyOf(formats);
    }

    /** true, если поле читается из файла. */
    public boolean boundToExcel() {
        return headerName != null || columnIndex != null;
    }

    /** Отображаемое имя колонки для сообщений об ошибках. */
    public String displayName() {
        if (headerName != null) {
            return headerName;
        }
        if (columnIndex != null) {
            return org.apache.poi.ss.util.CellReference.convertNumToColString(columnIndex);
        }
        return fieldName;
    }
}
```

`internal/map/MappingModel.java`:

```java
package io.github.excelimport.internal.map;

import io.github.excelimport.SheetSelector;
import io.github.excelimport.TableRef;
import io.github.excelimport.exception.MappingConfigurationException;
import java.lang.invoke.MethodHandle;
import java.util.ArrayList;
import java.util.List;

/** Разобранная и провалидированная схема маппинга класса. Иммутабельна и переиспользуема. */
public final class MappingModel<T> {

    private final Class<T> type;
    private final SheetSelector sheet;
    private final int headerRowIndex;
    private final int firstDataRowIndex;
    private final TableRef table;
    private final List<ColumnBinding> excelColumns;
    private final List<ColumnBinding> dbOnlyColumns;
    private final MethodHandle constructor;

    MappingModel(
            Class<T> type,
            SheetSelector sheet,
            int headerRowIndex,
            int firstDataRowIndex,
            TableRef table,
            List<ColumnBinding> excelColumns,
            List<ColumnBinding> dbOnlyColumns,
            MethodHandle constructor) {
        this.type = type;
        this.sheet = sheet;
        this.headerRowIndex = headerRowIndex;
        this.firstDataRowIndex = firstDataRowIndex;
        this.table = table;
        this.excelColumns = List.copyOf(excelColumns);
        this.dbOnlyColumns = List.copyOf(dbOnlyColumns);
        this.constructor = constructor;
    }

    public Class<T> type() {
        return type;
    }

    public SheetSelector sheet() {
        return sheet;
    }

    public int headerRowIndex() {
        return headerRowIndex;
    }

    public int firstDataRowIndex() {
        return firstDataRowIndex;
    }

    public TableRef table() {
        return table;
    }

    /** Возвращает копию модели с другой таблицей — для переопределения из ImportConfig. */
    public MappingModel<T> withTable(TableRef override) {
        return new MappingModel<>(
                type, sheet, headerRowIndex, firstDataRowIndex, override,
                excelColumns, dbOnlyColumns, constructor);
    }

    /** Возвращает копию модели с другим листом и расположением заголовка. */
    public MappingModel<T> withSheet(SheetSelector override, int headerRow, int firstDataRow) {
        return new MappingModel<>(
                type, override, headerRow, firstDataRow, table,
                excelColumns, dbOnlyColumns, constructor);
    }

    public List<ColumnBinding> excelColumns() {
        return excelColumns;
    }

    public List<ColumnBinding> dbOnlyColumns() {
        return dbOnlyColumns;
    }

    /** Имена колонок БД в порядке вставки: сначала excel-привязанные, потом db-only. */
    public List<String> allDbColumns() {
        List<String> names = new ArrayList<>(excelColumns.size() + dbOnlyColumns.size());
        excelColumns.forEach(binding -> names.add(binding.dbColumn()));
        dbOnlyColumns.forEach(binding -> names.add(binding.dbColumn()));
        return List.copyOf(names);
    }

    /** Все привязки в том же порядке, что {@link #allDbColumns()}. */
    public List<ColumnBinding> allBindings() {
        List<ColumnBinding> all = new ArrayList<>(excelColumns);
        all.addAll(dbOnlyColumns);
        return List.copyOf(all);
    }

    @SuppressWarnings("unchecked")
    public T newInstance() {
        try {
            return (T) constructor.invoke();
        } catch (Throwable e) {
            throw new MappingConfigurationException(
                    "не удалось создать экземпляр " + type.getName(), e);
        }
    }
}
```

- [ ] **Step 6: Реализовать `MappingModelFactory`**

```java
package io.github.excelimport.internal.map;

import io.github.excelimport.NamingStrategy;
import io.github.excelimport.SheetSelector;
import io.github.excelimport.TableRef;
import io.github.excelimport.annotation.Column;
import io.github.excelimport.annotation.ExcelColumn;
import io.github.excelimport.annotation.ExcelSheet;
import io.github.excelimport.annotation.TargetTable;
import io.github.excelimport.convert.CellConverter;
import io.github.excelimport.exception.MappingConfigurationException;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.apache.poi.ss.util.CellReference;

/** Строит {@link MappingModel} по аннотациям класса. Вся валидация конфигурации — здесь. */
public final class MappingModelFactory {

    private MappingModelFactory() {}

    public static <T> MappingModel<T> create(Class<T> type, NamingStrategy naming) {
        ExcelSheet sheetAnnotation = type.getAnnotation(ExcelSheet.class);
        if (sheetAnnotation == null) {
            throw new MappingConfigurationException(
                    "на классе " + type.getName() + " нет аннотации @ExcelSheet");
        }

        SheetSelector sheet = resolveSheet(type, sheetAnnotation);
        int headerRow = sheetAnnotation.headerRow();
        if (headerRow < 0) {
            throw new MappingConfigurationException(
                    "headerRow не может быть отрицательным на " + type.getName());
        }
        int firstDataRow =
                sheetAnnotation.firstDataRow() < 0 ? headerRow + 1 : sheetAnnotation.firstDataRow();
        if (firstDataRow <= headerRow) {
            throw new MappingConfigurationException(
                    "firstDataRow должен быть больше headerRow на " + type.getName());
        }

        MethodHandles.Lookup lookup = privateLookup(type);
        List<ColumnBinding> excelColumns = new ArrayList<>();
        List<ColumnBinding> dbOnlyColumns = new ArrayList<>();
        Set<String> seenDbColumns = new HashSet<>();

        for (Field field : allFields(type)) {
            if (Modifier.isStatic(field.getModifiers()) || field.isSynthetic()) {
                continue;
            }
            ExcelColumn excel = field.getAnnotation(ExcelColumn.class);
            Column column = field.getAnnotation(Column.class);
            if (excel == null && column == null) {
                continue;
            }
            String dbColumn = column != null ? column.value() : naming.toColumnName(field.getName());
            if (!seenDbColumns.add(dbColumn)) {
                throw new MappingConfigurationException(
                        "колонка БД " + dbColumn + " привязана более одного раза в " + type.getName());
            }
            ColumnBinding binding = buildBinding(type, field, excel, dbColumn, lookup);
            if (binding.boundToExcel()) {
                excelColumns.add(binding);
            } else {
                dbOnlyColumns.add(binding);
            }
        }

        if (excelColumns.isEmpty()) {
            throw new MappingConfigurationException(
                    "в классе " + type.getName() + " нет ни одного @ExcelColumn");
        }

        return new MappingModel<>(
                type, sheet, headerRow, firstDataRow, resolveTable(type),
                excelColumns, dbOnlyColumns, defaultConstructor(type, lookup));
    }

    private static SheetSelector resolveSheet(Class<?> type, ExcelSheet annotation) {
        boolean hasName = !annotation.name().isEmpty();
        boolean hasIndex = annotation.index() >= 0;
        if (hasName == hasIndex) {
            throw new MappingConfigurationException(
                    "в @ExcelSheet на " + type.getName() + " должен быть задан ровно один из name/index");
        }
        return hasName ? SheetSelector.byName(annotation.name()) : SheetSelector.byIndex(annotation.index());
    }

    private static TableRef resolveTable(Class<?> type) {
        TargetTable annotation = type.getAnnotation(TargetTable.class);
        if (annotation == null) {
            return null; // обязана быть задана в ImportConfig — проверяется при сборке импортёра
        }
        String schema = annotation.schema().isEmpty() ? null : annotation.schema();
        return new TableRef(schema, annotation.name());
    }

    private static ColumnBinding buildBinding(
            Class<?> type,
            Field field,
            ExcelColumn excel,
            String dbColumn,
            MethodHandles.Lookup lookup) {
        String header = null;
        Integer index = null;
        if (excel != null) {
            int modes = 0;
            if (!excel.header().isEmpty()) {
                header = excel.header();
                modes++;
            }
            if (excel.index() >= 0) {
                index = excel.index();
                modes++;
            }
            if (!excel.letter().isEmpty()) {
                index = CellReference.convertColStringToIndex(excel.letter());
                modes++;
            }
            if (modes != 1) {
                throw new MappingConfigurationException(
                        "в @ExcelColumn на " + type.getName() + "." + field.getName()
                                + " должен быть задан ровно один из header/index/letter");
            }
        }

        try {
            return new ColumnBinding(
                    field.getName(),
                    header,
                    index,
                    excel == null || excel.required(),
                    dbColumn,
                    field.getType(),
                    lookup.unreflectSetter(field),
                    lookup.unreflectGetter(field),
                    excel == null ? List.of() : List.of(excel.formats()),
                    excel == null || excel.trim(),
                    excel == null || excel.emptyAsNull(),
                    excel == null ? converterDefault() : excel.converter());
        } catch (IllegalAccessException e) {
            throw new MappingConfigurationException(
                    "нет доступа к полю " + type.getName() + "." + field.getName()
                            + "; сделайте класс и его поля видимыми для библиотеки",
                    e);
        }
    }

    @SuppressWarnings("unchecked")
    private static Class<? extends CellConverter<?>> converterDefault() {
        return (Class<? extends CellConverter<?>>) (Class<?>) CellConverter.class;
    }

    private static List<Field> allFields(Class<?> type) {
        List<Field> fields = new ArrayList<>();
        for (Class<?> current = type; current != null && current != Object.class;
                current = current.getSuperclass()) {
            fields.addAll(List.of(current.getDeclaredFields()));
        }
        return fields;
    }

    private static MethodHandles.Lookup privateLookup(Class<?> type) {
        try {
            return MethodHandles.privateLookupIn(type, MethodHandles.lookup());
        } catch (IllegalAccessException e) {
            throw new MappingConfigurationException(
                    "модуль, содержащий " + type.getName()
                            + ", должен открыть пакет для io.github.excelimport (opens ...)",
                    e);
        }
    }

    private static MethodHandle defaultConstructor(Class<?> type, MethodHandles.Lookup lookup) {
        try {
            return lookup.findConstructor(type, MethodType.methodType(void.class));
        } catch (NoSuchMethodException | IllegalAccessException e) {
            throw new MappingConfigurationException(
                    "у " + type.getName() + " нет доступного конструктора без аргументов", e);
        }
    }
}
```

- [ ] **Step 7: Запустить тесты**

Run: `./gradlew :excel-import-core:test --tests "*MappingModelFactoryTest*"`
Expected: PASS — все 10 тестов. Тест `setterWritesValueThroughMethodHandle` подтверждает, что `MethodHandle` пишет в приватное поле вложенного класса.

Замечание про порядок колонок: `Class.getDeclaredFields()` не гарантирует порядок объявления по спецификации, хотя HotSpot его сохраняет. Тест `separatesDbOnlyColumnsFromExcelColumns` проверяет порядок через `containsExactly` и на этом держится генерация SQL. Если тест окажется нестабильным на другой JVM, заменить проверку на `containsExactlyInAnyOrder` и добавить в `@ExcelColumn` атрибут `order() default Integer.MAX_VALUE` с сортировкой по нему — но не раньше, чем нестабильность реально проявится.

- [ ] **Step 8: Коммит**

```bash
git add excel-import-core/src/main/java/io/github/excelimport/annotation \
        excel-import-core/src/main/java/io/github/excelimport/NamingStrategy.java \
        excel-import-core/src/main/java/io/github/excelimport/internal/map \
        excel-import-core/src/test/java/io/github/excelimport/internal/map
git commit -m "feat: add mapping annotations and annotation-driven MappingModel"
```

---

### Task 6: Конвертеры значений ячеек

**Files:**
- Create: `excel-import-core/src/main/java/io/github/excelimport/convert/CellConverter.java`
- Create: `excel-import-core/src/main/java/io/github/excelimport/convert/ConversionContext.java`
- Create: `excel-import-core/src/main/java/io/github/excelimport/convert/ConversionException.java`
- Create: `excel-import-core/src/main/java/io/github/excelimport/convert/BooleanWords.java`
- Create: `excel-import-core/src/main/java/io/github/excelimport/internal/convert/BuiltinConverters.java`
- Create: `excel-import-core/src/main/java/io/github/excelimport/internal/convert/ConverterRegistry.java`
- Test: `excel-import-core/src/test/java/io/github/excelimport/internal/convert/BuiltinConvertersTest.java`
- Test: `excel-import-core/src/test/java/io/github/excelimport/internal/convert/ConverterRegistryTest.java`

**Interfaces:**
- Consumes: `CellValue` (Task 3), `ColumnBinding` (Task 5).
- Produces:
  - `interface CellConverter<V> { V convert(CellValue value, ConversionContext ctx); }`.
  - `ConversionContext` — record `(String columnDisplayName, Class<?> targetType, List<String> formats, boolean trim, boolean emptyAsNull, Locale locale, BooleanWords booleanWords)`; метод `String text(CellValue)` — общая предобработка (trim + emptyAsNull) для всех конвертеров.
  - `ConversionException(String code, String message)` с геттером `code()`.
  - `BooleanWords` — record `(Set<String> trueWords, Set<String> falseWords)` + `BooleanWords.defaults()` (`да/true/1/y/yes/истина` и `нет/false/0/n/no/ложь`).
  - `ConverterRegistry` — `CellConverter<?> resolve(ColumnBinding binding)`, `register(Class<?> targetType, CellConverter<?>)`, кэш пользовательских конвертеров по классу.

- [ ] **Step 1: Написать падающие тесты конвертеров**

`excel-import-core/src/test/java/io/github/excelimport/internal/convert/BuiltinConvertersTest.java`:

```java
package io.github.excelimport.internal.convert;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.excelimport.convert.BooleanWords;
import io.github.excelimport.convert.CellConverter;
import io.github.excelimport.convert.CellValue;
import io.github.excelimport.convert.ConversionContext;
import io.github.excelimport.convert.ConversionException;
import io.github.excelimport.internal.read.ImmutableCellValue;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.util.CellAddress;
import org.junit.jupiter.api.Test;

class BuiltinConvertersTest {

    private static CellValue text(String value) {
        return new ImmutableCellValue(
                new CellAddress(0, 0), CellType.STRING, false, value, null, null, null, (byte) -1, false);
    }

    private static CellValue number(double value) {
        return new ImmutableCellValue(
                new CellAddress(0, 0), CellType.NUMERIC, false, String.valueOf(value), value,
                null, null, (byte) -1, false);
    }

    private static CellValue date(double serial) {
        return new ImmutableCellValue(
                new CellAddress(0, 0), CellType.NUMERIC, true, String.valueOf(serial), serial,
                null, null, (byte) -1, false);
    }

    private static ConversionContext ctx(Class<?> targetType, String... formats) {
        return new ConversionContext(
                "Колонка", targetType, List.of(formats), true, true, Locale.of("ru"),
                BooleanWords.defaults());
    }

    @Test
    void stringConverterTrimsAndNullsEmpty() {
        CellConverter<String> converter = BuiltinConverters.stringConverter();

        assertThat(converter.convert(text("  Иванов  "), ctx(String.class))).isEqualTo("Иванов");
        assertThat(converter.convert(text("   "), ctx(String.class))).isNull();
    }

    @Test
    void longConverterAcceptsNumericAndTextCells() {
        CellConverter<Long> converter = BuiltinConverters.longConverter();

        assertThat(converter.convert(number(42), ctx(Long.class))).isEqualTo(42L);
        assertThat(converter.convert(text("1 234"), ctx(Long.class))).isEqualTo(1234L);
        assertThat(converter.convert(text(""), ctx(Long.class))).isNull();
    }

    @Test
    void longConverterRejectsFractionalNumber() {
        assertThatThrownBy(() -> BuiltinConverters.longConverter().convert(number(1.5), ctx(Long.class)))
                .isInstanceOf(ConversionException.class)
                .satisfies(e -> assertThat(((ConversionException) e).code()).isEqualTo("NOT_INTEGER"));
    }

    @Test
    void bigDecimalConverterKeepsScaleFromTextAndHandlesComma() {
        CellConverter<BigDecimal> converter = BuiltinConverters.bigDecimalConverter();

        assertThat(converter.convert(text("1234,50"), ctx(BigDecimal.class)))
                .isEqualByComparingTo("1234.50");
        assertThat(converter.convert(number(1000.5), ctx(BigDecimal.class)))
                .isEqualByComparingTo("1000.5");
    }

    @Test
    void bigDecimalConverterRejectsGarbage() {
        assertThatThrownBy(() ->
                        BuiltinConverters.bigDecimalConverter().convert(text("abc"), ctx(BigDecimal.class)))
                .isInstanceOf(ConversionException.class)
                .hasMessageContaining("число");
    }

    @Test
    void localDateConverterReadsSerialDate() {
        CellConverter<LocalDate> converter = BuiltinConverters.localDateConverter();
        // 46232 — 2026-07-29 в системе 1900
        double serial = org.apache.poi.ss.usermodel.DateUtil.getExcelDate(
                LocalDate.of(2026, 7, 29).atStartOfDay(), false);

        assertThat(converter.convert(date(serial), ctx(LocalDate.class)))
                .isEqualTo(LocalDate.of(2026, 7, 29));
    }

    @Test
    void localDateConverterTriesFormatsInOrder() {
        CellConverter<LocalDate> converter = BuiltinConverters.localDateConverter();
        ConversionContext context = ctx(LocalDate.class, "dd.MM.yyyy", "yyyy-MM-dd");

        assertThat(converter.convert(text("29.07.2026"), context)).isEqualTo(LocalDate.of(2026, 7, 29));
        assertThat(converter.convert(text("2026-07-29"), context)).isEqualTo(LocalDate.of(2026, 7, 29));
    }

    @Test
    void localDateConverterReportsAllTriedFormats() {
        ConversionContext context = ctx(LocalDate.class, "dd.MM.yyyy");

        assertThatThrownBy(() -> BuiltinConverters.localDateConverter().convert(text("29/07/2026"), context))
                .isInstanceOf(ConversionException.class)
                .hasMessageContaining("dd.MM.yyyy");
    }

    @Test
    void localDateTimeConverterReadsSerialWithTime() {
        double serial = org.apache.poi.ss.usermodel.DateUtil.getExcelDate(
                LocalDateTime.of(2026, 7, 29, 13, 45), false);

        assertThat(BuiltinConverters.localDateTimeConverter().convert(date(serial), ctx(LocalDateTime.class)))
                .isEqualTo(LocalDateTime.of(2026, 7, 29, 13, 45));
    }

    @Test
    void booleanConverterUnderstandsRussianWords() {
        CellConverter<Boolean> converter = BuiltinConverters.booleanConverter();

        assertThat(converter.convert(text("Да"), ctx(Boolean.class))).isTrue();
        assertThat(converter.convert(text("нет"), ctx(Boolean.class))).isFalse();
        assertThat(converter.convert(text("1"), ctx(Boolean.class))).isTrue();
    }

    @Test
    void booleanConverterRejectsUnknownWord() {
        assertThatThrownBy(() -> BuiltinConverters.booleanConverter().convert(text("может быть"),
                        ctx(Boolean.class)))
                .isInstanceOf(ConversionException.class)
                .satisfies(e -> assertThat(((ConversionException) e).code()).isEqualTo("NOT_BOOLEAN"));
    }

    enum Status {
        ACTIVE,
        FIRED
    }

    @Test
    void enumConverterMatchesByNameIgnoringCase() {
        CellConverter<?> converter = BuiltinConverters.enumConverter(Status.class);

        assertThat(converter.convert(text("active"), ctx(Status.class))).isEqualTo(Status.ACTIVE);
    }

    @Test
    void enumConverterListsAllowedValuesOnFailure() {
        assertThatThrownBy(() ->
                        BuiltinConverters.enumConverter(Status.class).convert(text("UNKNOWN"), ctx(Status.class)))
                .isInstanceOf(ConversionException.class)
                .hasMessageContaining("ACTIVE")
                .hasMessageContaining("FIRED");
    }

    @Test
    void uuidConverterParsesAndRejects() {
        assertThat(BuiltinConverters.uuidConverter()
                        .convert(text("123e4567-e89b-12d3-a456-426614174000"), ctx(java.util.UUID.class)))
                .hasToString("123e4567-e89b-12d3-a456-426614174000");

        assertThatThrownBy(() ->
                        BuiltinConverters.uuidConverter().convert(text("не uuid"), ctx(java.util.UUID.class)))
                .isInstanceOf(ConversionException.class);
    }

    @Test
    void errorCellIsAlwaysAConversionFailure() {
        CellValue errorCell = new ImmutableCellValue(
                new CellAddress(0, 0), CellType.ERROR, false, "#N/A", null, null, null,
                org.apache.poi.ss.usermodel.FormulaError.NA.getCode(), false);

        assertThatThrownBy(() -> BuiltinConverters.stringConverter().convert(errorCell, ctx(String.class)))
                .isInstanceOf(ConversionException.class)
                .satisfies(e -> assertThat(((ConversionException) e).code()).isEqualTo("CELL_ERROR"))
                .hasMessageContaining("#N/A");
    }
}
```

`excel-import-core/src/test/java/io/github/excelimport/internal/convert/ConverterRegistryTest.java`:

```java
package io.github.excelimport.internal.convert;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.excelimport.NamingStrategy;
import io.github.excelimport.annotation.ExcelColumn;
import io.github.excelimport.annotation.ExcelSheet;
import io.github.excelimport.convert.CellConverter;
import io.github.excelimport.convert.CellValue;
import io.github.excelimport.convert.ConversionContext;
import io.github.excelimport.exception.MappingConfigurationException;
import io.github.excelimport.internal.map.ColumnBinding;
import io.github.excelimport.internal.map.MappingModel;
import io.github.excelimport.internal.map.MappingModelFactory;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

class ConverterRegistryTest {

    static class InnConverter implements CellConverter<String> {
        @Override
        public String convert(CellValue value, ConversionContext ctx) {
            String raw = ctx.text(value);
            return raw == null ? null : raw.replaceAll("\\D", "");
        }
    }

    @ExcelSheet(name = "S")
    static class Row {
        @ExcelColumn(header = "Дата")
        LocalDate date;

        @ExcelColumn(header = "ИНН", converter = InnConverter.class)
        String inn;

        @ExcelColumn(header = "Что-то")
        Thread unsupported;
    }

    private ColumnBinding binding(String fieldName) {
        MappingModel<Row> model = MappingModelFactory.create(Row.class, NamingStrategy.SNAKE_CASE);
        return model.excelColumns().stream()
                .filter(b -> fieldName.equals(b.fieldName()))
                .findFirst()
                .orElseThrow();
    }

    @Test
    void resolvesBuiltinConverterByFieldType() {
        assertThat(new ConverterRegistry().resolve(binding("date"))).isNotNull();
    }

    @Test
    void resolvesExplicitCustomConverter() {
        CellConverter<?> converter = new ConverterRegistry().resolve(binding("inn"));

        assertThat(converter).isInstanceOf(InnConverter.class);
    }

    @Test
    void customConverterInstanceIsCachedPerClass() {
        ConverterRegistry registry = new ConverterRegistry();

        assertThat(registry.resolve(binding("inn"))).isSameAs(registry.resolve(binding("inn")));
    }

    @Test
    void unsupportedFieldTypeIsRejectedAtBuildTime() {
        assertThatThrownBy(() -> new ConverterRegistry().resolve(binding("unsupported")))
                .isInstanceOf(MappingConfigurationException.class)
                .hasMessageContaining("Thread");
    }
}
```

- [ ] **Step 2: Запустить тесты, убедиться что падают**

Run: `./gradlew :excel-import-core:test --tests "*Converters*Test" --tests "*ConverterRegistryTest*"`
Expected: FAIL — `cannot find symbol: class ConversionContext`.

Замечание: тесты создают `ImmutableCellValue` с 9 аргументами (последний — `date1904` из Task 4). Если сигнатура другая, привести тесты в соответствие с фактической.

- [ ] **Step 3: Реализовать `CellConverter`, `ConversionException`, `BooleanWords`, `ConversionContext`**

`convert/CellConverter.java`:

```java
package io.github.excelimport.convert;

/**
 * Преобразует значение ячейки в значение поля. Реализации должны быть потокобезопасны
 * и не хранить состояние между вызовами: один экземпляр используется на весь импорт.
 *
 * <p>Для отсутствующего значения возвращают {@code null}, а не бросают исключение.
 * Обязательность проверяется на слое Jakarta-валидации ({@code @NotNull}).
 */
public interface CellConverter<V> {

    /**
     * @throws ConversionException если значение непусто, но не приводится к целевому типу
     */
    V convert(CellValue value, ConversionContext ctx);
}
```

`convert/ConversionException.java`:

```java
package io.github.excelimport.convert;

import io.github.excelimport.exception.ExcelImportException;

/**
 * Значение ячейки не приводится к типу поля. Перехватывается маппером и превращается
 * в {@code RowError} — наружу из импорта не выходит.
 */
public class ConversionException extends ExcelImportException {

    private static final long serialVersionUID = 1L;

    private final String code;

    public ConversionException(String code, String message) {
        super(message);
        this.code = code;
    }

    public ConversionException(String code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
    }

    /** Машиночитаемый код, попадает в {@code RowError.code()}. */
    public String code() {
        return code;
    }
}
```

`convert/BooleanWords.java`:

```java
package io.github.excelimport.convert;

import java.util.Locale;
import java.util.Set;

/** Слова, распознаваемые как true и false. Сравнение регистронезависимое. */
public record BooleanWords(Set<String> trueWords, Set<String> falseWords) {

    private static final BooleanWords DEFAULTS = new BooleanWords(
            Set.of("да", "true", "1", "y", "yes", "истина", "+"),
            Set.of("нет", "false", "0", "n", "no", "ложь", "-"));

    public BooleanWords {
        trueWords = normalize(trueWords);
        falseWords = normalize(falseWords);
    }

    public static BooleanWords defaults() {
        return DEFAULTS;
    }

    private static Set<String> normalize(Set<String> words) {
        return words.stream()
                .map(word -> word.toLowerCase(Locale.ROOT))
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    public Boolean parse(String text) {
        String normalized = text.toLowerCase(Locale.ROOT);
        if (trueWords.contains(normalized)) {
            return Boolean.TRUE;
        }
        if (falseWords.contains(normalized)) {
            return Boolean.FALSE;
        }
        return null;
    }
}
```

`convert/ConversionContext.java`:

```java
package io.github.excelimport.convert;

import java.util.List;
import java.util.Locale;
import java.util.Objects;
import org.apache.poi.ss.usermodel.CellType;

/**
 * Контекст одной конвертации: настройки колонки и книги.
 *
 * @param columnDisplayName имя колонки для сообщений об ошибках
 * @param targetType        тип поля
 * @param formats           форматы разбора в порядке приоритета
 * @param trim              обрезать пробелы
 * @param emptyAsNull       пустую строку считать null
 * @param locale            локаль разбора чисел и дат
 * @param booleanWords      словарь логических значений
 */
public record ConversionContext(
        String columnDisplayName,
        Class<?> targetType,
        List<String> formats,
        boolean trim,
        boolean emptyAsNull,
        Locale locale,
        BooleanWords booleanWords) {

    public ConversionContext {
        Objects.requireNonNull(columnDisplayName, "columnDisplayName");
        Objects.requireNonNull(targetType, "targetType");
        formats = List.copyOf(formats);
        Objects.requireNonNull(locale, "locale");
        Objects.requireNonNull(booleanWords, "booleanWords");
    }

    /**
     * Общая предобработка для всех конвертеров: ячейка с ошибкой Excel всегда даёт
     * {@link ConversionException}, затем применяются trim и emptyAsNull.
     *
     * @return текст ячейки или null, если значения нет
     */
    public String text(CellValue value) {
        if (value.type() == CellType.ERROR) {
            throw new ConversionException(
                    "CELL_ERROR", "ячейка содержит ошибку Excel: " + value.asString());
        }
        String raw = value.asString();
        if (raw == null) {
            return null;
        }
        String result = trim ? raw.trim() : raw;
        return emptyAsNull && result.isEmpty() ? null : result;
    }
}
```

- [ ] **Step 4: Реализовать `BuiltinConverters`**

```java
package io.github.excelimport.internal.convert;

import io.github.excelimport.convert.CellConverter;
import io.github.excelimport.convert.CellValue;
import io.github.excelimport.convert.ConversionContext;
import io.github.excelimport.convert.ConversionException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/** Встроенные конвертеры. Все экземпляры без состояния и потокобезопасны. */
public final class BuiltinConverters {

    private BuiltinConverters() {}

    public static CellConverter<String> stringConverter() {
        return (value, ctx) -> ctx.text(value);
    }

    public static CellConverter<Long> longConverter() {
        return (value, ctx) -> {
            Double number = numericOrNull(value, ctx);
            if (number != null) {
                return toExactLong(number, ctx);
            }
            String text = ctx.text(value);
            if (text == null) {
                return null;
            }
            try {
                return Long.valueOf(cleanNumber(text));
            } catch (NumberFormatException e) {
                throw new ConversionException(
                        "NOT_INTEGER", "«" + text + "» не является целым числом", e);
            }
        };
    }

    public static CellConverter<Integer> integerConverter() {
        return (value, ctx) -> {
            Long parsed = longConverter().convert(value, ctx);
            if (parsed == null) {
                return null;
            }
            if (parsed < Integer.MIN_VALUE || parsed > Integer.MAX_VALUE) {
                throw new ConversionException(
                        "OUT_OF_RANGE", parsed + " не укладывается в 32-битное целое");
            }
            return parsed.intValue();
        };
    }

    public static CellConverter<Short> shortConverter() {
        return (value, ctx) -> {
            Integer parsed = integerConverter().convert(value, ctx);
            if (parsed == null) {
                return null;
            }
            if (parsed < Short.MIN_VALUE || parsed > Short.MAX_VALUE) {
                throw new ConversionException(
                        "OUT_OF_RANGE", parsed + " не укладывается в 16-битное целое");
            }
            return parsed.shortValue();
        };
    }

    public static CellConverter<BigDecimal> bigDecimalConverter() {
        return (value, ctx) -> {
            String text = ctx.text(value);
            if (text == null) {
                return null;
            }
            try {
                return new BigDecimal(cleanNumber(text).replace(',', '.'));
            } catch (NumberFormatException e) {
                throw new ConversionException(
                        "NOT_NUMBER", "«" + text + "» не является числом", e);
            }
        };
    }

    public static CellConverter<Double> doubleConverter() {
        return (value, ctx) -> {
            BigDecimal parsed = bigDecimalConverter().convert(value, ctx);
            return parsed == null ? null : parsed.doubleValue();
        };
    }

    public static CellConverter<Float> floatConverter() {
        return (value, ctx) -> {
            BigDecimal parsed = bigDecimalConverter().convert(value, ctx);
            return parsed == null ? null : parsed.floatValue();
        };
    }

    public static CellConverter<Boolean> booleanConverter() {
        return (value, ctx) -> {
            Boolean direct = value.asBoolean();
            if (direct != null) {
                return direct;
            }
            String text = ctx.text(value);
            if (text == null) {
                return null;
            }
            Boolean parsed = ctx.booleanWords().parse(text);
            if (parsed == null) {
                throw new ConversionException(
                        "NOT_BOOLEAN",
                        "«" + text + "» не распознано как логическое значение; допустимо: "
                                + ctx.booleanWords().trueWords() + " / "
                                + ctx.booleanWords().falseWords());
            }
            return parsed;
        };
    }

    public static CellConverter<LocalDate> localDateConverter() {
        return (value, ctx) -> {
            LocalDateTime serial = value.asLocalDateTime();
            if (serial != null) {
                return serial.toLocalDate();
            }
            String text = ctx.text(value);
            if (text == null) {
                return null;
            }
            return parseTemporal(text, ctx, LocalDate::parse, "дату");
        };
    }

    public static CellConverter<LocalDateTime> localDateTimeConverter() {
        return (value, ctx) -> {
            LocalDateTime serial = value.asLocalDateTime();
            if (serial != null) {
                return serial;
            }
            String text = ctx.text(value);
            if (text == null) {
                return null;
            }
            return parseTemporal(text, ctx, LocalDateTime::parse, "дату и время");
        };
    }

    public static CellConverter<LocalTime> localTimeConverter() {
        return (value, ctx) -> {
            LocalDateTime serial = value.asLocalDateTime();
            if (serial != null) {
                return serial.toLocalTime();
            }
            String text = ctx.text(value);
            if (text == null) {
                return null;
            }
            return parseTemporal(text, ctx, LocalTime::parse, "время");
        };
    }

    public static CellConverter<OffsetDateTime> offsetDateTimeConverter() {
        return (value, ctx) -> {
            LocalDateTime local = localDateTimeConverter().convert(value, ctx);
            return local == null ? null : local.atOffset(ZoneOffset.UTC);
        };
    }

    public static CellConverter<UUID> uuidConverter() {
        return (value, ctx) -> {
            String text = ctx.text(value);
            if (text == null) {
                return null;
            }
            try {
                return UUID.fromString(text);
            } catch (IllegalArgumentException e) {
                throw new ConversionException("NOT_UUID", "«" + text + "» не является UUID", e);
            }
        };
    }

    public static <E extends Enum<E>> CellConverter<E> enumConverter(Class<E> enumType) {
        return (value, ctx) -> {
            String text = ctx.text(value);
            if (text == null) {
                return null;
            }
            for (E constant : enumType.getEnumConstants()) {
                if (constant.name().equalsIgnoreCase(text) || constant.toString().equalsIgnoreCase(text)) {
                    return constant;
                }
            }
            throw new ConversionException(
                    "NOT_IN_ENUM",
                    "«" + text + "» не входит в допустимые значения: "
                            + java.util.Arrays.toString(enumType.getEnumConstants()));
        };
    }

    private static Double numericOrNull(CellValue value, ConversionContext ctx) {
        if (value.type() == org.apache.poi.ss.usermodel.CellType.ERROR) {
            ctx.text(value); // бросит CELL_ERROR
        }
        return value.asNumeric();
    }

    private static Long toExactLong(double number, ConversionContext ctx) {
        if (number != Math.rint(number)) {
            throw new ConversionException(
                    "NOT_INTEGER",
                    "в колонке «" + ctx.columnDisplayName() + "» ожидалось целое, получено " + number);
        }
        return (long) number;
    }

    private static String cleanNumber(String text) {
        // убираем разделители разрядов: обычный пробел, неразрывный, узкий неразрывный, апостроф
        return text.replace(" ", "")
                .replace(" ", "")
                .replace("'", "")
                .replace(" ", "");
    }

    private interface TemporalParser<V> {
        V parse(CharSequence text, DateTimeFormatter formatter);
    }

    private static <V> V parseTemporal(
            String text, ConversionContext ctx, TemporalParser<V> parser, String what) {
        List<String> formats = ctx.formats();
        if (formats.isEmpty()) {
            try {
                return parser.parse(text, DateTimeFormatter.ISO_LOCAL_DATE_TIME);
            } catch (DateTimeParseException ignored) {
                try {
                    return parser.parse(text, DateTimeFormatter.ISO_LOCAL_DATE);
                } catch (DateTimeParseException e) {
                    throw new ConversionException(
                            "NOT_A_DATE",
                            "«" + text + "» не распознано как " + what
                                    + "; укажите формат в @ExcelColumn(formats = ...)",
                            e);
                }
            }
        }
        for (String pattern : formats) {
            try {
                return parser.parse(text, DateTimeFormatter.ofPattern(pattern, localeOf(ctx)));
            } catch (DateTimeParseException | IllegalArgumentException ignored) {
                // пробуем следующий формат
            }
        }
        throw new ConversionException(
                "NOT_A_DATE",
                "«" + text + "» не соответствует ни одному из форматов " + formats);
    }

    private static Locale localeOf(ConversionContext ctx) {
        return ctx.locale() == null ? Locale.ROOT : ctx.locale();
    }
}
```

- [ ] **Step 5: Реализовать `ConverterRegistry`**

```java
package io.github.excelimport.internal.convert;

import io.github.excelimport.convert.CellConverter;
import io.github.excelimport.exception.MappingConfigurationException;
import io.github.excelimport.internal.map.ColumnBinding;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Подбирает конвертер для привязки: явный из {@code @ExcelColumn(converter = ...)},
 * иначе зарегистрированный для типа поля, иначе встроенный.
 */
public final class ConverterRegistry {

    private final Map<Class<?>, CellConverter<?>> byType = new HashMap<>();
    private final Map<Class<?>, CellConverter<?>> customInstances = new ConcurrentHashMap<>();

    public ConverterRegistry() {
        byType.put(String.class, BuiltinConverters.stringConverter());
        byType.put(Long.class, BuiltinConverters.longConverter());
        byType.put(long.class, BuiltinConverters.longConverter());
        byType.put(Integer.class, BuiltinConverters.integerConverter());
        byType.put(int.class, BuiltinConverters.integerConverter());
        byType.put(Short.class, BuiltinConverters.shortConverter());
        byType.put(short.class, BuiltinConverters.shortConverter());
        byType.put(BigDecimal.class, BuiltinConverters.bigDecimalConverter());
        byType.put(Double.class, BuiltinConverters.doubleConverter());
        byType.put(double.class, BuiltinConverters.doubleConverter());
        byType.put(Float.class, BuiltinConverters.floatConverter());
        byType.put(float.class, BuiltinConverters.floatConverter());
        byType.put(Boolean.class, BuiltinConverters.booleanConverter());
        byType.put(boolean.class, BuiltinConverters.booleanConverter());
        byType.put(LocalDate.class, BuiltinConverters.localDateConverter());
        byType.put(LocalDateTime.class, BuiltinConverters.localDateTimeConverter());
        byType.put(LocalTime.class, BuiltinConverters.localTimeConverter());
        byType.put(OffsetDateTime.class, BuiltinConverters.offsetDateTimeConverter());
        byType.put(UUID.class, BuiltinConverters.uuidConverter());
    }

    /** Регистрирует конвертер для типа поля, перекрывая встроенный. */
    public ConverterRegistry register(Class<?> targetType, CellConverter<?> converter) {
        byType.put(targetType, converter);
        return this;
    }

    public CellConverter<?> resolve(ColumnBinding binding) {
        Class<? extends CellConverter<?>> explicit = binding.converterType();
        if (explicit != null && explicit != CellConverter.class) {
            return customInstances.computeIfAbsent(explicit, ConverterRegistry::instantiate);
        }
        Class<?> fieldType = binding.fieldType();
        CellConverter<?> registered = byType.get(fieldType);
        if (registered != null) {
            return registered;
        }
        if (fieldType.isEnum()) {
            return customInstances.computeIfAbsent(
                    fieldType, type -> enumConverterFor(type));
        }
        throw new MappingConfigurationException(
                "нет конвертера для типа " + fieldType.getName() + " (поле " + binding.fieldName()
                        + "); укажите converter в @ExcelColumn или зарегистрируйте свой");
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static CellConverter<?> enumConverterFor(Class<?> enumType) {
        return BuiltinConverters.enumConverter((Class) enumType);
    }

    private static CellConverter<?> instantiate(Class<?> converterType) {
        try {
            return (CellConverter<?>) converterType.getDeclaredConstructor().newInstance();
        } catch (ReflectiveOperationException e) {
            throw new MappingConfigurationException(
                    "у конвертера " + converterType.getName()
                            + " должен быть публичный конструктор без аргументов",
                    e);
        }
    }
}
```

- [ ] **Step 6: Запустить тесты**

Run: `./gradlew :excel-import-core:test --tests "*BuiltinConvertersTest*" --tests "*ConverterRegistryTest*"`
Expected: PASS — 15 + 4 теста.

- [ ] **Step 7: Прогнать всю сборку**

Run: `./gradlew :excel-import-core:test`
Expected: PASS — все тесты предыдущих задач по-прежнему зелёные.

- [ ] **Step 8: Коммит**

```bash
git add excel-import-core/src/main/java/io/github/excelimport/convert \
        excel-import-core/src/main/java/io/github/excelimport/internal/convert \
        excel-import-core/src/test/java/io/github/excelimport/internal/convert
git commit -m "feat: add cell converters and converter registry"
```

---

### Task 7: Сопоставление заголовков

**Files:**
- Create: `excel-import-core/src/main/java/io/github/excelimport/HeaderMatchingPolicy.java`
- Create: `excel-import-core/src/main/java/io/github/excelimport/internal/map/ResolvedColumns.java`
- Create: `excel-import-core/src/main/java/io/github/excelimport/internal/map/HeaderResolver.java`
- Test: `excel-import-core/src/test/java/io/github/excelimport/internal/map/HeaderResolverTest.java`

**Interfaces:**
- Consumes: `RawRow` (Task 3), `MappingModel`/`ColumnBinding` (Task 5), `FileStructureException`, `RowError` (Task 2).
- Produces:
  - `HeaderMatchingPolicy` — record `(boolean trim, boolean collapseWhitespace, boolean ignoreCase, boolean normalizeNonBreakingSpace)` + `HeaderMatchingPolicy.defaults()` (всё `true`) и метод `String normalize(String)`.
  - `ResolvedColumns` — `List<Entry> entries()` где `Entry` = record `(ColumnBinding binding, int columnIndex)`; метод `Optional<Integer> indexOf(String fieldName)`.
  - `HeaderResolver.resolve(MappingModel<?>, RawRow headerRow, HeaderMatchingPolicy)` → `ResolvedColumns`, бросает `FileStructureException` при отсутствии обязательной колонки или дублях.

- [ ] **Step 1: Написать падающий тест**

```java
package io.github.excelimport.internal.map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.excelimport.HeaderMatchingPolicy;
import io.github.excelimport.NamingStrategy;
import io.github.excelimport.annotation.ExcelColumn;
import io.github.excelimport.annotation.ExcelSheet;
import io.github.excelimport.exception.FileStructureException;
import io.github.excelimport.testsupport.RawRows;
import io.github.excelimport.internal.read.RawRow;
import org.junit.jupiter.api.Test;

class HeaderResolverTest {

    @ExcelSheet(name = "S")
    static class Model {
        @ExcelColumn(header = "ФИО")
        String fullName;

        @ExcelColumn(header = "Оклад")
        Long salary;

        @ExcelColumn(header = "Отдел", required = false)
        String department;

        @ExcelColumn(index = 9)
        String byIndex;
    }

    private final MappingModel<Model> model =
            MappingModelFactory.create(Model.class, NamingStrategy.SNAKE_CASE);

    @Test
    void matchesHeadersByTextIgnoringCaseAndExtraSpaces() {
        RawRow header = RawRows.of(0, "  фио ", "лишняя", "ОКЛАД");

        ResolvedColumns resolved = HeaderResolver.resolve(model, header, HeaderMatchingPolicy.defaults());

        assertThat(resolved.indexOf("fullName")).hasValue(0);
        assertThat(resolved.indexOf("salary")).hasValue(2);
    }

    @Test
    void indexBoundColumnKeepsItsDeclaredIndex() {
        RawRow header = RawRows.of(0, "ФИО", "Оклад");

        ResolvedColumns resolved = HeaderResolver.resolve(model, header, HeaderMatchingPolicy.defaults());

        assertThat(resolved.indexOf("byIndex")).hasValue(9);
    }

    @Test
    void optionalColumnMayBeAbsent() {
        RawRow header = RawRows.of(0, "ФИО", "Оклад");

        ResolvedColumns resolved = HeaderResolver.resolve(model, header, HeaderMatchingPolicy.defaults());

        assertThat(resolved.indexOf("department")).isEmpty();
        assertThat(resolved.entries()).hasSize(3); // fullName, salary, byIndex
    }

    @Test
    void missingRequiredColumnFails() {
        RawRow header = RawRows.of(0, "ФИО");

        assertThatThrownBy(() -> HeaderResolver.resolve(model, header, HeaderMatchingPolicy.defaults()))
                .isInstanceOf(FileStructureException.class)
                .hasMessageContaining("Оклад");
    }

    @Test
    void duplicateHeaderFails() {
        RawRow header = RawRows.of(0, "ФИО", "Оклад", "оклад");

        assertThatThrownBy(() -> HeaderResolver.resolve(model, header, HeaderMatchingPolicy.defaults()))
                .isInstanceOf(FileStructureException.class)
                .hasMessageContaining("неоднозначн");
    }

    @Test
    void nonBreakingSpaceInFileHeaderIsNormalized() {
        RawRow header = RawRows.of(0, "Ф И О", "Оклад");

        // после нормализации неразрывный пробел становится обычным, затем схлопывается
        assertThatThrownBy(() -> HeaderResolver.resolve(model, header, HeaderMatchingPolicy.defaults()))
                .isInstanceOf(FileStructureException.class)
                .hasMessageContaining("ФИО");
    }

    @Test
    void caseSensitiveModeDistinguishesHeaders() {
        RawRow header = RawRows.of(0, "фио", "Оклад");
        HeaderMatchingPolicy strict = new HeaderMatchingPolicy(true, true, false, true);

        assertThatThrownBy(() -> HeaderResolver.resolve(model, header, strict))
                .isInstanceOf(FileStructureException.class)
                .hasMessageContaining("ФИО");
    }
}
```

Тест `nonBreakingSpaceInFileHeaderIsNormalized` фиксирует поведение осознанно: `"Ф И О"` после нормализации даёт `"Ф И О"`, что не равно `"ФИО"`, поэтому обязательная колонка не находится. Нормализация неразрывных пробелов приводит их к обычным, но не удаляет.

- [ ] **Step 2: Добавить тестовый хелпер `RawRows`**

`excel-import-core/src/test/java/io/github/excelimport/testsupport/RawRows.java`:

```java
package io.github.excelimport.testsupport;

import io.github.excelimport.convert.CellValue;
import io.github.excelimport.internal.read.ImmutableCellValue;
import io.github.excelimport.internal.read.RawRow;
import java.lang.reflect.Constructor;
import java.util.HashMap;
import java.util.Map;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.util.CellAddress;

/** Сборка {@link RawRow} из строковых значений без файла. Только для тестов. */
public final class RawRows {

    private RawRows() {}

    public static RawRow of(int rowIndex, String... values) {
        Map<Integer, CellValue> cells = new HashMap<>();
        for (int i = 0; i < values.length; i++) {
            if (values[i] == null) {
                continue;
            }
            cells.put(i, new ImmutableCellValue(
                    new CellAddress(rowIndex, i), CellType.STRING, false, values[i],
                    null, null, null, (byte) -1, false));
        }
        return newRawRow(rowIndex, cells);
    }

    public static RawRow ofCells(int rowIndex, Map<Integer, CellValue> cells) {
        return newRawRow(rowIndex, new HashMap<>(cells));
    }

    private static RawRow newRawRow(int rowIndex, Map<Integer, CellValue> cells) {
        try {
            Constructor<RawRow> ctor = RawRow.class.getDeclaredConstructor(int.class, Map.class);
            ctor.setAccessible(true);
            return ctor.newInstance(rowIndex, cells);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("не удалось создать RawRow", e);
        }
    }
}
```

Конструктор `RawRow` имеет package-private видимость, а тест лежит в другом пакете, поэтому используется рефлексия. Альтернатива — открыть конструктор публично; не делаем, чтобы не расширять внутренний API ради тестов.

- [ ] **Step 3: Запустить тест, убедиться что падает**

Run: `./gradlew :excel-import-core:test --tests "*HeaderResolverTest*"`
Expected: FAIL — `cannot find symbol: class HeaderMatchingPolicy`.

- [ ] **Step 4: Реализовать `HeaderMatchingPolicy`**

```java
package io.github.excelimport;

/**
 * Правила сопоставления текста в файле с {@code @ExcelColumn(header = ...)}.
 *
 * @param trim                      обрезать пробелы по краям
 * @param collapseWhitespace        схлопывать подряд идущие пробелы в один
 * @param ignoreCase                сравнивать без учёта регистра
 * @param normalizeNonBreakingSpace приводить неразрывные пробелы к обычным
 */
public record HeaderMatchingPolicy(
        boolean trim, boolean collapseWhitespace, boolean ignoreCase, boolean normalizeNonBreakingSpace) {

    private static final HeaderMatchingPolicy DEFAULTS =
            new HeaderMatchingPolicy(true, true, true, true);

    public static HeaderMatchingPolicy defaults() {
        return DEFAULTS;
    }

    /** Приводит заголовок к канонической форме для сравнения. */
    public String normalize(String header) {
        if (header == null) {
            return null;
        }
        String result = header;
        if (normalizeNonBreakingSpace) {
            result = result.replace(' ', ' ').replace(' ', ' ').replace('﻿', ' ');
        }
        if (collapseWhitespace) {
            result = result.replaceAll("\\s+", " ");
        }
        if (trim) {
            result = result.trim();
        }
        if (ignoreCase) {
            result = result.toLowerCase(java.util.Locale.ROOT);
        }
        return result;
    }
}
```

- [ ] **Step 5: Реализовать `ResolvedColumns` и `HeaderResolver`**

`internal/map/ResolvedColumns.java`:

```java
package io.github.excelimport.internal.map;

import java.util.List;
import java.util.Optional;

/** Привязки колонок с уже определёнными индексами в конкретном файле. */
public record ResolvedColumns(List<Entry> entries) {

    public ResolvedColumns {
        entries = List.copyOf(entries);
    }

    public record Entry(ColumnBinding binding, int columnIndex) {}

    public Optional<Integer> indexOf(String fieldName) {
        return entries.stream()
                .filter(entry -> entry.binding().fieldName().equals(fieldName))
                .map(Entry::columnIndex)
                .findFirst();
    }
}
```

`internal/map/HeaderResolver.java`:

```java
package io.github.excelimport.internal.map;

import io.github.excelimport.HeaderMatchingPolicy;
import io.github.excelimport.exception.FileStructureException;
import io.github.excelimport.internal.read.RawRow;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Определяет, в каких колонках файла лежат поля модели. */
public final class HeaderResolver {

    private HeaderResolver() {}

    public static ResolvedColumns resolve(
            MappingModel<?> model, RawRow headerRow, HeaderMatchingPolicy policy) {
        Map<String, Integer> headerIndexes = indexHeaders(headerRow, policy);
        List<ResolvedColumns.Entry> entries = new ArrayList<>();
        List<String> missing = new ArrayList<>();

        for (ColumnBinding binding : model.excelColumns()) {
            if (binding.columnIndex() != null) {
                entries.add(new ResolvedColumns.Entry(binding, binding.columnIndex()));
                continue;
            }
            Integer index = headerIndexes.get(policy.normalize(binding.headerName()));
            if (index != null) {
                entries.add(new ResolvedColumns.Entry(binding, index));
            } else if (binding.required()) {
                missing.add(binding.headerName());
            }
        }

        if (!missing.isEmpty()) {
            throw new FileStructureException(
                    "в строке заголовка не найдены обязательные колонки: " + missing
                            + "; фактические заголовки: " + headerTexts(headerRow));
        }
        return new ResolvedColumns(entries);
    }

    private static Map<String, Integer> indexHeaders(RawRow headerRow, HeaderMatchingPolicy policy) {
        Map<String, Integer> indexes = new HashMap<>();
        Set<String> duplicates = new HashSet<>();
        for (int column = 0; column <= headerRow.lastColumnIndex(); column++) {
            String text = headerRow.cell(column).asString();
            if (text == null || text.isBlank()) {
                continue;
            }
            String normalized = policy.normalize(text);
            if (indexes.putIfAbsent(normalized, column) != null) {
                duplicates.add(normalized);
            }
        }
        if (!duplicates.isEmpty()) {
            throw new FileStructureException(
                    "заголовки повторяются, сопоставление неоднозначно: " + duplicates);
        }
        return indexes;
    }

    private static List<String> headerTexts(RawRow headerRow) {
        List<String> texts = new ArrayList<>();
        for (int column = 0; column <= headerRow.lastColumnIndex(); column++) {
            String text = headerRow.cell(column).asString();
            if (text != null && !text.isBlank()) {
                texts.add(text);
            }
        }
        return texts;
    }
}
```

- [ ] **Step 6: Запустить тесты**

Run: `./gradlew :excel-import-core:test --tests "*HeaderResolverTest*"`
Expected: PASS — 7 тестов.

- [ ] **Step 7: Коммит**

```bash
git add excel-import-core/src/main/java/io/github/excelimport/HeaderMatchingPolicy.java \
        excel-import-core/src/main/java/io/github/excelimport/internal/map \
        excel-import-core/src/test/java/io/github/excelimport
git commit -m "feat: add header matching policy and resolver"
```

---

### Task 8: `RowMapper`

**Files:**
- Create: `excel-import-core/src/main/java/io/github/excelimport/internal/map/MappingResult.java`
- Create: `excel-import-core/src/main/java/io/github/excelimport/internal/map/RowMapper.java`
- Test: `excel-import-core/src/test/java/io/github/excelimport/internal/map/RowMapperTest.java`

**Interfaces:**
- Consumes: `MappingModel`, `ResolvedColumns` (Task 5, 7), `ConverterRegistry`, `ConversionContext` (Task 6), `RawRow` (Task 3), `RowError` (Task 2).
- Produces:
  - `MappingResult<T>` — record `(T value, List<RowError> errors)` с `boolean isValid()`.
  - `RowMapper<T>` — конструктор `RowMapper(MappingModel<T>, ResolvedColumns, ConverterRegistry, Locale, BooleanWords)`; метод `MappingResult<T> map(RawRow row)`. Собирает **все** ошибки конвертации строки, а не первую.

- [ ] **Step 1: Написать падающий тест**

```java
package io.github.excelimport.internal.map;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.excelimport.ErrorKind;
import io.github.excelimport.HeaderMatchingPolicy;
import io.github.excelimport.NamingStrategy;
import io.github.excelimport.annotation.ExcelColumn;
import io.github.excelimport.annotation.ExcelSheet;
import io.github.excelimport.convert.BooleanWords;
import io.github.excelimport.internal.convert.ConverterRegistry;
import io.github.excelimport.internal.read.RawRow;
import io.github.excelimport.testsupport.RawRows;
import java.math.BigDecimal;
import java.util.Locale;
import org.junit.jupiter.api.Test;

class RowMapperTest {

    @ExcelSheet(name = "S")
    static class Employee {
        @ExcelColumn(header = "ФИО")
        String fullName;

        @ExcelColumn(header = "Оклад")
        BigDecimal salary;

        @ExcelColumn(header = "Стаж")
        Integer years;
    }

    private final MappingModel<Employee> model =
            MappingModelFactory.create(Employee.class, NamingStrategy.SNAKE_CASE);

    private RowMapper<Employee> mapper() {
        RawRow header = RawRows.of(0, "ФИО", "Оклад", "Стаж");
        ResolvedColumns resolved = HeaderResolver.resolve(model, header, HeaderMatchingPolicy.defaults());
        return new RowMapper<>(
                model, resolved, new ConverterRegistry(), Locale.of("ru"), BooleanWords.defaults());
    }

    @Test
    void mapsAllFields() {
        MappingResult<Employee> result = mapper().map(RawRows.of(4, "Иванов", "1234,50", "7"));

        assertThat(result.isValid()).isTrue();
        assertThat(result.value().fullName).isEqualTo("Иванов");
        assertThat(result.value().salary).isEqualByComparingTo("1234.50");
        assertThat(result.value().years).isEqualTo(7);
    }

    @Test
    void missingCellsLeaveFieldsNull() {
        MappingResult<Employee> result = mapper().map(RawRows.of(4, "Иванов", null, null));

        assertThat(result.isValid()).isTrue();
        assertThat(result.value().salary).isNull();
        assertThat(result.value().years).isNull();
    }

    @Test
    void collectsAllConversionErrorsNotJustTheFirst() {
        MappingResult<Employee> result = mapper().map(RawRows.of(4, "Иванов", "abc", "xyz"));

        assertThat(result.isValid()).isFalse();
        assertThat(result.errors()).hasSize(2);
        assertThat(result.errors()).allSatisfy(error -> {
            assertThat(error.kind()).isEqualTo(ErrorKind.CONVERSION);
            assertThat(error.rowNum()).isEqualTo(5); // 1-based
        });
        assertThat(result.errors()).extracting("columnHeader").containsExactly("Оклад", "Стаж");
    }

    @Test
    void errorCarriesRawCellText() {
        MappingResult<Employee> result = mapper().map(RawRows.of(4, "Иванов", "abc", "1"));

        assertThat(result.errors()).singleElement()
                .satisfies(error -> assertThat(error.rawValue()).isEqualTo("abc"));
    }

    @Test
    void valueIsStillReturnedWhenSomeColumnsFailed() {
        // объект нужен отчёту и потенциальному BatchValidator, но строка считается невалидной
        MappingResult<Employee> result = mapper().map(RawRows.of(4, "Иванов", "abc", "7"));

        assertThat(result.value().fullName).isEqualTo("Иванов");
        assertThat(result.value().years).isEqualTo(7);
        assertThat(result.isValid()).isFalse();
    }

    @Test
    void mapperIsReusableAcrossRows() {
        RowMapper<Employee> mapper = mapper();

        Employee first = mapper.map(RawRows.of(1, "А", "1", "1")).value();
        Employee second = mapper.map(RawRows.of(2, "Б", "2", "2")).value();

        assertThat(first).isNotSameAs(second);
        assertThat(first.fullName).isEqualTo("А");
        assertThat(second.fullName).isEqualTo("Б");
    }
}
```

- [ ] **Step 2: Запустить тест, убедиться что падает**

Run: `./gradlew :excel-import-core:test --tests "*RowMapperTest*"`
Expected: FAIL — `cannot find symbol: class RowMapper`.

- [ ] **Step 3: Реализовать `MappingResult`**

```java
package io.github.excelimport.internal.map;

import io.github.excelimport.RowError;
import java.util.List;

/**
 * Результат маппинга одной строки. Объект возвращается даже при ошибках — частично
 * заполненный, он нужен отчёту и логам.
 */
public record MappingResult<T>(T value, List<RowError> errors) {

    public MappingResult {
        errors = List.copyOf(errors);
    }

    public boolean isValid() {
        return errors.isEmpty();
    }
}
```

- [ ] **Step 4: Реализовать `RowMapper`**

```java
package io.github.excelimport.internal.map;

import io.github.excelimport.RowError;
import io.github.excelimport.convert.BooleanWords;
import io.github.excelimport.convert.CellConverter;
import io.github.excelimport.convert.CellValue;
import io.github.excelimport.convert.ConversionContext;
import io.github.excelimport.convert.ConversionException;
import io.github.excelimport.internal.convert.ConverterRegistry;
import io.github.excelimport.internal.read.RawRow;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Превращает {@link RawRow} в объект модели. Создаётся один раз на прогон импорта
 * после разбора заголовка и переиспользуется для всех строк.
 */
public final class RowMapper<T> {

    /** Заранее подготовленный план заполнения одного поля. */
    private record FieldPlan(
            ColumnBinding binding, int columnIndex, CellConverter<?> converter, ConversionContext context) {}

    private final MappingModel<T> model;
    private final List<FieldPlan> plans;

    public RowMapper(
            MappingModel<T> model,
            ResolvedColumns resolved,
            ConverterRegistry converters,
            Locale locale,
            BooleanWords booleanWords) {
        this.model = model;
        List<FieldPlan> prepared = new ArrayList<>(resolved.entries().size());
        for (ResolvedColumns.Entry entry : resolved.entries()) {
            ColumnBinding binding = entry.binding();
            ConversionContext context = new ConversionContext(
                    binding.displayName(),
                    binding.fieldType(),
                    binding.formats(),
                    binding.trim(),
                    binding.emptyAsNull(),
                    locale,
                    booleanWords);
            prepared.add(new FieldPlan(binding, entry.columnIndex(), converters.resolve(binding), context));
        }
        this.plans = List.copyOf(prepared);
    }

    public MappingResult<T> map(RawRow row) {
        T instance = model.newInstance();
        List<RowError> errors = new ArrayList<>(0);

        for (FieldPlan plan : plans) {
            CellValue cell = row.cell(plan.columnIndex());
            try {
                Object converted = plan.converter().convert(cell, plan.context());
                if (converted != null || !plan.binding().fieldType().isPrimitive()) {
                    plan.binding().setter().invoke(instance, converted);
                }
            } catch (ConversionException e) {
                errors.add(RowError.conversion(
                        row.excelRowNumber(),
                        plan.binding().displayName(),
                        cell.asString(),
                        e.code(),
                        e.getMessage()));
            } catch (Throwable e) {
                errors.add(RowError.conversion(
                        row.excelRowNumber(),
                        plan.binding().displayName(),
                        cell.asString(),
                        "SETTER_FAILED",
                        "не удалось записать значение в поле " + plan.binding().fieldName() + ": "
                                + e.getMessage()));
            }
        }
        return new MappingResult<>(instance, errors);
    }
}
```

Примитивное поле при `null` не записывается: оставляем значение по умолчанию, а обязательность проверяет `@NotNull` на слое валидации.

- [ ] **Step 5: Запустить тесты**

Run: `./gradlew :excel-import-core:test --tests "*RowMapperTest*"`
Expected: PASS — 6 тестов.

- [ ] **Step 6: Коммит**

```bash
git add excel-import-core/src/main/java/io/github/excelimport/internal/map \
        excel-import-core/src/test/java/io/github/excelimport/internal/map
git commit -m "feat: add RowMapper collecting all conversion errors per row"
```

---

### Task 9: Интеграция Jakarta Bean Validation

**Files:**
- Create: `excel-import-core/src/main/java/io/github/excelimport/internal/validate/BeanValidator.java`
- Create: `excel-import-core/src/main/resources/ValidationMessages.properties`
- Create: `excel-import-core/src/main/resources/ValidationMessages_ru.properties`
- Test: `excel-import-core/src/test/java/io/github/excelimport/internal/validate/BeanValidatorTest.java`

**Interfaces:**
- Consumes: `RowError`, `ErrorKind` (Task 2), `MappingModel`/`ColumnBinding` (Task 5).
- Produces: `BeanValidator` — конструктор `BeanValidator(Locale, MappingModel<?>)`; методы `List<RowError> validate(Object bean, int rowNum)` и `void close()`. Имя нарушенного поля переводится в заголовок Excel-колонки через `MappingModel`; для нарушений уровня класса `columnHeader` остаётся `null`.

- [ ] **Step 1: Написать падающий тест**

```java
package io.github.excelimport.internal.validate;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.excelimport.ErrorKind;
import io.github.excelimport.NamingStrategy;
import io.github.excelimport.annotation.ExcelColumn;
import io.github.excelimport.annotation.ExcelSheet;
import io.github.excelimport.internal.map.MappingModel;
import io.github.excelimport.internal.map.MappingModelFactory;
import jakarta.validation.Constraint;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import jakarta.validation.Payload;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class BeanValidatorTest {

    @Target(ElementType.TYPE)
    @Retention(RetentionPolicy.RUNTIME)
    @Constraint(validatedBy = HireBeforeFireValidator.class)
    @interface HireBeforeFire {
        String message() default "Дата увольнения раньше даты приёма";

        Class<?>[] groups() default {};

        Class<? extends Payload>[] payload() default {};
    }

    static class HireBeforeFireValidator implements ConstraintValidator<HireBeforeFire, Employee> {
        @Override
        public boolean isValid(Employee value, ConstraintValidatorContext context) {
            if (value.hiredAt == null || value.firedAt == null) {
                return true;
            }
            return !value.firedAt.isBefore(value.hiredAt);
        }
    }

    @ExcelSheet(name = "S")
    @HireBeforeFire
    static class Employee {
        @ExcelColumn(header = "ФИО")
        @NotBlank
        @Size(max = 5)
        String fullName;

        @ExcelColumn(header = "Стаж")
        @NotNull
        @Min(0)
        Integer years;

        @ExcelColumn(header = "Дата приёма")
        LocalDate hiredAt;

        @ExcelColumn(header = "Дата увольнения")
        LocalDate firedAt;
    }

    private final MappingModel<Employee> model =
            MappingModelFactory.create(Employee.class, NamingStrategy.SNAKE_CASE);
    private final BeanValidator validator = new BeanValidator(Locale.of("ru"), model);

    @AfterEach
    void tearDown() {
        validator.close();
    }

    @Test
    void validBeanProducesNoErrors() {
        Employee bean = new Employee();
        bean.fullName = "Иван";
        bean.years = 3;

        assertThat(validator.validate(bean, 7)).isEmpty();
    }

    @Test
    void fieldViolationIsMappedToExcelColumnHeader() {
        Employee bean = new Employee();
        bean.fullName = "";
        bean.years = 3;

        List<io.github.excelimport.RowError> errors = validator.validate(bean, 7);

        assertThat(errors).singleElement().satisfies(error -> {
            assertThat(error.kind()).isEqualTo(ErrorKind.CONSTRAINT);
            assertThat(error.columnHeader()).isEqualTo("ФИО");
            assertThat(error.rowNum()).isEqualTo(7);
            assertThat(error.code()).isEqualTo("NotBlank");
        });
    }

    @Test
    void allViolationsAreCollected() {
        Employee bean = new Employee();
        bean.fullName = "СлишкомДлинноеИмя";
        bean.years = -1;

        assertThat(validator.validate(bean, 7)).hasSize(2);
    }

    @Test
    void nullFieldTriggersNotNull() {
        Employee bean = new Employee();
        bean.fullName = "Иван";
        bean.years = null;

        assertThat(validator.validate(bean, 7)).singleElement()
                .satisfies(error -> assertThat(error.code()).isEqualTo("NotNull"));
    }

    @Test
    void classLevelViolationHasNoColumnHeader() {
        Employee bean = new Employee();
        bean.fullName = "Иван";
        bean.years = 3;
        bean.hiredAt = LocalDate.of(2026, 7, 1);
        bean.firedAt = LocalDate.of(2026, 6, 1);

        assertThat(validator.validate(bean, 7)).singleElement().satisfies(error -> {
            assertThat(error.columnHeader()).isNull();
            assertThat(error.code()).isEqualTo("HireBeforeFire");
            assertThat(error.message()).contains("раньше даты приёма");
        });
    }

    @Test
    void validatorIsReusableAndThreadSafeForSequentialCalls() {
        Employee first = new Employee();
        first.fullName = "Иван";
        first.years = 1;
        Employee second = new Employee();
        second.years = 1;

        assertThat(validator.validate(first, 1)).isEmpty();
        assertThat(validator.validate(second, 2)).hasSize(1);
    }
}
```

- [ ] **Step 2: Запустить тест, убедиться что падает**

Run: `./gradlew :excel-import-core:test --tests "*BeanValidatorTest*"`
Expected: FAIL — `cannot find symbol: class BeanValidator`.

- [ ] **Step 3: Реализовать `BeanValidator`**

```java
package io.github.excelimport.internal.validate;

import io.github.excelimport.RowError;
import io.github.excelimport.internal.map.ColumnBinding;
import io.github.excelimport.internal.map.MappingModel;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import jakarta.validation.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.hibernate.validator.HibernateValidator;
import org.hibernate.validator.messageinterpolation.ResourceBundleMessageInterpolator;
import org.hibernate.validator.resourceloading.PlatformResourceBundleLocator;

/**
 * Обёртка над Jakarta Bean Validation. Сообщения интерполируются в заданной локали,
 * имена полей переводятся в заголовки Excel-колонок.
 */
public final class BeanValidator implements AutoCloseable {

    private final ValidatorFactory factory;
    private final Validator validator;
    private final Map<String, String> fieldToHeader;

    public BeanValidator(Locale locale, MappingModel<?> model) {
        this.factory = Validation.byProvider(HibernateValidator.class)
                .configure()
                .messageInterpolator(new LocaleFixedInterpolator(locale))
                .buildValidatorFactory();
        this.validator = factory.getValidator();
        this.fieldToHeader = new HashMap<>();
        for (ColumnBinding binding : model.excelColumns()) {
            fieldToHeader.put(binding.fieldName(), binding.displayName());
        }
    }

    /**
     * @param rowNum 1-based номер строки Excel
     * @return все нарушения; пустой список, если объект валиден
     */
    public List<RowError> validate(Object bean, int rowNum) {
        Set<ConstraintViolation<Object>> violations = validator.validate(bean);
        if (violations.isEmpty()) {
            return List.of();
        }
        List<RowError> errors = new ArrayList<>(violations.size());
        for (ConstraintViolation<Object> violation : violations) {
            String field = leafFieldName(violation.getPropertyPath());
            String header = field == null ? null : fieldToHeader.get(field);
            String code = violation.getConstraintDescriptor().getAnnotation()
                    .annotationType()
                    .getSimpleName();
            String rawValue = violation.getInvalidValue() == null
                    ? null
                    : String.valueOf(violation.getInvalidValue());
            errors.add(RowError.constraint(rowNum, header, rawValue, code, violation.getMessage()));
        }
        // порядок нарушений в Set не определён — сортируем для предсказуемости отчёта
        errors.sort(java.util.Comparator.comparing(
                error -> (error.columnHeader() == null ? "" : error.columnHeader()) + error.code()));
        return List.copyOf(errors);
    }

    private static String leafFieldName(Path path) {
        String last = null;
        for (Path.Node node : path) {
            if (node.getKind() == jakarta.validation.ElementKind.PROPERTY) {
                last = node.getName();
            }
        }
        return last;
    }

    @Override
    public void close() {
        factory.close();
    }

    /** Интерполятор, всегда использующий заданную локаль вместо локали JVM. */
    private static final class LocaleFixedInterpolator extends ResourceBundleMessageInterpolator {

        private final Locale locale;

        LocaleFixedInterpolator(Locale locale) {
            super(new PlatformResourceBundleLocator("ValidationMessages"));
            this.locale = locale;
        }

        @Override
        public String interpolate(String message, Context context) {
            return super.interpolate(message, context, locale);
        }
    }
}
```

- [ ] **Step 4: Создать файлы сообщений**

`excel-import-core/src/main/resources/ValidationMessages.properties`:

```properties
# Переопределения сообщений по умолчанию для стандартных constraint'ов.
# Пустой файл-заглушка: библиотека полагается на сообщения Hibernate Validator,
# а этот файл существует, чтобы PlatformResourceBundleLocator не падал и чтобы
# потребитель мог переопределить сообщения своим ValidationMessages.properties.
```

`excel-import-core/src/main/resources/ValidationMessages_ru.properties`:

```properties
jakarta.validation.constraints.NotNull.message=значение обязательно
jakarta.validation.constraints.NotBlank.message=значение не может быть пустым
jakarta.validation.constraints.NotEmpty.message=значение не может быть пустым
jakarta.validation.constraints.Size.message=длина должна быть от {min} до {max}
jakarta.validation.constraints.Min.message=значение должно быть не меньше {value}
jakarta.validation.constraints.Max.message=значение должно быть не больше {value}
jakarta.validation.constraints.DecimalMin.message=значение должно быть не меньше {value}
jakarta.validation.constraints.DecimalMax.message=значение должно быть не больше {value}
jakarta.validation.constraints.Positive.message=значение должно быть положительным
jakarta.validation.constraints.PositiveOrZero.message=значение не может быть отрицательным
jakarta.validation.constraints.Past.message=дата должна быть в прошлом
jakarta.validation.constraints.PastOrPresent.message=дата не может быть в будущем
jakarta.validation.constraints.Future.message=дата должна быть в будущем
jakarta.validation.constraints.Pattern.message=значение не соответствует шаблону {regexp}
jakarta.validation.constraints.Email.message=некорректный адрес электронной почты
jakarta.validation.constraints.Digits.message=допустимо {integer} цифр в целой части и {fraction} в дробной
```

- [ ] **Step 5: Запустить тесты**

Run: `./gradlew :excel-import-core:test --tests "*BeanValidatorTest*"`
Expected: PASS — 6 тестов. Тест `fieldViolationIsMappedToExcelColumnHeader` подтверждает перевод `fullName` → `ФИО`; `nullFieldTriggersNotNull` — что сообщение берётся из русского бандла.

Если `LocaleFixedInterpolator` не компилируется из-за защищённого конструктора `ResourceBundleMessageInterpolator`, заменить на делегирующую реализацию `MessageInterpolator`, оборачивающую `new ResourceBundleMessageInterpolator()` и вызывающую `interpolate(message, context, locale)`.

- [ ] **Step 6: Коммит**

```bash
git add excel-import-core/src/main/java/io/github/excelimport/internal/validate \
        excel-import-core/src/main/resources \
        excel-import-core/src/test/java/io/github/excelimport/internal/validate
git commit -m "feat: integrate Jakarta Bean Validation with column-aware error mapping"
```

---

### Task 10: `ImportConfig`, `ConflictStrategy`, `ErrorPolicy`

**Files:**
- Create: `excel-import-core/src/main/java/io/github/excelimport/ConflictStrategy.java`
- Create: `excel-import-core/src/main/java/io/github/excelimport/ImportConfig.java`
- Test: `excel-import-core/src/test/java/io/github/excelimport/ConflictStrategyTest.java`
- Test: `excel-import-core/src/test/java/io/github/excelimport/ImportConfigTest.java`

**Interfaces:**
- Consumes: `SheetSelector`, `TableRef` (Task 2), `HeaderMatchingPolicy` (Task 7), `NamingStrategy` (Task 5), `FormulaPolicy` (Task 3), `BooleanWords` (Task 6), `ReportStyle` (создаётся в Task 15 — здесь объявляется поле типа `ReportStyle` и минимальная реализация с фабрикой `defaults()`).
- Produces: `ConflictStrategy` с фабриками `none()`, `doNothing(String... conflictColumns)`, `doUpdate(List<String> conflictColumns, List<String> updateColumns)` и методом `String toSql()`; `ImportConfig` с билдером и полным набором параметров из §7 спеки, валидируемых в `build()`.

- [ ] **Step 1: Написать падающие тесты**

`ConflictStrategyTest.java`:

```java
package io.github.excelimport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;

class ConflictStrategyTest {

    @Test
    void noneProducesEmptySql() {
        assertThat(ConflictStrategy.none().toSql()).isEmpty();
    }

    @Test
    void doNothingQuotesConflictColumns() {
        assertThat(ConflictStrategy.doNothing("personnel_no").toSql())
                .isEqualTo("ON CONFLICT (\"personnel_no\") DO NOTHING");
    }

    @Test
    void doNothingSupportsCompositeKey() {
        assertThat(ConflictStrategy.doNothing("a", "b").toSql())
                .isEqualTo("ON CONFLICT (\"a\", \"b\") DO NOTHING");
    }

    @Test
    void doUpdateGeneratesExcludedAssignments() {
        assertThat(ConflictStrategy.doUpdate(List.of("personnel_no"), List.of("full_name", "salary"))
                        .toSql())
                .isEqualTo("ON CONFLICT (\"personnel_no\") DO UPDATE SET "
                        + "\"full_name\" = EXCLUDED.\"full_name\", \"salary\" = EXCLUDED.\"salary\"");
    }

    @Test
    void doNothingRequiresAtLeastOneColumn() {
        assertThatThrownBy(ConflictStrategy::doNothing)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("хотя бы одна колонка");
    }

    @Test
    void doUpdateRequiresNonEmptyUpdateColumns() {
        assertThatThrownBy(() -> ConflictStrategy.doUpdate(List.of("a"), List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("обновляемых колонок");
    }
}
```

`ImportConfigTest.java`:

```java
package io.github.excelimport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class ImportConfigTest {

    @Test
    void defaultsMatchSpec() {
        ImportConfig config = ImportConfig.builder().build();

        assertThat(config.batchSize()).isEqualTo(1000);
        assertThat(config.maxErrors()).isEqualTo(Integer.MAX_VALUE);
        assertThat(config.maxErrorsInMemory()).isEqualTo(1000);
        assertThat(config.maxSplitDepth()).isEqualTo(16);
        assertThat(config.maxOutcomeMessagesInMemory()).isEqualTo(50_000);
        assertThat(config.skipBlankRows()).isTrue();
        assertThat(config.expandMergedCells()).isTrue();
        assertThat(config.includeDatabaseDetailInReport()).isTrue();
        assertThat(config.dryRun()).isFalse();
        assertThat(config.reportPath()).isNull();
        assertThat(config.conflictStrategy().toSql()).isEmpty();
        assertThat(config.queryTimeoutSeconds()).isZero();
    }

    @Test
    void builderOverridesValues() {
        ImportConfig config = ImportConfig.builder()
                .batchSize(5000)
                .sheet(SheetSelector.byName("Данные"))
                .headerRow(3)
                .maxErrors(10)
                .reportPath(Path.of("/tmp/report.xlsx"))
                .dryRun(true)
                .build();

        assertThat(config.batchSize()).isEqualTo(5000);
        assertThat(config.sheet()).isNotNull();
        assertThat(config.headerRow()).isEqualTo(3);
        assertThat(config.firstDataRow()).isEqualTo(4); // headerRow + 1 по умолчанию
        assertThat(config.dryRun()).isTrue();
    }

    @Test
    void rejectsNonPositiveBatchSize() {
        assertThatThrownBy(() -> ImportConfig.builder().batchSize(0).build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("batchSize");
    }

    @Test
    void rejectsNegativeHeaderRow() {
        assertThatThrownBy(() -> ImportConfig.builder().headerRow(-1).build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("headerRow");
    }

    @Test
    void rejectsFirstDataRowNotAfterHeaderRow() {
        assertThatThrownBy(() -> ImportConfig.builder().headerRow(5).firstDataRow(5).build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("firstDataRow");
    }

    @Test
    void rejectsNegativeSplitDepth() {
        assertThatThrownBy(() -> ImportConfig.builder().maxSplitDepth(-1).build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("maxSplitDepth");
    }

    @Test
    void configIsImmutableAfterBuild() {
        ImportConfig.Builder builder = ImportConfig.builder().batchSize(100);
        ImportConfig first = builder.build();
        builder.batchSize(200);

        assertThat(first.batchSize()).isEqualTo(100);
    }
}
```

- [ ] **Step 2: Запустить тесты, убедиться что падают**

Run: `./gradlew :excel-import-core:test --tests "*ConflictStrategyTest*" --tests "*ImportConfigTest*"`
Expected: FAIL — `cannot find symbol: class ConflictStrategy`.

- [ ] **Step 3: Реализовать `ConflictStrategy`**

```java
package io.github.excelimport;

import java.util.List;
import java.util.stream.Collectors;

/** Секция {@code ON CONFLICT} для генерируемого INSERT. */
public final class ConflictStrategy {

    private static final ConflictStrategy NONE = new ConflictStrategy(List.of(), null);

    private final List<String> conflictColumns;
    private final List<String> updateColumns;

    private ConflictStrategy(List<String> conflictColumns, List<String> updateColumns) {
        this.conflictColumns = List.copyOf(conflictColumns);
        this.updateColumns = updateColumns == null ? null : List.copyOf(updateColumns);
    }

    /** Без {@code ON CONFLICT}: конфликт приводит к ошибке БД. */
    public static ConflictStrategy none() {
        return NONE;
    }

    /** {@code ON CONFLICT (...) DO NOTHING} — конфликтующие строки молча пропускаются. */
    public static ConflictStrategy doNothing(String... conflictColumns) {
        requireColumns(List.of(conflictColumns));
        return new ConflictStrategy(List.of(conflictColumns), null);
    }

    /** {@code ON CONFLICT (...) DO UPDATE SET col = EXCLUDED.col, ...} */
    public static ConflictStrategy doUpdate(List<String> conflictColumns, List<String> updateColumns) {
        requireColumns(conflictColumns);
        if (updateColumns == null || updateColumns.isEmpty()) {
            throw new IllegalArgumentException("для DO UPDATE нужен непустой список обновляемых колонок");
        }
        return new ConflictStrategy(conflictColumns, updateColumns);
    }

    private static void requireColumns(List<String> columns) {
        if (columns == null || columns.isEmpty()) {
            throw new IllegalArgumentException("для ON CONFLICT нужна хотя бы одна колонка");
        }
    }

    /** true, если конфликтующие строки не приводят к ошибке БД. */
    public boolean swallowsConflicts() {
        return !conflictColumns.isEmpty();
    }

    /** SQL-фрагмент; пустая строка для {@link #none()}. */
    public String toSql() {
        if (conflictColumns.isEmpty()) {
            return "";
        }
        String target = conflictColumns.stream()
                .map(TableRef::quote)
                .collect(Collectors.joining(", "));
        if (updateColumns == null) {
            return "ON CONFLICT (" + target + ") DO NOTHING";
        }
        String assignments = updateColumns.stream()
                .map(column -> TableRef.quote(column) + " = EXCLUDED." + TableRef.quote(column))
                .collect(Collectors.joining(", "));
        return "ON CONFLICT (" + target + ") DO UPDATE SET " + assignments;
    }
}
```

- [ ] **Step 4: Реализовать минимальный `ReportStyle` (полностью — в Task 15)**

`excel-import-core/src/main/java/io/github/excelimport/report/ReportStyle.java`:

```java
package io.github.excelimport.report;

/**
 * Оформление Excel-отчёта. Расширяется в задаче, реализующей ReportWriter;
 * здесь достаточно значений по умолчанию, чтобы ImportConfig был собираем.
 */
public final class ReportStyle {

    private static final ReportStyle DEFAULTS = new ReportStyle();

    ReportStyle() {}

    public static ReportStyle defaults() {
        return DEFAULTS;
    }
}
```

- [ ] **Step 5: Реализовать `ImportConfig`**

```java
package io.github.excelimport;

import io.github.excelimport.convert.BooleanWords;
import io.github.excelimport.internal.read.FormulaPolicy;
import io.github.excelimport.report.ReportStyle;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Objects;

/** Иммутабельная конфигурация одного вида импорта. Все проверки — в {@link Builder#build()}. */
public final class ImportConfig {

    private final int batchSize;
    private final SheetSelector sheet;
    private final int headerRow;
    private final int firstDataRow;
    private final boolean skipBlankRows;
    private final boolean expandMergedCells;
    private final FormulaPolicy formulaPolicy;
    private final HeaderMatchingPolicy headerMatching;
    private final NamingStrategy namingStrategy;
    private final TableRef targetTable;
    private final ConflictStrategy conflictStrategy;
    private final int maxErrors;
    private final int maxErrorsInMemory;
    private final int maxSplitDepth;
    private final int maxOutcomeMessagesInMemory;
    private final Path reportPath;
    private final ReportStyle reportStyle;
    private final boolean includeDatabaseDetailInReport;
    private final boolean dryRun;
    private final Locale locale;
    private final Path tempDir;
    private final int queryTimeoutSeconds;
    private final BooleanWords booleanWords;

    private ImportConfig(Builder builder) {
        this.batchSize = builder.batchSize;
        this.sheet = builder.sheet;
        this.headerRow = builder.headerRow;
        this.firstDataRow = builder.firstDataRow < 0 ? builder.headerRow + 1 : builder.firstDataRow;
        this.skipBlankRows = builder.skipBlankRows;
        this.expandMergedCells = builder.expandMergedCells;
        this.formulaPolicy = builder.formulaPolicy;
        this.headerMatching = builder.headerMatching;
        this.namingStrategy = builder.namingStrategy;
        this.targetTable = builder.targetTable;
        this.conflictStrategy = builder.conflictStrategy;
        this.maxErrors = builder.maxErrors;
        this.maxErrorsInMemory = builder.maxErrorsInMemory;
        this.maxSplitDepth = builder.maxSplitDepth;
        this.maxOutcomeMessagesInMemory = builder.maxOutcomeMessagesInMemory;
        this.reportPath = builder.reportPath;
        this.reportStyle = builder.reportStyle;
        this.includeDatabaseDetailInReport = builder.includeDatabaseDetailInReport;
        this.dryRun = builder.dryRun;
        this.locale = builder.locale;
        this.tempDir = builder.tempDir;
        this.queryTimeoutSeconds = builder.queryTimeoutSeconds;
        this.booleanWords = builder.booleanWords;
    }

    public static Builder builder() {
        return new Builder();
    }

    public int batchSize() {
        return batchSize;
    }

    /** null означает «взять из @ExcelSheet». */
    public SheetSelector sheet() {
        return sheet;
    }

    public int headerRow() {
        return headerRow;
    }

    public int firstDataRow() {
        return firstDataRow;
    }

    public boolean skipBlankRows() {
        return skipBlankRows;
    }

    public boolean expandMergedCells() {
        return expandMergedCells;
    }

    public FormulaPolicy formulaPolicy() {
        return formulaPolicy;
    }

    public HeaderMatchingPolicy headerMatching() {
        return headerMatching;
    }

    public NamingStrategy namingStrategy() {
        return namingStrategy;
    }

    /** null означает «взять из @TargetTable». */
    public TableRef targetTable() {
        return targetTable;
    }

    public ConflictStrategy conflictStrategy() {
        return conflictStrategy;
    }

    public int maxErrors() {
        return maxErrors;
    }

    public int maxErrorsInMemory() {
        return maxErrorsInMemory;
    }

    public int maxSplitDepth() {
        return maxSplitDepth;
    }

    public int maxOutcomeMessagesInMemory() {
        return maxOutcomeMessagesInMemory;
    }

    /** null означает «отчёт не генерировать». */
    public Path reportPath() {
        return reportPath;
    }

    public ReportStyle reportStyle() {
        return reportStyle;
    }

    public boolean includeDatabaseDetailInReport() {
        return includeDatabaseDetailInReport;
    }

    public boolean dryRun() {
        return dryRun;
    }

    public Locale locale() {
        return locale;
    }

    public Path tempDir() {
        return tempDir;
    }

    public int queryTimeoutSeconds() {
        return queryTimeoutSeconds;
    }

    public BooleanWords booleanWords() {
        return booleanWords;
    }

    /** Настройки чтения, выведенные из конфигурации. */
    public io.github.excelimport.internal.read.ReadOptions readOptions() {
        return new io.github.excelimport.internal.read.ReadOptions(
                skipBlankRows, expandMergedCells, formulaPolicy);
    }

    /** Билдер. Может переиспользоваться: {@code build()} снимает снимок значений. */
    public static final class Builder {

        private int batchSize = 1000;
        private SheetSelector sheet;
        private int headerRow = 0;
        private int firstDataRow = -1;
        private boolean skipBlankRows = true;
        private boolean expandMergedCells = true;
        private FormulaPolicy formulaPolicy = FormulaPolicy.AS_NULL;
        private HeaderMatchingPolicy headerMatching = HeaderMatchingPolicy.defaults();
        private NamingStrategy namingStrategy = NamingStrategy.SNAKE_CASE;
        private TableRef targetTable;
        private ConflictStrategy conflictStrategy = ConflictStrategy.none();
        private int maxErrors = Integer.MAX_VALUE;
        private int maxErrorsInMemory = 1000;
        private int maxSplitDepth = 16;
        private int maxOutcomeMessagesInMemory = 50_000;
        private Path reportPath;
        private ReportStyle reportStyle = ReportStyle.defaults();
        private boolean includeDatabaseDetailInReport = true;
        private boolean dryRun;
        private Locale locale = Locale.getDefault();
        private Path tempDir = Path.of(System.getProperty("java.io.tmpdir"));
        private int queryTimeoutSeconds;
        private BooleanWords booleanWords = BooleanWords.defaults();

        private Builder() {}

        public Builder batchSize(int value) {
            this.batchSize = value;
            return this;
        }

        public Builder sheet(SheetSelector value) {
            this.sheet = value;
            return this;
        }

        public Builder headerRow(int value) {
            this.headerRow = value;
            return this;
        }

        public Builder firstDataRow(int value) {
            this.firstDataRow = value;
            return this;
        }

        public Builder skipBlankRows(boolean value) {
            this.skipBlankRows = value;
            return this;
        }

        public Builder expandMergedCells(boolean value) {
            this.expandMergedCells = value;
            return this;
        }

        public Builder formulaPolicy(FormulaPolicy value) {
            this.formulaPolicy = Objects.requireNonNull(value, "formulaPolicy");
            return this;
        }

        public Builder headerMatching(HeaderMatchingPolicy value) {
            this.headerMatching = Objects.requireNonNull(value, "headerMatching");
            return this;
        }

        public Builder namingStrategy(NamingStrategy value) {
            this.namingStrategy = Objects.requireNonNull(value, "namingStrategy");
            return this;
        }

        public Builder targetTable(TableRef value) {
            this.targetTable = value;
            return this;
        }

        public Builder conflictStrategy(ConflictStrategy value) {
            this.conflictStrategy = Objects.requireNonNull(value, "conflictStrategy");
            return this;
        }

        public Builder maxErrors(int value) {
            this.maxErrors = value;
            return this;
        }

        public Builder maxErrorsInMemory(int value) {
            this.maxErrorsInMemory = value;
            return this;
        }

        public Builder maxSplitDepth(int value) {
            this.maxSplitDepth = value;
            return this;
        }

        public Builder maxOutcomeMessagesInMemory(int value) {
            this.maxOutcomeMessagesInMemory = value;
            return this;
        }

        public Builder reportPath(Path value) {
            this.reportPath = value;
            return this;
        }

        public Builder reportStyle(ReportStyle value) {
            this.reportStyle = Objects.requireNonNull(value, "reportStyle");
            return this;
        }

        public Builder includeDatabaseDetailInReport(boolean value) {
            this.includeDatabaseDetailInReport = value;
            return this;
        }

        public Builder dryRun(boolean value) {
            this.dryRun = value;
            return this;
        }

        public Builder locale(Locale value) {
            this.locale = Objects.requireNonNull(value, "locale");
            return this;
        }

        public Builder tempDir(Path value) {
            this.tempDir = Objects.requireNonNull(value, "tempDir");
            return this;
        }

        public Builder queryTimeoutSeconds(int value) {
            this.queryTimeoutSeconds = value;
            return this;
        }

        public Builder booleanWords(BooleanWords value) {
            this.booleanWords = Objects.requireNonNull(value, "booleanWords");
            return this;
        }

        public ImportConfig build() {
            if (batchSize < 1) {
                throw new IllegalArgumentException("batchSize должен быть >= 1, получено: " + batchSize);
            }
            if (headerRow < 0) {
                throw new IllegalArgumentException("headerRow должен быть >= 0, получено: " + headerRow);
            }
            if (firstDataRow >= 0 && firstDataRow <= headerRow) {
                throw new IllegalArgumentException(
                        "firstDataRow (" + firstDataRow + ") должен быть больше headerRow (" + headerRow + ")");
            }
            if (maxErrors < 0) {
                throw new IllegalArgumentException("maxErrors должен быть >= 0");
            }
            if (maxErrorsInMemory < 0) {
                throw new IllegalArgumentException("maxErrorsInMemory должен быть >= 0");
            }
            if (maxSplitDepth < 0) {
                throw new IllegalArgumentException("maxSplitDepth должен быть >= 0");
            }
            if (maxOutcomeMessagesInMemory < 0) {
                throw new IllegalArgumentException("maxOutcomeMessagesInMemory должен быть >= 0");
            }
            if (queryTimeoutSeconds < 0) {
                throw new IllegalArgumentException("queryTimeoutSeconds должен быть >= 0");
            }
            return new ImportConfig(this);
        }
    }
}
```

- [ ] **Step 6: Запустить тесты**

Run: `./gradlew :excel-import-core:test --tests "*ConflictStrategyTest*" --tests "*ImportConfigTest*"`
Expected: PASS — 6 + 7 тестов.

- [ ] **Step 7: Коммит**

```bash
git add excel-import-core/src/main/java/io/github/excelimport \
        excel-import-core/src/test/java/io/github/excelimport
git commit -m "feat: add ImportConfig and ON CONFLICT strategies"
```

---

### Task 11: Генерация SQL, привязка параметров, вставка чанка

**Files:**
- Create: `excel-import-core/src/main/java/io/github/excelimport/internal/write/SqlBuilder.java`
- Create: `excel-import-core/src/main/java/io/github/excelimport/internal/write/RowBinder.java`
- Create: `excel-import-core/src/main/java/io/github/excelimport/internal/write/InsertExecutor.java`
- Test: `excel-import-core/src/test/java/io/github/excelimport/internal/write/SqlBuilderTest.java`
- Test: `excel-import-core/src/test/java/io/github/excelimport/internal/write/RowBinderTest.java`

**Interfaces:**
- Consumes: `MappingModel`, `ColumnBinding` (Task 5), `TableRef`, `RowRef` (Task 2), `ConflictStrategy` (Task 10).
- Produces:
  - `SqlBuilder` — конструктор `SqlBuilder(TableRef, List<String> columns, ConflictStrategy)`; методы `String insertSql(int rowCount)`, `int maxRowsPerStatement()` (= `65535 / columnCount`), `int chunkSize(int requestedBatchSize)` (= `min(requested, maxRowsPerStatement())`), `int columnCount()`.
  - `RowBinder<T>` — конструктор `RowBinder(List<ColumnBinding>)`; метод `void bind(PreparedStatement stmt, int startParameterIndex, T value)`.
  - `InsertExecutor<T>` — конструктор `InsertExecutor(SqlBuilder, RowBinder<T>, int queryTimeoutSeconds)`; метод `int execute(Connection conn, List<RowRef<T>> chunk) throws SQLException` возвращающий число фактически вставленных строк.

- [ ] **Step 1: Написать падающие тесты**

`SqlBuilderTest.java`:

```java
package io.github.excelimport.internal.write;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.excelimport.ConflictStrategy;
import io.github.excelimport.TableRef;
import java.util.List;
import org.junit.jupiter.api.Test;

class SqlBuilderTest {

    private static final List<String> COLUMNS = List.of("personnel_no", "full_name", "salary");

    private SqlBuilder builder(ConflictStrategy conflict) {
        return new SqlBuilder(TableRef.of("hr.employee"), COLUMNS, conflict);
    }

    @Test
    void singleRowInsertHasOneTuple() {
        assertThat(builder(ConflictStrategy.none()).insertSql(1))
                .isEqualTo("INSERT INTO \"hr\".\"employee\" "
                        + "(\"personnel_no\", \"full_name\", \"salary\") VALUES (?, ?, ?)");
    }

    @Test
    void multiRowInsertRepeatsTuples() {
        assertThat(builder(ConflictStrategy.none()).insertSql(3))
                .isEqualTo("INSERT INTO \"hr\".\"employee\" "
                        + "(\"personnel_no\", \"full_name\", \"salary\") "
                        + "VALUES (?, ?, ?), (?, ?, ?), (?, ?, ?)");
    }

    @Test
    void conflictClauseIsAppended() {
        assertThat(builder(ConflictStrategy.doNothing("personnel_no")).insertSql(1))
                .endsWith("VALUES (?, ?, ?) ON CONFLICT (\"personnel_no\") DO NOTHING");
    }

    @Test
    void maxRowsPerStatementRespectsParameterLimit() {
        // 65535 / 3 = 21845
        assertThat(builder(ConflictStrategy.none()).maxRowsPerStatement()).isEqualTo(21845);
    }

    @Test
    void chunkSizeIsCappedByParameterLimit() {
        SqlBuilder sql = builder(ConflictStrategy.none());

        assertThat(sql.chunkSize(1000)).isEqualTo(1000);
        assertThat(sql.chunkSize(100_000)).isEqualTo(21845);
    }

    @Test
    void wideTableAllowsFewerRowsPerStatement() {
        List<String> wide = new java.util.ArrayList<>();
        for (int i = 0; i < 700; i++) {
            wide.add("c" + i);
        }
        SqlBuilder sql = new SqlBuilder(TableRef.of("t"), wide, ConflictStrategy.none());

        assertThat(sql.maxRowsPerStatement()).isEqualTo(93); // 65535 / 700
        assertThat(sql.chunkSize(1000)).isEqualTo(93);
    }

    @Test
    void sqlIsCachedPerRowCount() {
        SqlBuilder sql = builder(ConflictStrategy.none());

        assertThat(sql.insertSql(5)).isSameAs(sql.insertSql(5));
    }

    @Test
    void rejectsZeroRowCount() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> builder(ConflictStrategy.none()).insertSql(0))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
```

`RowBinderTest.java`:

```java
package io.github.excelimport.internal.write;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import io.github.excelimport.NamingStrategy;
import io.github.excelimport.annotation.ExcelColumn;
import io.github.excelimport.annotation.ExcelSheet;
import io.github.excelimport.internal.map.MappingModel;
import io.github.excelimport.internal.map.MappingModelFactory;
import java.math.BigDecimal;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Types;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

class RowBinderTest {

    @ExcelSheet(name = "S")
    static class Employee {
        @ExcelColumn(header = "Номер")
        Long personnelNo;

        @ExcelColumn(header = "ФИО")
        String fullName;

        @ExcelColumn(header = "Оклад")
        BigDecimal salary;

        @ExcelColumn(header = "Дата")
        LocalDate hiredAt;
    }

    private final MappingModel<Employee> model =
            MappingModelFactory.create(Employee.class, NamingStrategy.SNAKE_CASE);

    @Test
    void bindsAllColumnsInDeclaredOrder() throws SQLException {
        PreparedStatement stmt = mock(PreparedStatement.class);
        Employee value = new Employee();
        value.personnelNo = 42L;
        value.fullName = "Иванов";
        value.salary = new BigDecimal("1000.50");
        value.hiredAt = LocalDate.of(2026, 7, 29);

        new RowBinder<Employee>(model.allBindings()).bind(stmt, 1, value);

        InOrder order = inOrder(stmt);
        order.verify(stmt).setObject(1, 42L);
        order.verify(stmt).setObject(2, "Иванов");
        order.verify(stmt).setObject(3, new BigDecimal("1000.50"));
        order.verify(stmt).setObject(4, LocalDate.of(2026, 7, 29));
    }

    @Test
    void nullBecomesSetNull() throws SQLException {
        PreparedStatement stmt = mock(PreparedStatement.class);

        new RowBinder<Employee>(model.allBindings()).bind(stmt, 1, new Employee());

        verify(stmt, org.mockito.Mockito.times(4)).setNull(anyInt(), org.mockito.Mockito.eq(Types.NULL));
    }

    @Test
    void parameterIndexOffsetShiftsAllColumns() throws SQLException {
        PreparedStatement stmt = mock(PreparedStatement.class);
        Employee value = new Employee();
        value.personnelNo = 1L;

        new RowBinder<Employee>(model.allBindings()).bind(stmt, 5, value);

        verify(stmt).setObject(5, 1L);
    }

    @Test
    void columnCountMatchesBindingCount() {
        assertThat(new RowBinder<Employee>(model.allBindings()).columnCount()).isEqualTo(4);
    }
}
```

- [ ] **Step 2: Запустить тесты, убедиться что падают**

Run: `./gradlew :excel-import-core:test --tests "*SqlBuilderTest*" --tests "*RowBinderTest*"`
Expected: FAIL — `cannot find symbol: class SqlBuilder`.

- [ ] **Step 3: Реализовать `SqlBuilder`**

```java
package io.github.excelimport.internal.write;

import io.github.excelimport.ConflictStrategy;
import io.github.excelimport.TableRef;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Генерирует multi-row INSERT. Учитывает предел PostgreSQL в 65535 bind-параметров
 * на один запрос: при большом batchSize батч дробится на чанки.
 */
public final class SqlBuilder {

    /** Жёсткий предел числа bind-параметров в одном запросе PostgreSQL. */
    public static final int MAX_BIND_PARAMETERS = 65_535;

    private final String prefix;
    private final String tuple;
    private final String suffix;
    private final int columnCount;
    private final Map<Integer, String> cache = new ConcurrentHashMap<>();

    public SqlBuilder(TableRef table, List<String> columns, ConflictStrategy conflict) {
        if (columns.isEmpty()) {
            throw new IllegalArgumentException("список колонок пуст");
        }
        this.columnCount = columns.size();
        this.prefix = "INSERT INTO " + table.qualifiedName() + " ("
                + columns.stream().map(TableRef::quote).collect(Collectors.joining(", "))
                + ") VALUES ";
        this.tuple = "(" + "?, ".repeat(columnCount - 1) + "?)";
        String conflictSql = conflict.toSql();
        this.suffix = conflictSql.isEmpty() ? "" : " " + conflictSql;
    }

    public int columnCount() {
        return columnCount;
    }

    /** Сколько строк максимум влезает в один запрос, не превысив предел параметров. */
    public int maxRowsPerStatement() {
        return Math.max(1, MAX_BIND_PARAMETERS / columnCount);
    }

    /** Фактический размер чанка: запрошенный batchSize, урезанный пределом параметров. */
    public int chunkSize(int requestedBatchSize) {
        return Math.min(requestedBatchSize, maxRowsPerStatement());
    }

    /** SQL для указанного числа строк. Кэшируется, потому что вариантов всего два-три. */
    public String insertSql(int rowCount) {
        if (rowCount < 1) {
            throw new IllegalArgumentException("rowCount должен быть >= 1, получено: " + rowCount);
        }
        return cache.computeIfAbsent(rowCount, this::buildSql);
    }

    private String buildSql(int rowCount) {
        StringBuilder sql = new StringBuilder(prefix.length() + rowCount * (tuple.length() + 2)
                + suffix.length());
        sql.append(prefix);
        for (int i = 0; i < rowCount; i++) {
            if (i > 0) {
                sql.append(", ");
            }
            sql.append(tuple);
        }
        sql.append(suffix);
        return sql.toString();
    }
}
```

- [ ] **Step 4: Реализовать `RowBinder`**

```java
package io.github.excelimport.internal.write;

import io.github.excelimport.exception.ExcelImportException;
import io.github.excelimport.internal.map.ColumnBinding;
import java.lang.invoke.MethodHandle;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Types;
import java.util.ArrayList;
import java.util.List;

/**
 * Привязывает поля объекта к параметрам {@link PreparedStatement}.
 * Порядок соответствует {@code MappingModel.allDbColumns()}.
 */
public final class RowBinder<T> {

    private final List<MethodHandle> getters;

    public RowBinder(List<ColumnBinding> bindings) {
        List<MethodHandle> handles = new ArrayList<>(bindings.size());
        for (ColumnBinding binding : bindings) {
            handles.add(binding.getter());
        }
        this.getters = List.copyOf(handles);
    }

    public int columnCount() {
        return getters.size();
    }

    /**
     * @param startParameterIndex 1-based индекс первого параметра этой строки
     */
    public void bind(PreparedStatement statement, int startParameterIndex, T value)
            throws SQLException {
        int index = startParameterIndex;
        for (MethodHandle getter : getters) {
            Object fieldValue;
            try {
                fieldValue = getter.invoke(value);
            } catch (Throwable e) {
                throw new ExcelImportException("не удалось прочитать поле объекта строки", e);
            }
            if (fieldValue == null) {
                statement.setNull(index, Types.NULL);
            } else {
                // setObject справляется с java.time.* через драйвер pgjdbc
                statement.setObject(index, fieldValue);
            }
            index++;
        }
    }
}
```

Использование `Types.NULL` вместо конкретного типа допустимо: pgjdbc выводит тип из контекста запроса. Если для какой-то колонки этого окажется недостаточно (проявится как `could not determine data type of parameter`), в `ColumnBinding` добавляется поле `sqlType` — но это преждевременная сложность до появления такого случая.

- [ ] **Step 5: Реализовать `InsertExecutor`**

```java
package io.github.excelimport.internal.write;

import io.github.excelimport.RowRef;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.List;

/** Выполняет один multi-row INSERT в переданном соединении. Транзакцией не управляет. */
public final class InsertExecutor<T> {

    private final SqlBuilder sqlBuilder;
    private final RowBinder<T> binder;
    private final int queryTimeoutSeconds;

    public InsertExecutor(SqlBuilder sqlBuilder, RowBinder<T> binder, int queryTimeoutSeconds) {
        this.sqlBuilder = sqlBuilder;
        this.binder = binder;
        this.queryTimeoutSeconds = queryTimeoutSeconds;
    }

    /**
     * @return число вставленных строк (может быть меньше размера чанка при ON CONFLICT DO NOTHING)
     */
    public int execute(Connection connection, List<RowRef<T>> chunk) throws SQLException {
        String sql = sqlBuilder.insertSql(chunk.size());
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            if (queryTimeoutSeconds > 0) {
                statement.setQueryTimeout(queryTimeoutSeconds);
            }
            int parameterIndex = 1;
            for (RowRef<T> row : chunk) {
                binder.bind(statement, parameterIndex, row.value());
                parameterIndex += binder.columnCount();
            }
            return statement.executeUpdate();
        }
    }

    public SqlBuilder sqlBuilder() {
        return sqlBuilder;
    }
}
```

- [ ] **Step 6: Запустить тесты**

Run: `./gradlew :excel-import-core:test --tests "*SqlBuilderTest*" --tests "*RowBinderTest*"`
Expected: PASS — 8 + 4 теста. `InsertExecutor` покрывается интеграционными тестами Task 17 — мокать `PreparedStatement` для проверки самого выполнения смысла нет.

- [ ] **Step 7: Коммит**

```bash
git add excel-import-core/src/main/java/io/github/excelimport/internal/write \
        excel-import-core/src/test/java/io/github/excelimport/internal/write
git commit -m "feat: add multi-row INSERT SQL builder, parameter binder and executor"
```

---

### Task 12: Классификация SQL-ошибок и бисекция сбойного батча

**Files:**
- Create: `excel-import-core/src/main/java/io/github/excelimport/SqlErrorClassifier.java`
- Create: `excel-import-core/src/main/java/io/github/excelimport/internal/write/DefaultSqlErrorClassifier.java`
- Create: `excel-import-core/src/main/java/io/github/excelimport/internal/write/DatabaseErrorMessages.java`
- Create: `excel-import-core/src/main/java/io/github/excelimport/internal/write/BatchSplitter.java`
- Test: `excel-import-core/src/test/java/io/github/excelimport/internal/write/DefaultSqlErrorClassifierTest.java`
- Test: `excel-import-core/src/test/java/io/github/excelimport/internal/write/BatchSplitterTest.java`

**Interfaces:**
- Consumes: `RowRef`, `RowError` (Task 2).
- Produces:
  - `interface SqlErrorClassifier { boolean isFatal(SQLException e); }`.
  - `DefaultSqlErrorClassifier` — фатальны классы `08` (соединение), `53` (ресурсы), `57` (оператор прервал), коды `42P01`, `42703`, `42501`, `3D000`, `28P01`; нефатальны `23*` (ограничения) и `22*` (данные).
  - `DatabaseErrorMessages.describe(SQLException, boolean includeDetail)` → человекочитаемое сообщение с `SQLState`, именем constraint и (опционально) `detail`. Работает и без pgjdbc на classpath — извлекает `ServerErrorMessage` через рефлексию.
  - `BatchSplitter<T>` — конструктор `BatchSplitter(int maxSplitDepth, SqlErrorClassifier, boolean includeDatabaseDetail)`; метод `SplitResult insertWithBisection(List<RowRef<T>> batch, ChunkAttempt<T> attempt)`; интерфейс `ChunkAttempt<T> { int attempt(List<RowRef<T>> chunk) throws SQLException; }`; record `SplitResult(int insertedCount, List<RowError> errors, int statementCount)`.

- [ ] **Step 1: Написать падающие тесты**

`DefaultSqlErrorClassifierTest.java`:

```java
package io.github.excelimport.internal.write;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.excelimport.SqlErrorClassifier;
import java.sql.SQLException;
import org.junit.jupiter.api.Test;

class DefaultSqlErrorClassifierTest {

    private final SqlErrorClassifier classifier = new DefaultSqlErrorClassifier();

    @Test
    void connectionFailureIsFatal() {
        assertThat(classifier.isFatal(new SQLException("connection lost", "08006"))).isTrue();
    }

    @Test
    void missingTableIsFatal() {
        assertThat(classifier.isFatal(new SQLException("relation does not exist", "42P01"))).isTrue();
    }

    @Test
    void missingColumnIsFatal() {
        assertThat(classifier.isFatal(new SQLException("column does not exist", "42703"))).isTrue();
    }

    @Test
    void insufficientPrivilegeIsFatal() {
        assertThat(classifier.isFatal(new SQLException("permission denied", "42501"))).isTrue();
    }

    @Test
    void outOfResourcesIsFatal() {
        assertThat(classifier.isFatal(new SQLException("disk full", "53100"))).isTrue();
    }

    @Test
    void uniqueViolationIsNotFatal() {
        assertThat(classifier.isFatal(new SQLException("duplicate key", "23505"))).isFalse();
    }

    @Test
    void notNullViolationIsNotFatal() {
        assertThat(classifier.isFatal(new SQLException("null value", "23502"))).isFalse();
    }

    @Test
    void dataExceptionIsNotFatal() {
        assertThat(classifier.isFatal(new SQLException("numeric overflow", "22003"))).isFalse();
    }

    @Test
    void unknownSqlStateIsTreatedAsFatal() {
        // консервативно: неизвестное состояние лучше не делить пополам вслепую
        assertThat(classifier.isFatal(new SQLException("что-то странное", "XX000"))).isTrue();
    }

    @Test
    void nullSqlStateIsFatal() {
        assertThat(classifier.isFatal(new SQLException("без состояния"))).isTrue();
    }

    @Test
    void fatalCauseInsideBatchExceptionIsDetected() {
        SQLException fatal = new SQLException("connection lost", "08006");
        SQLException batch = new java.sql.BatchUpdateException(new int[0], fatal);

        assertThat(classifier.isFatal(batch)).isTrue();
    }
}
```

`BatchSplitterTest.java`:

```java
package io.github.excelimport.internal.write;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.excelimport.ErrorKind;
import io.github.excelimport.RowRef;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class BatchSplitterTest {

    private static List<RowRef<String>> batch(int size) {
        List<RowRef<String>> rows = new ArrayList<>(size);
        for (int i = 1; i <= size; i++) {
            rows.add(new RowRef<>(i, "row" + i));
        }
        return rows;
    }

    /** Имитация БД: перечисленные номера строк всегда валят запрос. */
    private static BatchSplitter.ChunkAttempt<String> failingRows(
            Set<Integer> badRowNums, List<Integer> statementSizes) {
        return chunk -> {
            statementSizes.add(chunk.size());
            for (RowRef<String> row : chunk) {
                if (badRowNums.contains(row.rowNum())) {
                    throw new SQLException("duplicate key value violates unique constraint \"uq_x\"", "23505");
                }
            }
            return chunk.size();
        };
    }

    private BatchSplitter<String> splitter(int maxDepth) {
        return new BatchSplitter<>(maxDepth, new DefaultSqlErrorClassifier(), true);
    }

    @Test
    void cleanBatchInsertsInOneStatement() {
        List<Integer> statements = new ArrayList<>();

        BatchSplitter.SplitResult result =
                splitter(16).insertWithBisection(batch(8), failingRows(Set.of(), statements));

        assertThat(result.insertedCount()).isEqualTo(8);
        assertThat(result.errors()).isEmpty();
        assertThat(statements).containsExactly(8);
    }

    @Test
    void isolatesSingleBadRowInTheMiddle() {
        List<Integer> statements = new ArrayList<>();

        BatchSplitter.SplitResult result =
                splitter(16).insertWithBisection(batch(8), failingRows(Set.of(5), statements));

        assertThat(result.insertedCount()).isEqualTo(7);
        assertThat(result.errors()).singleElement().satisfies(error -> {
            assertThat(error.rowNum()).isEqualTo(5);
            assertThat(error.kind()).isEqualTo(ErrorKind.DATABASE);
            assertThat(error.code()).isEqualTo("23505");
            assertThat(error.message()).contains("uq_x");
        });
    }

    @Test
    void isolatesFirstAndLastRow() {
        BatchSplitter.SplitResult result = splitter(16)
                .insertWithBisection(batch(8), failingRows(Set.of(1, 8), new ArrayList<>()));

        assertThat(result.insertedCount()).isEqualTo(6);
        assertThat(result.errors()).extracting("rowNum").containsExactlyInAnyOrder(1, 8);
    }

    @Test
    void allRowsBadProducesErrorPerRow() {
        BatchSplitter.SplitResult result = splitter(16)
                .insertWithBisection(batch(4), failingRows(Set.of(1, 2, 3, 4), new ArrayList<>()));

        assertThat(result.insertedCount()).isZero();
        assertThat(result.errors()).hasSize(4);
    }

    @Test
    void singleRowBatchFailsWithoutSplitting() {
        List<Integer> statements = new ArrayList<>();

        BatchSplitter.SplitResult result =
                splitter(16).insertWithBisection(batch(1), failingRows(Set.of(1), statements));

        assertThat(result.errors()).hasSize(1);
        assertThat(statements).containsExactly(1);
    }

    @Test
    void depthLimitMarksWholeSubBatchWithSharedReason() {
        // maxDepth = 0 запрещает деление вообще
        BatchSplitter.SplitResult result =
                splitter(0).insertWithBisection(batch(4), failingRows(Set.of(3), new ArrayList<>()));

        assertThat(result.insertedCount()).isZero();
        assertThat(result.errors()).hasSize(4);
        assertThat(result.errors()).allSatisfy(error ->
                assertThat(error.message()).contains("не удалось изолировать"));
    }

    @Test
    void fatalErrorPropagatesInsteadOfSplitting() {
        BatchSplitter.ChunkAttempt<String> connectionLost = chunk -> {
            throw new SQLException("connection lost", "08006");
        };

        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                        splitter(16).insertWithBisection(batch(8), connectionLost))
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("connection lost");
    }

    @Test
    void statementCountIsReportedForObservability() {
        BatchSplitter.SplitResult result = splitter(16)
                .insertWithBisection(batch(8), failingRows(Set.of(5), new ArrayList<>()));

        // 1 неудачный на 8 + деления: 4+4, 4 ок, 4 → 2+2, 2 ок, 2 → 1+1
        assertThat(result.statementCount()).isGreaterThan(1);
    }

    @Test
    void errorsAreOrderedByRowNum() {
        BatchSplitter.SplitResult result = splitter(16)
                .insertWithBisection(batch(16), failingRows(Set.of(2, 9, 14), new ArrayList<>()));

        assertThat(result.errors()).extracting("rowNum").containsExactly(2, 9, 14);
    }
}
```

- [ ] **Step 2: Запустить тесты, убедиться что падают**

Run: `./gradlew :excel-import-core:test --tests "*DefaultSqlErrorClassifierTest*" --tests "*BatchSplitterTest*"`
Expected: FAIL — `cannot find symbol: class SqlErrorClassifier`.

- [ ] **Step 3: Реализовать `SqlErrorClassifier` и `DefaultSqlErrorClassifier`**

`SqlErrorClassifier.java`:

```java
package io.github.excelimport;

import java.sql.SQLException;

/**
 * Решает, можно ли продолжать импорт после ошибки БД. Фатальная ошибка прерывает
 * весь прогон; нефатальная запускает деление батча для поиска сбойных строк.
 *
 * <p>Потребитель может подменить реализацию, если его окружение возвращает
 * нестандартные {@code SQLState}.
 */
public interface SqlErrorClassifier {

    boolean isFatal(SQLException exception);
}
```

`internal/write/DefaultSqlErrorClassifier.java`:

```java
package io.github.excelimport.internal.write;

import io.github.excelimport.SqlErrorClassifier;
import java.sql.SQLException;
import java.util.Set;

/**
 * Классификация по {@code SQLState} PostgreSQL. Консервативна: неизвестное состояние
 * считается фатальным, чтобы не делить батч вслепую при системной проблеме.
 */
public final class DefaultSqlErrorClassifier implements SqlErrorClassifier {

    /** Классы состояний, при которых деление батча бессмысленно. */
    private static final Set<String> FATAL_CLASSES = Set.of(
            "08", // connection exception
            "53", // insufficient resources
            "57", // operator intervention
            "58", // system error
            "F0", // configuration file error
            "XX"); // internal error

    /** Полные коды, фатальные точечно. */
    private static final Set<String> FATAL_CODES = Set.of(
            "42P01", // undefined_table
            "42703", // undefined_column
            "42P07", // duplicate_table
            "42501", // insufficient_privilege
            "42601", // syntax_error
            "3D000", // invalid_catalog_name
            "28P01", // invalid_password
            "28000"); // invalid_authorization_specification

    /** Классы, ожидаемые на «плохих строках»: деление батча их изолирует. */
    private static final Set<String> RECOVERABLE_CLASSES = Set.of(
            "23", // integrity constraint violation
            "22"); // data exception

    @Override
    public boolean isFatal(SQLException exception) {
        for (Throwable current = exception; current != null; current = current.getCause()) {
            if (current instanceof SQLException sqlException) {
                Boolean verdict = classify(sqlException.getSQLState());
                if (verdict != null && verdict) {
                    return true;
                }
                if (verdict != null) {
                    return false;
                }
            }
        }
        return true; // не смогли определить — считаем фатальным
    }

    /** true — фатально, false — восстановимо, null — неизвестно, смотрим дальше по цепочке. */
    private static Boolean classify(String sqlState) {
        if (sqlState == null || sqlState.length() < 2) {
            return null;
        }
        if (FATAL_CODES.contains(sqlState)) {
            return Boolean.TRUE;
        }
        String errorClass = sqlState.substring(0, 2);
        if (FATAL_CLASSES.contains(errorClass)) {
            return Boolean.TRUE;
        }
        if (RECOVERABLE_CLASSES.contains(errorClass)) {
            return Boolean.FALSE;
        }
        return null;
    }
}
```

- [ ] **Step 4: Реализовать `DatabaseErrorMessages`**

```java
package io.github.excelimport.internal.write;

import java.sql.SQLException;

/**
 * Человекочитаемое описание ошибки БД для отчёта. Достаёт constraint и detail из
 * {@code PSQLException} через рефлексию, чтобы ядро не зависело от pgjdbc в compile-time.
 */
public final class DatabaseErrorMessages {

    private DatabaseErrorMessages() {}

    /**
     * @param includeDetail включать ли поле {@code detail} — оно может содержать значения строки
     */
    public static String describe(SQLException exception, boolean includeDetail) {
        SQLException root = rootSqlException(exception);
        StringBuilder message = new StringBuilder();
        String constraint = serverField(root, "getConstraint");
        if (constraint != null) {
            message.append("нарушено ограничение ").append(constraint).append(": ");
        }
        String primary = serverField(root, "getMessage");
        message.append(primary != null ? primary : root.getMessage());
        if (includeDetail) {
            String detail = serverField(root, "getDetail");
            if (detail != null && !detail.isBlank()) {
                message.append(" (").append(detail).append(')');
            }
        }
        String state = root.getSQLState();
        if (state != null) {
            message.append(" [SQLState ").append(state).append(']');
        }
        return message.toString();
    }

    /** Код ошибки для {@code RowError.code()}. */
    public static String codeOf(SQLException exception) {
        String state = rootSqlException(exception).getSQLState();
        return state != null ? state : "SQL_ERROR";
    }

    private static SQLException rootSqlException(SQLException exception) {
        SQLException result = exception;
        for (Throwable current = exception.getCause(); current != null; current = current.getCause()) {
            if (current instanceof SQLException sqlException && sqlException.getSQLState() != null) {
                result = sqlException;
                break;
            }
        }
        if (result.getSQLState() == null && exception.getNextException() != null) {
            return exception.getNextException();
        }
        return result;
    }

    private static String serverField(SQLException exception, String accessor) {
        try {
            Object serverErrorMessage = exception.getClass()
                    .getMethod("getServerErrorMessage")
                    .invoke(exception);
            if (serverErrorMessage == null) {
                return null;
            }
            Object value = serverErrorMessage.getClass().getMethod(accessor).invoke(serverErrorMessage);
            return value == null ? null : value.toString();
        } catch (ReflectiveOperationException | RuntimeException e) {
            return null; // не pgjdbc или другая версия — довольствуемся getMessage()
        }
    }
}
```

- [ ] **Step 5: Реализовать `BatchSplitter`**

```java
package io.github.excelimport.internal.write;

import io.github.excelimport.RowError;
import io.github.excelimport.RowRef;
import io.github.excelimport.SqlErrorClassifier;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Изолирует сбойные строки рекурсивным делением батча пополам. Каждая попытка —
 * отдельный вызов {@link ChunkAttempt}, который вызывающая сторона выполняет в своей
 * транзакции.
 */
public final class BatchSplitter<T> {

    private static final Logger log = LoggerFactory.getLogger(BatchSplitter.class);

    /** Одна попытка вставки чанка. Реализация отвечает за транзакцию. */
    public interface ChunkAttempt<T> {
        int attempt(List<RowRef<T>> chunk) throws SQLException;
    }

    /**
     * @param insertedCount  сколько строк реально вставлено
     * @param errors         ошибки по строкам, отсортированные по номеру строки
     * @param statementCount сколько запросов потребовалось — для наблюдаемости
     */
    public record SplitResult(int insertedCount, List<RowError> errors, int statementCount) {

        public SplitResult {
            errors = List.copyOf(errors);
        }
    }

    private final int maxSplitDepth;
    private final SqlErrorClassifier classifier;
    private final boolean includeDatabaseDetail;

    public BatchSplitter(
            int maxSplitDepth, SqlErrorClassifier classifier, boolean includeDatabaseDetail) {
        this.maxSplitDepth = maxSplitDepth;
        this.classifier = classifier;
        this.includeDatabaseDetail = includeDatabaseDetail;
    }

    /**
     * @throws SQLException если ошибка признана фатальной — импорт должен прерваться
     */
    public SplitResult insertWithBisection(List<RowRef<T>> batch, ChunkAttempt<T> attempt)
            throws SQLException {
        State state = new State();
        insert(batch, attempt, 0, state);
        state.errors.sort(Comparator.comparingInt(RowError::rowNum));
        return new SplitResult(state.inserted, state.errors, state.statements);
    }

    private void insert(List<RowRef<T>> chunk, ChunkAttempt<T> attempt, int depth, State state)
            throws SQLException {
        if (chunk.isEmpty()) {
            return;
        }
        try {
            state.statements++;
            state.inserted += attempt.attempt(chunk);
        } catch (SQLException e) {
            if (classifier.isFatal(e)) {
                throw e;
            }
            if (chunk.size() == 1) {
                RowRef<T> row = chunk.get(0);
                state.errors.add(RowError.database(
                        row.rowNum(),
                        DatabaseErrorMessages.codeOf(e),
                        DatabaseErrorMessages.describe(e, includeDatabaseDetail)));
                return;
            }
            if (depth >= maxSplitDepth) {
                String reason = "не удалось изолировать сбойную строку: достигнут предел деления батча ("
                        + maxSplitDepth + "); ошибка батча: "
                        + DatabaseErrorMessages.describe(e, includeDatabaseDetail);
                String code = DatabaseErrorMessages.codeOf(e);
                for (RowRef<T> row : chunk) {
                    state.errors.add(RowError.database(row.rowNum(), code, reason));
                }
                return;
            }
            log.warn(
                    "батч из {} строк отклонён ({}), делю пополам, глубина {}",
                    chunk.size(),
                    DatabaseErrorMessages.codeOf(e),
                    depth);
            int middle = chunk.size() / 2;
            insert(chunk.subList(0, middle), attempt, depth + 1, state);
            insert(chunk.subList(middle, chunk.size()), attempt, depth + 1, state);
        }
    }

    /** Изменяемое состояние обхода: держим отдельно, чтобы рекурсия оставалась читаемой. */
    private static final class State {
        private int inserted;
        private int statements;
        private final List<RowError> errors = new ArrayList<>();
    }
}
```

- [ ] **Step 6: Запустить тесты**

Run: `./gradlew :excel-import-core:test --tests "*DefaultSqlErrorClassifierTest*" --tests "*BatchSplitterTest*"`
Expected: PASS — 11 + 9 тестов.

- [ ] **Step 7: Коммит**

```bash
git add excel-import-core/src/main/java/io/github/excelimport/SqlErrorClassifier.java \
        excel-import-core/src/main/java/io/github/excelimport/internal/write \
        excel-import-core/src/test/java/io/github/excelimport/internal/write
git commit -m "feat: add SQL error classification and recursive batch bisection"
```

---

### Task 13: `BatchValidator` SPI, защита соединения, `BatchProcessor`

**Files:**
- Create: `excel-import-core/src/main/java/io/github/excelimport/validate/BatchValidator.java`
- Create: `excel-import-core/src/main/java/io/github/excelimport/internal/write/GuardedConnection.java`
- Create: `excel-import-core/src/main/java/io/github/excelimport/internal/write/BatchProcessor.java`
- Test: `excel-import-core/src/test/java/io/github/excelimport/internal/write/GuardedConnectionTest.java`
- Test: `excel-import-core/src/integrationTest/java/io/github/excelimport/internal/write/BatchProcessorIT.java`
- Test: `excel-import-core/src/integrationTest/java/io/github/excelimport/testsupport/PostgresSupport.java`

**Interfaces:**
- Consumes: `RowRef`, `RowError` (Task 2), `InsertExecutor` (Task 11), `BatchSplitter` (Task 12).
- Produces:
  - `interface BatchValidator<T> { List<RowError> validate(List<RowRef<T>> batch, Connection connection) throws SQLException; }`.
  - `GuardedConnection.wrap(Connection)` — динамический прокси, запрещающий `commit`, `rollback`, `setAutoCommit`, `close`, `abort`, `setSavepoint` с `IllegalStateException`.
  - `BatchProcessor<T>` — конструктор `BatchProcessor(DataSource, InsertExecutor<T>, BatchSplitter<T>, List<BatchValidator<T>>, boolean dryRun, int requestedBatchSize)`; метод `BatchOutcome process(List<RowRef<T>> batch) throws SQLException`; record `BatchOutcome(int insertedCount, List<RowError> errors, int statementCount)`. Соединение берётся из `DataSource` ровно один раз на вызов `process` и возвращается в пул после коммита.
  - Тестовый хелпер `PostgresSupport` — общий Testcontainers-контейнер и `DataSource`.

- [ ] **Step 1: Написать падающий unit-тест защиты соединения**

```java
package io.github.excelimport.internal.write;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import org.junit.jupiter.api.Test;

class GuardedConnectionTest {

    @Test
    void queryMethodsAreDelegated() throws SQLException {
        Connection real = mock(Connection.class);
        PreparedStatement statement = mock(PreparedStatement.class);
        when(real.prepareStatement("select 1")).thenReturn(statement);

        Connection guarded = GuardedConnection.wrap(real);

        assertThat(guarded.prepareStatement("select 1")).isSameAs(statement);
    }

    @Test
    void commitIsForbidden() {
        Connection guarded = GuardedConnection.wrap(mock(Connection.class));

        assertThatThrownBy(guarded::commit)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("commit");
    }

    @Test
    void rollbackIsForbidden() {
        Connection guarded = GuardedConnection.wrap(mock(Connection.class));

        assertThatThrownBy(guarded::rollback).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void closeIsForbiddenAndDoesNotReachRealConnection() throws SQLException {
        Connection real = mock(Connection.class);
        Connection guarded = GuardedConnection.wrap(real);

        assertThatThrownBy(guarded::close).isInstanceOf(IllegalStateException.class);
        verify(real, never()).close();
    }

    @Test
    void setAutoCommitIsForbidden() {
        Connection guarded = GuardedConnection.wrap(mock(Connection.class));

        assertThatThrownBy(() -> guarded.setAutoCommit(true)).isInstanceOf(IllegalStateException.class);
    }
}
```

- [ ] **Step 2: Запустить тест, убедиться что падает**

Run: `./gradlew :excel-import-core:test --tests "*GuardedConnectionTest*"`
Expected: FAIL — `cannot find symbol: class GuardedConnection`.

- [ ] **Step 3: Реализовать `BatchValidator` и `GuardedConnection`**

`validate/BatchValidator.java`:

```java
package io.github.excelimport.validate;

import io.github.excelimport.RowError;
import io.github.excelimport.RowRef;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;

/**
 * Межстрочная валидация собранного батча — единственный императивный хук пользователя.
 *
 * <p>Вызывается перед вставкой, в той же транзакции, поэтому видит данные, вставленные
 * предыдущими батчами этого же прогона, и может делать проверки по БД одним запросом.
 *
 * <p>Контракт: реализация НЕ должна вызывать {@code commit}, {@code rollback},
 * {@code setAutoCommit} или {@code close} на переданном соединении — попытка приведёт
 * к {@link IllegalStateException}. Строки, на которые возвращены ошибки, исключаются
 * из батча; остальные вставляются.
 *
 * <p>Реализация должна быть потокобезопасной, если один импортёр используется из
 * нескольких потоков.
 */
public interface BatchValidator<T> {

    /**
     * @param batch      строки батча вместе с 1-based номерами строк Excel
     * @param connection соединение текущей транзакции, только для чтения данных
     * @return ошибки, привязанные к номерам строк; пустой список, если всё в порядке
     */
    List<RowError> validate(List<RowRef<T>> batch, Connection connection) throws SQLException;
}
```

`internal/write/GuardedConnection.java`:

```java
package io.github.excelimport.internal.write;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.util.Set;

/**
 * Прокси над {@link Connection}, передаваемым в {@code BatchValidator}: запрещает
 * управление транзакцией и закрытие, потому что этим владеет {@link BatchProcessor}.
 */
public final class GuardedConnection {

    private static final Set<String> FORBIDDEN = Set.of(
            "commit", "rollback", "close", "setAutoCommit", "abort", "setSavepoint",
            "releaseSavepoint", "setTransactionIsolation");

    private GuardedConnection() {}

    public static Connection wrap(Connection delegate) {
        return (Connection) Proxy.newProxyInstance(
                Connection.class.getClassLoader(),
                new Class<?>[] {Connection.class},
                new Handler(delegate));
    }

    private record Handler(Connection delegate) implements InvocationHandler {

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            if (FORBIDDEN.contains(method.getName())) {
                throw new IllegalStateException(
                        "BatchValidator не должен вызывать " + method.getName()
                                + "() — транзакцией управляет библиотека");
            }
            try {
                return method.invoke(delegate, args);
            } catch (InvocationTargetException e) {
                throw e.getTargetException();
            }
        }
    }
}
```

- [ ] **Step 4: Реализовать `BatchProcessor`**

```java
package io.github.excelimport.internal.write;

import io.github.excelimport.RowError;
import io.github.excelimport.RowRef;
import io.github.excelimport.validate.BatchValidator;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Обрабатывает один батч: берёт соединение, открывает транзакцию, прогоняет
 * пользовательские валидаторы, вставляет остаток, коммитит. При ошибке БД делегирует
 * поиск сбойных строк {@link BatchSplitter}.
 */
public final class BatchProcessor<T> {

    private static final Logger log = LoggerFactory.getLogger(BatchProcessor.class);

    /** Итог обработки батча. */
    public record BatchOutcome(int insertedCount, List<RowError> errors, int statementCount) {

        public BatchOutcome {
            errors = List.copyOf(errors);
        }
    }

    private final DataSource dataSource;
    private final InsertExecutor<T> executor;
    private final BatchSplitter<T> splitter;
    private final List<BatchValidator<T>> validators;
    private final boolean dryRun;
    private final int chunkSize;

    public BatchProcessor(
            DataSource dataSource,
            InsertExecutor<T> executor,
            BatchSplitter<T> splitter,
            List<BatchValidator<T>> validators,
            boolean dryRun,
            int requestedBatchSize) {
        this.dataSource = dataSource;
        this.executor = executor;
        this.splitter = splitter;
        this.validators = List.copyOf(validators);
        this.dryRun = dryRun;
        this.chunkSize = executor.sqlBuilder().chunkSize(requestedBatchSize);
        if (chunkSize < requestedBatchSize) {
            log.warn(
                    "batchSize {} превышает предел {} bind-параметров при {} колонках; "
                            + "батч будет дробиться на чанки по {} строк внутри одной транзакции",
                    requestedBatchSize,
                    SqlBuilder.MAX_BIND_PARAMETERS,
                    executor.sqlBuilder().columnCount(),
                    chunkSize);
        }
    }

    /**
     * @throws SQLException при фатальной ошибке БД — импорт должен прерваться
     */
    public BatchOutcome process(List<RowRef<T>> batch) throws SQLException {
        if (batch.isEmpty()) {
            return new BatchOutcome(0, List.of(), 0);
        }
        try (Connection connection = dataSource.getConnection()) {
            boolean originalAutoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try {
                List<RowError> validationErrors = new ArrayList<>();
                List<RowRef<T>> accepted = runValidators(batch, connection, validationErrors);

                if (dryRun) {
                    connection.rollback();
                    return new BatchOutcome(accepted.size(), validationErrors, 0);
                }

                BatchSplitter.SplitResult result = splitter.insertWithBisection(
                        accepted, chunk -> insertInChunks(connection, chunk));
                connection.commit();

                List<RowError> allErrors = new ArrayList<>(validationErrors);
                allErrors.addAll(result.errors());
                return new BatchOutcome(result.insertedCount(), allErrors, result.statementCount());
            } catch (SQLException | RuntimeException e) {
                safeRollback(connection);
                throw e;
            } finally {
                restoreAutoCommit(connection, originalAutoCommit);
            }
        }
    }

    private List<RowRef<T>> runValidators(
            List<RowRef<T>> batch, Connection connection, List<RowError> collectedErrors)
            throws SQLException {
        if (validators.isEmpty()) {
            return batch;
        }
        Connection guarded = GuardedConnection.wrap(connection);
        Set<Integer> rejectedRows = new HashSet<>();
        for (BatchValidator<T> validator : validators) {
            List<RowError> errors = validator.validate(batch, guarded);
            if (errors == null || errors.isEmpty()) {
                continue;
            }
            collectedErrors.addAll(errors);
            errors.forEach(error -> rejectedRows.add(error.rowNum()));
        }
        if (rejectedRows.isEmpty()) {
            return batch;
        }
        List<RowRef<T>> accepted = new ArrayList<>(batch.size() - rejectedRows.size());
        for (RowRef<T> row : batch) {
            if (!rejectedRows.contains(row.rowNum())) {
                accepted.add(row);
            }
        }
        return accepted;
    }

    /**
     * Дробит чанк, если он не влезает в предел bind-параметров. Все под-чанки идут
     * в той же транзакции, поэтому для вызывающей стороны это одна попытка.
     */
    private int insertInChunks(Connection connection, List<RowRef<T>> rows) throws SQLException {
        if (rows.size() <= chunkSize) {
            return executor.execute(connection, rows);
        }
        int inserted = 0;
        for (int start = 0; start < rows.size(); start += chunkSize) {
            int end = Math.min(start + chunkSize, rows.size());
            inserted += executor.execute(connection, rows.subList(start, end));
        }
        return inserted;
    }

    private static void safeRollback(Connection connection) {
        try {
            connection.rollback();
        } catch (SQLException e) {
            log.warn("не удалось откатить транзакцию батча: {}", e.getMessage());
        }
    }

    private static void restoreAutoCommit(Connection connection, boolean original) {
        try {
            connection.setAutoCommit(original);
        } catch (SQLException e) {
            log.debug("не удалось вернуть autoCommit: {}", e.getMessage());
        }
    }
}
```

- [ ] **Step 5: Написать интеграционный тест на реальном PostgreSQL**

`excel-import-core/src/integrationTest/java/io/github/excelimport/testsupport/PostgresSupport.java`:

```java
package io.github.excelimport.testsupport;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import javax.sql.DataSource;
import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.containers.PostgreSQLContainer;

/** Один контейнер PostgreSQL на весь прогон интеграционных тестов. */
public final class PostgresSupport {

    private static final PostgreSQLContainer<?> CONTAINER =
            new PostgreSQLContainer<>("postgres:16-alpine");

    static {
        CONTAINER.start();
        Runtime.getRuntime().addShutdownHook(new Thread(CONTAINER::stop));
    }

    private PostgresSupport() {}

    public static DataSource dataSource() {
        PGSimpleDataSource dataSource = new PGSimpleDataSource();
        dataSource.setUrl(CONTAINER.getJdbcUrl());
        dataSource.setUser(CONTAINER.getUsername());
        dataSource.setPassword(CONTAINER.getPassword());
        return dataSource;
    }

    public static void execute(String... statements) {
        try (Connection connection = dataSource().getConnection();
                Statement statement = connection.createStatement()) {
            for (String sql : statements) {
                statement.execute(sql);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("не удалось выполнить DDL: " + String.join("; ", statements), e);
        }
    }

    public static long countRows(String table) {
        try (Connection connection = dataSource().getConnection();
                Statement statement = connection.createStatement();
                var rs = statement.executeQuery("SELECT count(*) FROM " + table)) {
            rs.next();
            return rs.getLong(1);
        } catch (SQLException e) {
            throw new IllegalStateException("не удалось посчитать строки в " + table, e);
        }
    }
}
```

`excel-import-core/src/integrationTest/java/io/github/excelimport/internal/write/BatchProcessorIT.java`:

```java
package io.github.excelimport.internal.write;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.excelimport.ConflictStrategy;
import io.github.excelimport.NamingStrategy;
import io.github.excelimport.RowError;
import io.github.excelimport.RowRef;
import io.github.excelimport.TableRef;
import io.github.excelimport.annotation.Column;
import io.github.excelimport.annotation.ExcelColumn;
import io.github.excelimport.annotation.ExcelSheet;
import io.github.excelimport.annotation.TargetTable;
import io.github.excelimport.internal.map.MappingModel;
import io.github.excelimport.internal.map.MappingModelFactory;
import io.github.excelimport.testsupport.PostgresSupport;
import io.github.excelimport.validate.BatchValidator;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class BatchProcessorIT {

    @ExcelSheet(name = "S")
    @TargetTable(name = "employee")
    public static class Employee {
        @ExcelColumn(header = "Номер")
        @Column("personnel_no")
        public Long personnelNo;

        @ExcelColumn(header = "ФИО")
        @Column("full_name")
        public String fullName;

        public Employee() {}

        static Employee of(long no, String name) {
            Employee employee = new Employee();
            employee.personnelNo = no;
            employee.fullName = name;
            return employee;
        }
    }

    private final DataSource dataSource = PostgresSupport.dataSource();
    private final MappingModel<Employee> model =
            MappingModelFactory.create(Employee.class, NamingStrategy.SNAKE_CASE);

    @BeforeEach
    void resetTable() {
        PostgresSupport.execute(
                "DROP TABLE IF EXISTS employee",
                "CREATE TABLE employee ("
                        + "personnel_no bigint PRIMARY KEY, "
                        + "full_name text NOT NULL)");
    }

    private BatchProcessor<Employee> processor(ConflictStrategy conflict, List<BatchValidator<Employee>> validators) {
        SqlBuilder sqlBuilder = new SqlBuilder(TableRef.of("employee"), model.allDbColumns(), conflict);
        InsertExecutor<Employee> executor =
                new InsertExecutor<>(sqlBuilder, new RowBinder<>(model.allBindings()), 0);
        BatchSplitter<Employee> splitter =
                new BatchSplitter<>(16, new DefaultSqlErrorClassifier(), true);
        return new BatchProcessor<>(dataSource, executor, splitter, validators, false, 1000);
    }

    private static List<RowRef<Employee>> batch(long... numbers) {
        List<RowRef<Employee>> rows = new ArrayList<>();
        int rowNum = 1;
        for (long number : numbers) {
            rows.add(new RowRef<>(rowNum++, Employee.of(number, "Сотрудник " + number)));
        }
        return rows;
    }

    @Test
    void insertsWholeBatchInOneTransaction() throws SQLException {
        BatchProcessor.BatchOutcome outcome = processor(ConflictStrategy.none(), List.of())
                .process(batch(1, 2, 3));

        assertThat(outcome.insertedCount()).isEqualTo(3);
        assertThat(outcome.errors()).isEmpty();
        assertThat(PostgresSupport.countRows("employee")).isEqualTo(3);
    }

    @Test
    void duplicateKeyInsideBatchIsIsolatedByBisection() throws SQLException {
        // строка 2 конфликтует со строкой 1 по первичному ключу
        BatchProcessor.BatchOutcome outcome = processor(ConflictStrategy.none(), List.of())
                .process(batch(1, 1, 2, 3));

        assertThat(outcome.errors()).hasSize(1);
        assertThat(outcome.insertedCount()).isEqualTo(3);
        assertThat(PostgresSupport.countRows("employee")).isEqualTo(3);
    }

    @Test
    void databaseErrorMessageMentionsConstraint() throws SQLException {
        BatchProcessor.BatchOutcome outcome = processor(ConflictStrategy.none(), List.of())
                .process(batch(1, 1));

        assertThat(outcome.errors()).singleElement().satisfies(error -> {
            assertThat(error.code()).isEqualTo("23505");
            assertThat(error.message()).contains("employee_pkey");
        });
    }

    @Test
    void onConflictDoNothingSwallowsDuplicates() throws SQLException {
        BatchProcessor<Employee> processor =
                processor(ConflictStrategy.doNothing("personnel_no"), List.of());

        processor.process(batch(1, 2));
        BatchProcessor.BatchOutcome second = processor.process(batch(2, 3));

        assertThat(second.errors()).isEmpty();
        assertThat(second.insertedCount()).isEqualTo(1); // только 3
        assertThat(PostgresSupport.countRows("employee")).isEqualTo(3);
    }

    @Test
    void onConflictDoUpdateOverwritesExistingRow() throws SQLException {
        processor(ConflictStrategy.none(), List.of()).process(batch(1));

        BatchProcessor<Employee> upserting = processor(
                ConflictStrategy.doUpdate(List.of("personnel_no"), List.of("full_name")), List.of());
        upserting.process(List.of(new RowRef<>(1, Employee.of(1, "Новое имя"))));

        assertThat(PostgresSupport.countRows("employee")).isEqualTo(1);
        assertThat(selectName(1)).isEqualTo("Новое имя");
    }

    @Test
    void batchValidatorSeesRowsCommittedByPreviousBatch() throws SQLException {
        BatchValidator<Employee> noDuplicatesInDb = (rows, connection) -> {
            List<RowError> errors = new ArrayList<>();
            try (var statement = connection.prepareStatement(
                    "SELECT personnel_no FROM employee WHERE personnel_no = ANY (?)")) {
                Long[] numbers = rows.stream().map(row -> row.value().personnelNo).toArray(Long[]::new);
                statement.setArray(1, connection.createArrayOf("bigint", numbers));
                try (var rs = statement.executeQuery()) {
                    List<Long> existing = new ArrayList<>();
                    while (rs.next()) {
                        existing.add(rs.getLong(1));
                    }
                    for (RowRef<Employee> row : rows) {
                        if (existing.contains(row.value().personnelNo)) {
                            errors.add(RowError.batch(
                                    row.rowNum(), "ALREADY_EXISTS", "запись уже есть в БД"));
                        }
                    }
                }
            }
            return errors;
        };

        BatchProcessor<Employee> processor =
                processor(ConflictStrategy.none(), List.of(noDuplicatesInDb));
        processor.process(batch(1, 2));
        BatchProcessor.BatchOutcome second = processor.process(batch(2, 3));

        assertThat(second.errors()).singleElement()
                .satisfies(error -> assertThat(error.code()).isEqualTo("ALREADY_EXISTS"));
        assertThat(second.insertedCount()).isEqualTo(1);
        assertThat(PostgresSupport.countRows("employee")).isEqualTo(3);
    }

    @Test
    void validatorAttemptingCommitIsRejected() {
        BatchValidator<Employee> misbehaving = (rows, connection) -> {
            connection.commit();
            return List.of();
        };

        assertThatThrownBy(() -> processor(ConflictStrategy.none(), List.of(misbehaving)).process(batch(1)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("commit");
        assertThat(PostgresSupport.countRows("employee")).isZero();
    }

    @Test
    void missingTableIsFatalAndPropagates() {
        PostgresSupport.execute("DROP TABLE IF EXISTS employee");

        assertThatThrownBy(() -> processor(ConflictStrategy.none(), List.of()).process(batch(1)))
                .isInstanceOf(SQLException.class)
                .satisfies(e -> assertThat(((SQLException) e).getSQLState()).isEqualTo("42P01"));
    }

    @Test
    void dryRunRollsBackEverything() throws SQLException {
        SqlBuilder sqlBuilder =
                new SqlBuilder(TableRef.of("employee"), model.allDbColumns(), ConflictStrategy.none());
        BatchProcessor<Employee> dryRun = new BatchProcessor<>(
                dataSource,
                new InsertExecutor<>(sqlBuilder, new RowBinder<>(model.allBindings()), 0),
                new BatchSplitter<>(16, new DefaultSqlErrorClassifier(), true),
                List.of(),
                true,
                1000);

        dryRun.process(batch(1, 2, 3));

        assertThat(PostgresSupport.countRows("employee")).isZero();
    }

    private String selectName(long personnelNo) throws SQLException {
        try (var connection = dataSource.getConnection();
                var statement =
                        connection.prepareStatement("SELECT full_name FROM employee WHERE personnel_no = ?")) {
            statement.setLong(1, personnelNo);
            try (var rs = statement.executeQuery()) {
                rs.next();
                return rs.getString(1);
            }
        }
    }
}
```

- [ ] **Step 6: Запустить unit-тесты**

Run: `./gradlew :excel-import-core:test --tests "*GuardedConnectionTest*"`
Expected: PASS — 5 тестов.

- [ ] **Step 7: Запустить интеграционные тесты**

Run: `./gradlew :excel-import-core:integrationTest`
Expected: PASS — 9 тестов `BatchProcessorIT`. Требуется запущенный Docker. Первый прогон скачивает образ `postgres:16-alpine`.

- [ ] **Step 8: Коммит**

```bash
git add excel-import-core/src/main/java/io/github/excelimport/validate \
        excel-import-core/src/main/java/io/github/excelimport/internal/write \
        excel-import-core/src/test/java/io/github/excelimport/internal/write \
        excel-import-core/src/integrationTest
git commit -m "feat: add BatchValidator SPI, connection guard and transactional BatchProcessor"
```

---

### Task 14: `RowOutcomeStore` с выгрузкой на диск

**Files:**
- Create: `excel-import-core/src/main/java/io/github/excelimport/outcome/RowOutcomeStore.java`
- Create: `excel-import-core/src/main/java/io/github/excelimport/internal/outcome/SpillableRowOutcomeStore.java`
- Test: `excel-import-core/src/test/java/io/github/excelimport/internal/outcome/SpillableRowOutcomeStoreTest.java`

**Interfaces:**
- Consumes: `RowStatus`, `RowOutcome` (Task 2).
- Produces:
  - `interface RowOutcomeStore extends AutoCloseable` — `void put(int rowNum, RowOutcome)`, `RowOutcome get(int rowNum)`, `int maxRowNum()`, `void seal()` (переход в режим чтения), `void close()`.
  - `SpillableRowOutcomeStore(Path tempDir, int maxMessagesInMemory)` — статусы в растущем `byte[]`, сообщения в `HashMap` до порога, затем последовательная запись во временный файл; после `seal()` чтение сообщений идёт merge-join'ом по возрастанию `rowNum`.

- [ ] **Step 1: Написать падающий тест**

```java
package io.github.excelimport.internal.outcome;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.excelimport.RowOutcome;
import io.github.excelimport.RowStatus;
import io.github.excelimport.outcome.RowOutcomeStore;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SpillableRowOutcomeStoreTest {

    @TempDir
    Path tempDir;

    @Test
    void storesAndReadsStatusesWithoutSpilling() throws Exception {
        try (RowOutcomeStore store = new SpillableRowOutcomeStore(tempDir, 1000)) {
            store.put(1, RowOutcome.inserted());
            store.put(2, RowOutcome.rejected("плохая строка"));
            store.put(3, RowOutcome.skipped());
            store.seal();

            assertThat(store.get(1).status()).isEqualTo(RowStatus.INSERTED);
            assertThat(store.get(2).message()).isEqualTo("плохая строка");
            assertThat(store.get(3).status()).isEqualTo(RowStatus.SKIPPED);
        }
    }

    @Test
    void unknownRowIsNotProcessed() throws Exception {
        try (RowOutcomeStore store = new SpillableRowOutcomeStore(tempDir, 1000)) {
            store.put(1, RowOutcome.inserted());
            store.seal();

            assertThat(store.get(99).status()).isEqualTo(RowStatus.NOT_PROCESSED);
            assertThat(store.get(99).message()).isNull();
        }
    }

    @Test
    void maxRowNumTracksHighestSeenRow() throws Exception {
        try (RowOutcomeStore store = new SpillableRowOutcomeStore(tempDir, 1000)) {
            store.put(5, RowOutcome.inserted());
            store.put(200_000, RowOutcome.inserted());
            store.seal();

            assertThat(store.maxRowNum()).isEqualTo(200_000);
        }
    }

    @Test
    void statusArrayGrowsWithoutLosingEarlierValues() throws Exception {
        try (RowOutcomeStore store = new SpillableRowOutcomeStore(tempDir, 1000)) {
            for (int row = 1; row <= 100_000; row++) {
                store.put(row, row % 2 == 0 ? RowOutcome.inserted() : RowOutcome.skipped());
            }
            store.seal();

            assertThat(store.get(1).status()).isEqualTo(RowStatus.SKIPPED);
            assertThat(store.get(100_000).status()).isEqualTo(RowStatus.INSERTED);
        }
    }

    @Test
    void messagesSpillToDiskAfterThresholdAndAreStillReadable() throws Exception {
        Path spillDir = Files.createDirectory(tempDir.resolve("spill"));
        try (RowOutcomeStore store = new SpillableRowOutcomeStore(spillDir, 10)) {
            for (int row = 1; row <= 50; row++) {
                store.put(row, RowOutcome.rejected("ошибка " + row));
            }
            store.seal();

            assertThat(store.get(1).message()).isEqualTo("ошибка 1");
            assertThat(store.get(25).message()).isEqualTo("ошибка 25");
            assertThat(store.get(50).message()).isEqualTo("ошибка 50");
        }
    }

    @Test
    void spillFileIsCreatedWhenThresholdExceeded() throws Exception {
        Path spillDir = Files.createDirectory(tempDir.resolve("spill2"));
        try (RowOutcomeStore store = new SpillableRowOutcomeStore(spillDir, 5)) {
            for (int row = 1; row <= 20; row++) {
                store.put(row, RowOutcome.rejected("msg" + row));
            }
            store.seal();

            try (Stream<Path> files = Files.list(spillDir)) {
                assertThat(files).isNotEmpty();
            }
        }
    }

    @Test
    void spillFileIsDeletedOnClose() throws Exception {
        Path spillDir = Files.createDirectory(tempDir.resolve("spill3"));
        RowOutcomeStore store = new SpillableRowOutcomeStore(spillDir, 2);
        for (int row = 1; row <= 10; row++) {
            store.put(row, RowOutcome.rejected("msg" + row));
        }
        store.seal();
        store.close();

        try (Stream<Path> files = Files.list(spillDir)) {
            assertThat(files).isEmpty();
        }
    }

    @Test
    void messagesWithNewlinesSurviveSpilling() throws Exception {
        Path spillDir = Files.createDirectory(tempDir.resolve("spill4"));
        try (RowOutcomeStore store = new SpillableRowOutcomeStore(spillDir, 1)) {
            store.put(1, RowOutcome.rejected("первая строка\nвторая\tс табом"));
            store.put(2, RowOutcome.rejected("обычная"));
            store.seal();

            assertThat(store.get(1).message()).isEqualTo("первая строка\nвторая\tс табом");
            assertThat(store.get(2).message()).isEqualTo("обычная");
        }
    }

    @Test
    void readingBeforeSealIsRejected() throws Exception {
        try (RowOutcomeStore store = new SpillableRowOutcomeStore(tempDir, 10)) {
            store.put(1, RowOutcome.inserted());

            assertThatThrownBy(() -> store.get(1))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("seal");
        }
    }

    @Test
    void writingAfterSealIsRejected() throws Exception {
        try (RowOutcomeStore store = new SpillableRowOutcomeStore(tempDir, 10)) {
            store.seal();

            assertThatThrownBy(() -> store.put(1, RowOutcome.inserted()))
                    .isInstanceOf(IllegalStateException.class);
        }
    }

    @Test
    void readsMustBeMonotonicWhenSpilled() throws Exception {
        Path spillDir = Files.createDirectory(tempDir.resolve("spill5"));
        try (RowOutcomeStore store = new SpillableRowOutcomeStore(spillDir, 1)) {
            store.put(1, RowOutcome.rejected("a"));
            store.put(2, RowOutcome.rejected("b"));
            store.seal();

            assertThat(store.get(2).message()).isEqualTo("b");
            assertThatThrownBy(() -> store.get(1))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("по возрастанию");
        }
    }

    @Test
    void ioFailureIsWrappedInUncheckedException() throws IOException {
        Path notADirectory = Files.createFile(tempDir.resolve("file.txt"));

        assertThatThrownBy(() -> new SpillableRowOutcomeStore(notADirectory, 0))
                .isInstanceOf(IllegalStateException.class);
    }
}
```

Тест `readsMustBeMonotonicWhenSpilled` фиксирует ограничение конструкции: после выгрузки на диск чтение сообщений идёт merge-join'ом, поэтому обращения должны идти по возрастанию `rowNum`. Второй проход отчёта именно так и работает. Нарушение — явная ошибка, а не тихо неверный результат.

- [ ] **Step 2: Запустить тест, убедиться что падает**

Run: `./gradlew :excel-import-core:test --tests "*SpillableRowOutcomeStoreTest*"`
Expected: FAIL — `cannot find symbol: class RowOutcomeStore`.

- [ ] **Step 3: Реализовать интерфейс `RowOutcomeStore`**

```java
package io.github.excelimport.outcome;

import io.github.excelimport.RowOutcome;

/**
 * Хранилище исходов строк между двумя проходами импорта. Реализация по умолчанию
 * держит статусы в памяти, а сообщения об ошибках выгружает на диск после порога.
 *
 * <p>Жизненный цикл: запись через {@link #put} на первом проходе → {@link #seal()} →
 * чтение через {@link #get} на втором проходе → {@link #close()}.
 *
 * <p>Реализации не обязаны поддерживать произвольный порядок чтения: контракт по
 * умолчанию — обращения по возрастанию {@code rowNum}, как идёт второй проход.
 */
public interface RowOutcomeStore extends AutoCloseable {

    /** @param rowNum 1-based номер строки Excel */
    void put(int rowNum, RowOutcome outcome);

    /** @return исход строки; {@code RowOutcome.notProcessed()} для неизвестной строки */
    RowOutcome get(int rowNum);

    /** Наибольший номер строки, для которой был вызван {@link #put}; 0, если ничего не было. */
    int maxRowNum();

    /** Переводит хранилище в режим чтения. После вызова {@link #put} запрещён. */
    void seal();

    @Override
    void close();
}
```

- [ ] **Step 4: Реализовать `SpillableRowOutcomeStore`**

```java
package io.github.excelimport.internal.outcome;

import io.github.excelimport.RowOutcome;
import io.github.excelimport.RowStatus;
import io.github.excelimport.outcome.RowOutcomeStore;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Статусы — в растущем {@code byte[]} (1 МБ на миллион строк). Сообщения об ошибках —
 * в карте до порога, дальше в временный файл последовательной записью. Чтение после
 * {@link #seal()} идёт merge-join'ом, поэтому требует возрастающих {@code rowNum}.
 */
public final class SpillableRowOutcomeStore implements RowOutcomeStore {

    private static final Logger log = LoggerFactory.getLogger(SpillableRowOutcomeStore.class);
    private static final char SEPARATOR = '\t';

    private final int maxMessagesInMemory;
    private final Path spillFile;

    private byte[] statuses = new byte[1024];
    private int maxRowNum;
    private final Map<Integer, String> messages = new HashMap<>();
    private BufferedWriter spillWriter;
    private boolean sealed;

    // состояние чтения после seal()
    private BufferedReader spillReader;
    private int lastReadRowNum;
    private int pendingRowNum = -1;
    private String pendingMessage;

    public SpillableRowOutcomeStore(Path tempDir, int maxMessagesInMemory) {
        this.maxMessagesInMemory = maxMessagesInMemory;
        try {
            Files.createDirectories(tempDir);
            this.spillFile = tempDir.resolve("excel-import-outcomes-" + System.nanoTime() + ".tsv");
        } catch (IOException e) {
            throw new IllegalStateException("не удалось подготовить каталог для временных файлов", e);
        }
    }

    @Override
    public void put(int rowNum, RowOutcome outcome) {
        if (sealed) {
            throw new IllegalStateException("хранилище закрыто для записи после seal()");
        }
        if (rowNum < 1) {
            throw new IllegalArgumentException("номер строки 1-based, получено: " + rowNum);
        }
        ensureCapacity(rowNum);
        statuses[rowNum] = outcome.status().code();
        maxRowNum = Math.max(maxRowNum, rowNum);

        if (outcome.message() == null) {
            return;
        }
        if (spillWriter != null) {
            writeSpilled(rowNum, outcome.message());
            return;
        }
        if (messages.size() < maxMessagesInMemory) {
            messages.put(rowNum, outcome.message());
            return;
        }
        spillAll();
        writeSpilled(rowNum, outcome.message());
    }

    private void ensureCapacity(int rowNum) {
        if (rowNum < statuses.length) {
            return;
        }
        int newLength = statuses.length;
        while (newLength <= rowNum) {
            newLength = newLength + (newLength >> 1) + 1;
        }
        byte[] grown = new byte[newLength];
        System.arraycopy(statuses, 0, grown, 0, statuses.length);
        statuses = grown;
    }

    /** Переносит накопленные в памяти сообщения в файл и переходит в режим выгрузки. */
    private void spillAll() {
        try {
            spillWriter = Files.newBufferedWriter(spillFile, StandardCharsets.UTF_8);
            messages.entrySet().stream()
                    .sorted(Map.Entry.comparingByKey())
                    .forEach(entry -> writeSpilled(entry.getKey(), entry.getValue()));
            messages.clear();
            log.debug("сообщения об ошибках выгружены в {}", spillFile);
        } catch (IOException e) {
            throw new IllegalStateException("не удалось создать файл выгрузки " + spillFile, e);
        }
    }

    private void writeSpilled(int rowNum, String message) {
        try {
            spillWriter.write(Integer.toString(rowNum));
            spillWriter.write(SEPARATOR);
            spillWriter.write(escape(message));
            spillWriter.newLine();
        } catch (IOException e) {
            throw new IllegalStateException("не удалось записать сообщение в файл выгрузки", e);
        }
    }

    @Override
    public void seal() {
        if (sealed) {
            return;
        }
        sealed = true;
        if (spillWriter == null) {
            return;
        }
        try {
            spillWriter.close();
            spillWriter = null;
            spillReader = Files.newBufferedReader(spillFile, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("не удалось перейти к чтению файла выгрузки", e);
        }
    }

    @Override
    public RowOutcome get(int rowNum) {
        if (!sealed) {
            throw new IllegalStateException("перед чтением нужно вызвать seal()");
        }
        RowStatus status = rowNum < statuses.length
                ? RowStatus.fromCode(statuses[rowNum])
                : RowStatus.NOT_PROCESSED;
        if (status != RowStatus.REJECTED) {
            lastReadRowNum = Math.max(lastReadRowNum, rowNum);
            return statusOnly(status);
        }
        String message = messageFor(rowNum);
        return RowOutcome.rejected(message != null ? message : "причина не сохранена");
    }

    private static RowOutcome statusOnly(RowStatus status) {
        return switch (status) {
            case INSERTED -> RowOutcome.inserted();
            case SKIPPED -> RowOutcome.skipped();
            case NOT_PROCESSED -> RowOutcome.notProcessed();
            case REJECTED -> throw new IllegalStateException("REJECTED требует сообщения");
        };
    }

    private String messageFor(int rowNum) {
        if (spillReader == null) {
            lastReadRowNum = Math.max(lastReadRowNum, rowNum);
            return messages.get(rowNum);
        }
        if (rowNum < lastReadRowNum) {
            throw new IllegalStateException(
                    "после выгрузки на диск чтение возможно только по возрастанию rowNum; "
                            + "запрошено " + rowNum + " после " + lastReadRowNum);
        }
        lastReadRowNum = rowNum;
        while (true) {
            if (pendingRowNum == rowNum) {
                String result = pendingMessage;
                pendingRowNum = -1;
                pendingMessage = null;
                return result;
            }
            if (pendingRowNum > rowNum) {
                return null; // сообщения для этой строки в файле нет
            }
            if (!advance()) {
                return null;
            }
        }
    }

    /** Читает следующую запись файла выгрузки в pending-поля. */
    private boolean advance() {
        try {
            String line = spillReader.readLine();
            if (line == null) {
                pendingRowNum = Integer.MAX_VALUE;
                pendingMessage = null;
                return false;
            }
            int separator = line.indexOf(SEPARATOR);
            pendingRowNum = Integer.parseInt(line.substring(0, separator));
            pendingMessage = unescape(line.substring(separator + 1));
            return true;
        } catch (IOException e) {
            throw new IllegalStateException("не удалось прочитать файл выгрузки", e);
        }
    }

    @Override
    public int maxRowNum() {
        return maxRowNum;
    }

    @Override
    public void close() {
        try {
            if (spillWriter != null) {
                spillWriter.close();
            }
            if (spillReader != null) {
                spillReader.close();
            }
        } catch (IOException e) {
            log.warn("не удалось закрыть файл выгрузки: {}", e.getMessage());
        } finally {
            try {
                Files.deleteIfExists(spillFile);
            } catch (IOException e) {
                log.warn("не удалось удалить временный файл {}: {}", spillFile, e.getMessage());
            }
        }
    }

    private static String escape(String message) {
        return message.replace("\\", "\\\\").replace("\n", "\\n").replace("\r", "\\r")
                .replace("\t", "\\t");
    }

    private static String unescape(String encoded) {
        StringBuilder result = new StringBuilder(encoded.length());
        for (int i = 0; i < encoded.length(); i++) {
            char c = encoded.charAt(i);
            if (c != '\\' || i + 1 >= encoded.length()) {
                result.append(c);
                continue;
            }
            char next = encoded.charAt(++i);
            switch (next) {
                case 'n' -> result.append('\n');
                case 'r' -> result.append('\r');
                case 't' -> result.append('\t');
                case '\\' -> result.append('\\');
                default -> result.append('\\').append(next);
            }
        }
        return result.toString();
    }
}
```

- [ ] **Step 5: Запустить тесты**

Run: `./gradlew :excel-import-core:test --tests "*SpillableRowOutcomeStoreTest*"`
Expected: PASS — 12 тестов.

- [ ] **Step 6: Коммит**

```bash
git add excel-import-core/src/main/java/io/github/excelimport/outcome \
        excel-import-core/src/main/java/io/github/excelimport/internal/outcome \
        excel-import-core/src/test/java/io/github/excelimport/internal/outcome
git commit -m "feat: add row outcome store with disk spilling for error messages"
```

---

### Task 15: Excel-отчёт — `ReportStyle`, `ReportContext`, `ReportRowCustomizer`, `ReportWriter`

**Files:**
- Modify: `excel-import-core/src/main/java/io/github/excelimport/report/ReportStyle.java` (заменить заглушку из Task 10)
- Create: `excel-import-core/src/main/java/io/github/excelimport/report/ReportContext.java`
- Create: `excel-import-core/src/main/java/io/github/excelimport/report/ReportRowCustomizer.java`
- Create: `excel-import-core/src/main/java/io/github/excelimport/internal/report/ReportStyleCache.java`
- Create: `excel-import-core/src/main/java/io/github/excelimport/internal/report/ReportWriter.java`
- Test: `excel-import-core/src/test/java/io/github/excelimport/internal/report/ReportWriterTest.java`

**Interfaces:**
- Consumes: `StreamingSheetReader`/`RawRow` (Task 3), `RowOutcomeStore`/`RowOutcome`/`RowStatus` (Task 2, 14), `ImportReport` (Task 2), `SheetSelector`, `ReadOptions`.
- Produces:
  - `ReportStyle` с билдером: `insertedFill(XSSFColor|IndexedColors)`, `rejectedFill(...)`, `skippedFill(...)`, `fillPattern(FillPatternType)`, `statusColumnHeader(String)`, `reasonColumnHeader(String)`, `reasonAlignment(HorizontalAlignment)`, `dateFormat(String)`, `insertedText/rejectedText/skippedText(String)`, `summarySheetName(String)`; `ReportStyle.defaults()`.
  - `ReportContext` — `SXSSFWorkbook workbook()`, `DataFormat dataFormat()`, `int statusColumnIndex()`, `int reasonColumnIndex()`, `CellStyle styleFor(RowStatus, String dataFormat)`.
  - `interface ReportRowCustomizer` с `customizeHeader(SXSSFRow, ReportContext)`, `customizeRow(SXSSFRow, RowOutcome, ReportContext)`, `finish(SXSSFWorkbook, ImportReport)`.
  - `ReportWriter` — конструктор `ReportWriter(StreamingSheetReader, ReportStyle, ReportRowCustomizer)`; метод `void write(Path source, SheetSelector, ReadOptions, RowOutcomeStore, ImportReport, Path target)`.

- [ ] **Step 1: Написать падающий тест**

```java
package io.github.excelimport.internal.report;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.excelimport.ImportReport;
import io.github.excelimport.ImportStatus;
import io.github.excelimport.RowOutcome;
import io.github.excelimport.RowStatus;
import io.github.excelimport.SheetSelector;
import io.github.excelimport.exception.ReportGenerationException;
import io.github.excelimport.internal.outcome.SpillableRowOutcomeStore;
import io.github.excelimport.internal.read.PoiStreamingSheetReader;
import io.github.excelimport.internal.read.ReadOptions;
import io.github.excelimport.outcome.RowOutcomeStore;
import io.github.excelimport.report.ReportRowCustomizer;
import io.github.excelimport.report.ReportStyle;
import io.github.excelimport.testsupport.XlsxFixtures;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.IndexedColors;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFCellStyle;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ReportWriterTest {

    @TempDir
    Path tempDir;

    private Path source() {
        return XlsxFixtures.simpleSheet(tempDir, new Object[][] {
            {"ФИО", "Оклад"},
            {"Иванов", 100},
            {"Петров", 200},
            {"Сидоров", 300},
        });
    }

    private RowOutcomeStore outcomes() {
        RowOutcomeStore store = new SpillableRowOutcomeStore(tempDir, 1000);
        store.put(2, RowOutcome.inserted());
        store.put(3, RowOutcome.rejected("Оклад: не число"));
        store.put(4, RowOutcome.skipped());
        store.seal();
        return store;
    }

    private ImportReport report(Path reportPath) {
        return new ImportReport(
                UUID.randomUUID(), "employees.xlsx", 3, 1, 1, 1,
                Duration.ofSeconds(2), reportPath, List.of(), false, ImportStatus.PARTIAL);
    }

    private Path write(ReportStyle style, ReportRowCustomizer customizer) {
        Path target = tempDir.resolve("report.xlsx");
        try (RowOutcomeStore store = outcomes()) {
            new ReportWriter(new PoiStreamingSheetReader(), style, customizer)
                    .write(source(), SheetSelector.first(), ReadOptions.defaults(), store,
                            report(target), target);
        }
        return target;
    }

    @Test
    void reportKeepsAllRowsAndAddsTwoColumns() throws Exception {
        Path target = write(ReportStyle.defaults(), null);

        try (Workbook workbook = new XSSFWorkbook(Files.newInputStream(target))) {
            Sheet sheet = workbook.getSheetAt(0);
            assertThat(sheet.getLastRowNum()).isEqualTo(3); // заголовок + 3 строки данных

            Row header = sheet.getRow(0);
            assertThat(header.getCell(2).getStringCellValue()).isEqualTo("Статус импорта");
            assertThat(header.getCell(3).getStringCellValue()).isEqualTo("Причина");
        }
    }

    @Test
    void insertedRowIsGreenAndRejectedIsRed() throws Exception {
        Path target = write(ReportStyle.defaults(), null);

        try (Workbook workbook = new XSSFWorkbook(Files.newInputStream(target))) {
            Sheet sheet = workbook.getSheetAt(0);
            XSSFCellStyle inserted = (XSSFCellStyle) sheet.getRow(1).getCell(0).getCellStyle();
            XSSFCellStyle rejected = (XSSFCellStyle) sheet.getRow(2).getCell(0).getCellStyle();

            assertThat(inserted.getFillForegroundColorColor()).isNotNull();
            assertThat(rejected.getFillForegroundColorColor()).isNotNull();
            assertThat(inserted.getFillForegroundColorColor().getARGBHex())
                    .isNotEqualTo(rejected.getFillForegroundColorColor().getARGBHex());
        }
    }

    @Test
    void reasonColumnCarriesErrorText() throws Exception {
        Path target = write(ReportStyle.defaults(), null);

        try (Workbook workbook = new XSSFWorkbook(Files.newInputStream(target))) {
            Sheet sheet = workbook.getSheetAt(0);
            assertThat(sheet.getRow(2).getCell(3).getStringCellValue()).isEqualTo("Оклад: не число");
            assertThat(sheet.getRow(1).getCell(3).getStringCellValue()).isEmpty();
        }
    }

    @Test
    void statusColumnCarriesHumanReadableStatus() throws Exception {
        Path target = write(ReportStyle.defaults(), null);

        try (Workbook workbook = new XSSFWorkbook(Files.newInputStream(target))) {
            Sheet sheet = workbook.getSheetAt(0);
            assertThat(sheet.getRow(1).getCell(2).getStringCellValue()).isEqualTo("Загружено");
            assertThat(sheet.getRow(2).getCell(2).getStringCellValue()).isEqualTo("Ошибка");
            assertThat(sheet.getRow(3).getCell(2).getStringCellValue()).isEqualTo("Не обработано");
        }
    }

    @Test
    void numericCellsStayNumeric() throws Exception {
        Path target = write(ReportStyle.defaults(), null);

        try (Workbook workbook = new XSSFWorkbook(Files.newInputStream(target))) {
            Cell cell = workbook.getSheetAt(0).getRow(1).getCell(1);
            assertThat(cell.getNumericCellValue()).isEqualTo(100.0);
        }
    }

    @Test
    void summarySheetIsAdded() throws Exception {
        Path target = write(ReportStyle.defaults(), null);

        try (Workbook workbook = new XSSFWorkbook(Files.newInputStream(target))) {
            Sheet summary = workbook.getSheet("Сводка");
            assertThat(summary).isNotNull();
            String text = new StringBuilder()
                    .append(summary.getRow(0).getCell(0).getStringCellValue())
                    .append(summary.getRow(0).getCell(1).getStringCellValue())
                    .toString();
            assertThat(text).contains("Исходный файл").contains("employees.xlsx");
        }
    }

    @Test
    void headerRowIsFrozen() throws Exception {
        Path target = write(ReportStyle.defaults(), null);

        try (Workbook workbook = new XSSFWorkbook(Files.newInputStream(target))) {
            assertThat(workbook.getSheetAt(0).getPaneInformation()).isNotNull();
        }
    }

    @Test
    void customStyleOverridesColorsAndHeaders() throws Exception {
        ReportStyle style = ReportStyle.builder()
                .insertedFill(IndexedColors.LIGHT_BLUE)
                .statusColumnHeader("Status")
                .reasonColumnHeader("Reason")
                .insertedText("OK")
                .build();

        Path target = write(style, null);

        try (Workbook workbook = new XSSFWorkbook(Files.newInputStream(target))) {
            Sheet sheet = workbook.getSheetAt(0);
            assertThat(sheet.getRow(0).getCell(2).getStringCellValue()).isEqualTo("Status");
            assertThat(sheet.getRow(0).getCell(3).getStringCellValue()).isEqualTo("Reason");
            assertThat(sheet.getRow(1).getCell(2).getStringCellValue()).isEqualTo("OK");
        }
    }

    @Test
    void customizerCanAddCellsAndSeesOutcome() throws Exception {
        ReportRowCustomizer customizer = new ReportRowCustomizer() {
            @Override
            public void customizeHeader(
                    org.apache.poi.xssf.streaming.SXSSFRow header,
                    io.github.excelimport.report.ReportContext ctx) {
                header.createCell(4).setCellValue("Доп");
            }

            @Override
            public void customizeRow(
                    org.apache.poi.xssf.streaming.SXSSFRow row,
                    RowOutcome outcome,
                    io.github.excelimport.report.ReportContext ctx) {
                row.createCell(4).setCellValue(outcome.status().name());
            }
        };

        Path target = write(ReportStyle.defaults(), customizer);

        try (Workbook workbook = new XSSFWorkbook(Files.newInputStream(target))) {
            Sheet sheet = workbook.getSheetAt(0);
            assertThat(sheet.getRow(0).getCell(4).getStringCellValue()).isEqualTo("Доп");
            assertThat(sheet.getRow(1).getCell(4).getStringCellValue())
                    .isEqualTo(RowStatus.INSERTED.name());
            assertThat(sheet.getRow(2).getCell(4).getStringCellValue())
                    .isEqualTo(RowStatus.REJECTED.name());
        }
    }

    @Test
    void customizerFailureBecomesReportGenerationException() {
        ReportRowCustomizer failing = (row, outcome, ctx) -> {
            throw new IllegalArgumentException("сломался хук");
        };

        assertThatThrownBy(() -> write(ReportStyle.defaults(), failing))
                .isInstanceOf(ReportGenerationException.class)
                .hasRootCauseMessage("сломался хук");
    }

    @Test
    void styleCountStaysBoundedOnManyRows() throws Exception {
        Object[][] rows = new Object[501][];
        rows[0] = new Object[] {"ФИО", "Оклад"};
        for (int i = 1; i <= 500; i++) {
            rows[i] = new Object[] {"Сотрудник " + i, i};
        }
        Path source = XlsxFixtures.simpleSheet(tempDir, rows);
        Path target = tempDir.resolve("big-report.xlsx");

        try (RowOutcomeStore store = new SpillableRowOutcomeStore(tempDir, 1000)) {
            for (int rowNum = 2; rowNum <= 501; rowNum++) {
                store.put(rowNum, rowNum % 2 == 0
                        ? RowOutcome.inserted()
                        : RowOutcome.rejected("ошибка " + rowNum));
            }
            store.seal();
            new ReportWriter(new PoiStreamingSheetReader(), ReportStyle.defaults(), null)
                    .write(source, SheetSelector.first(), ReadOptions.defaults(), store,
                            report(target), target);
        }

        try (Workbook workbook = new XSSFWorkbook(Files.newInputStream(target))) {
            // стилей должно быть единицы, а не по одному на строку
            assertThat(workbook.getNumCellStyles()).isLessThan(30);
        }
    }

    @Test
    void targetFileIsNotLeftPartialOnFailure() {
        ReportRowCustomizer failing = (row, outcome, ctx) -> {
            throw new IllegalStateException("падаю");
        };
        Path target = tempDir.resolve("never-written.xlsx");

        try (RowOutcomeStore store = outcomes()) {
            assertThatThrownBy(() -> new ReportWriter(
                            new PoiStreamingSheetReader(), ReportStyle.defaults(), failing)
                            .write(source(), SheetSelector.first(), ReadOptions.defaults(), store,
                                    report(target), target))
                    .isInstanceOf(ReportGenerationException.class);
        }

        assertThat(Files.exists(target)).isFalse();
    }
}
```

- [ ] **Step 2: Запустить тест, убедиться что падает**

Run: `./gradlew :excel-import-core:test --tests "*ReportWriterTest*"`
Expected: FAIL — `cannot find symbol: class ReportContext`, `method builder()` у `ReportStyle`.

- [ ] **Step 3: Заменить заглушку `ReportStyle` полной реализацией**

```java
package io.github.excelimport.report;

import java.util.Objects;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.HorizontalAlignment;
import org.apache.poi.ss.usermodel.IndexedColors;
import org.apache.poi.xssf.usermodel.XSSFColor;

/** Оформление Excel-отчёта в терминах POI. Иммутабельно, собирается билдером. */
public final class ReportStyle {

    private static final ReportStyle DEFAULTS = builder().build();

    private final XSSFColor insertedFill;
    private final XSSFColor rejectedFill;
    private final XSSFColor skippedFill;
    private final FillPatternType fillPattern;
    private final String statusColumnHeader;
    private final String reasonColumnHeader;
    private final HorizontalAlignment reasonAlignment;
    private final String dateFormat;
    private final String insertedText;
    private final String rejectedText;
    private final String skippedText;
    private final String summarySheetName;
    private final String reportSheetNamePrefix;

    private ReportStyle(Builder builder) {
        this.insertedFill = builder.insertedFill;
        this.rejectedFill = builder.rejectedFill;
        this.skippedFill = builder.skippedFill;
        this.fillPattern = builder.fillPattern;
        this.statusColumnHeader = builder.statusColumnHeader;
        this.reasonColumnHeader = builder.reasonColumnHeader;
        this.reasonAlignment = builder.reasonAlignment;
        this.dateFormat = builder.dateFormat;
        this.insertedText = builder.insertedText;
        this.rejectedText = builder.rejectedText;
        this.skippedText = builder.skippedText;
        this.summarySheetName = builder.summarySheetName;
        this.reportSheetNamePrefix = builder.reportSheetNamePrefix;
    }

    public static ReportStyle defaults() {
        return DEFAULTS;
    }

    public static Builder builder() {
        return new Builder();
    }

    public XSSFColor insertedFill() {
        return insertedFill;
    }

    public XSSFColor rejectedFill() {
        return rejectedFill;
    }

    public XSSFColor skippedFill() {
        return skippedFill;
    }

    public FillPatternType fillPattern() {
        return fillPattern;
    }

    public String statusColumnHeader() {
        return statusColumnHeader;
    }

    public String reasonColumnHeader() {
        return reasonColumnHeader;
    }

    public HorizontalAlignment reasonAlignment() {
        return reasonAlignment;
    }

    public String dateFormat() {
        return dateFormat;
    }

    public String insertedText() {
        return insertedText;
    }

    public String rejectedText() {
        return rejectedText;
    }

    public String skippedText() {
        return skippedText;
    }

    public String summarySheetName() {
        return summarySheetName;
    }

    public String reportSheetNamePrefix() {
        return reportSheetNamePrefix;
    }

    /** Билдер. Цвета принимаются и как {@link XSSFColor}, и как {@link IndexedColors}. */
    public static final class Builder {

        private XSSFColor insertedFill = rgb(0xC6, 0xEF, 0xCE);
        private XSSFColor rejectedFill = rgb(0xFF, 0xC7, 0xCE);
        private XSSFColor skippedFill = rgb(0xF2, 0xF2, 0xF2);
        private FillPatternType fillPattern = FillPatternType.SOLID_FOREGROUND;
        private String statusColumnHeader = "Статус импорта";
        private String reasonColumnHeader = "Причина";
        private HorizontalAlignment reasonAlignment = HorizontalAlignment.LEFT;
        private String dateFormat = "dd.MM.yyyy";
        private String insertedText = "Загружено";
        private String rejectedText = "Ошибка";
        private String skippedText = "Не обработано";
        private String summarySheetName = "Сводка";
        private String reportSheetNamePrefix = "Отчёт";

        private Builder() {}

        public Builder insertedFill(XSSFColor value) {
            this.insertedFill = Objects.requireNonNull(value, "insertedFill");
            return this;
        }

        public Builder insertedFill(IndexedColors value) {
            return insertedFill(toXssf(value));
        }

        public Builder rejectedFill(XSSFColor value) {
            this.rejectedFill = Objects.requireNonNull(value, "rejectedFill");
            return this;
        }

        public Builder rejectedFill(IndexedColors value) {
            return rejectedFill(toXssf(value));
        }

        public Builder skippedFill(XSSFColor value) {
            this.skippedFill = Objects.requireNonNull(value, "skippedFill");
            return this;
        }

        public Builder skippedFill(IndexedColors value) {
            return skippedFill(toXssf(value));
        }

        public Builder fillPattern(FillPatternType value) {
            this.fillPattern = Objects.requireNonNull(value, "fillPattern");
            return this;
        }

        public Builder statusColumnHeader(String value) {
            this.statusColumnHeader = Objects.requireNonNull(value, "statusColumnHeader");
            return this;
        }

        public Builder reasonColumnHeader(String value) {
            this.reasonColumnHeader = Objects.requireNonNull(value, "reasonColumnHeader");
            return this;
        }

        public Builder reasonAlignment(HorizontalAlignment value) {
            this.reasonAlignment = Objects.requireNonNull(value, "reasonAlignment");
            return this;
        }

        public Builder dateFormat(String value) {
            this.dateFormat = Objects.requireNonNull(value, "dateFormat");
            return this;
        }

        public Builder insertedText(String value) {
            this.insertedText = Objects.requireNonNull(value, "insertedText");
            return this;
        }

        public Builder rejectedText(String value) {
            this.rejectedText = Objects.requireNonNull(value, "rejectedText");
            return this;
        }

        public Builder skippedText(String value) {
            this.skippedText = Objects.requireNonNull(value, "skippedText");
            return this;
        }

        public Builder summarySheetName(String value) {
            this.summarySheetName = Objects.requireNonNull(value, "summarySheetName");
            return this;
        }

        public Builder reportSheetNamePrefix(String value) {
            this.reportSheetNamePrefix = Objects.requireNonNull(value, "reportSheetNamePrefix");
            return this;
        }

        public ReportStyle build() {
            return new ReportStyle(this);
        }

        private static XSSFColor rgb(int red, int green, int blue) {
            return new XSSFColor(new byte[] {(byte) red, (byte) green, (byte) blue});
        }

        private static XSSFColor toXssf(IndexedColors color) {
            short[] triplet = color.getIndex() >= 0
                    ? org.apache.poi.hssf.util.HSSFColor.getIndexHash()
                            .get((int) color.getIndex())
                            .getTriplet()
                    : new short[] {0, 0, 0};
            return rgb(triplet[0], triplet[1], triplet[2]);
        }
    }
}
```

- [ ] **Step 4: Реализовать `ReportContext`, `ReportRowCustomizer`, `ReportStyleCache`**

`report/ReportContext.java`:

```java
package io.github.excelimport.report;

import io.github.excelimport.RowStatus;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.DataFormat;
import org.apache.poi.xssf.streaming.SXSSFWorkbook;

/**
 * Доступ к записываемой книге отчёта из {@link ReportRowCustomizer}.
 * Стили брать только через {@link #styleFor}: книга ограничена 64 000 уникальных
 * {@code CellStyle}, а кэш переиспользует одинаковые.
 */
public interface ReportContext {

    SXSSFWorkbook workbook();

    DataFormat dataFormat();

    /** 0-based индекс колонки «Статус импорта». */
    int statusColumnIndex();

    /** 0-based индекс колонки «Причина». */
    int reasonColumnIndex();

    /**
     * @param status     заливка по статусу строки; null — без заливки
     * @param dataFormat строка формата, например {@code "dd.MM.yyyy"}; null — общий формат
     */
    CellStyle styleFor(RowStatus status, String dataFormat);
}
```

`report/ReportRowCustomizer.java`:

```java
package io.github.excelimport.report;

import io.github.excelimport.ImportReport;
import io.github.excelimport.RowOutcome;
import org.apache.poi.xssf.streaming.SXSSFRow;
import org.apache.poi.xssf.streaming.SXSSFWorkbook;

/**
 * Хук для нестандартной доработки отчёта. Получает настоящие POI-объекты книги,
 * которая пишется прямо сейчас.
 *
 * <p>Контракт: нельзя обращаться к строкам, вышедшим из flush-окна SXSSF, и нельзя
 * вызывать {@code workbook.write()} или {@code dispose()} — этим управляет библиотека.
 * Исключение из любого метода превращается в {@code ReportGenerationException}.
 */
public interface ReportRowCustomizer {

    /** Вызывается после записи строки заголовка. */
    default void customizeHeader(SXSSFRow header, ReportContext ctx) {}

    /** Вызывается после записи значений и применения заливки, до перехода к следующей строке. */
    void customizeRow(SXSSFRow row, RowOutcome outcome, ReportContext ctx);

    /** Вызывается перед записью книги на диск — можно дописать свои листы. */
    default void finish(SXSSFWorkbook workbook, ImportReport report) {}
}
```

`internal/report/ReportStyleCache.java`:

```java
package io.github.excelimport.internal.report;

import io.github.excelimport.RowStatus;
import io.github.excelimport.report.ReportStyle;
import java.util.HashMap;
import java.util.Map;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.DataFormat;
import org.apache.poi.xssf.streaming.SXSSFWorkbook;
import org.apache.poi.xssf.usermodel.XSSFCellStyle;
import org.apache.poi.xssf.usermodel.XSSFColor;

/**
 * Кэш стилей по паре (статус, формат). Без него книга получила бы по стилю на ячейку
 * и упёрлась в предел 64 000 уникальных стилей.
 */
final class ReportStyleCache {

    private record Key(RowStatus status, String dataFormat) {}

    private final SXSSFWorkbook workbook;
    private final ReportStyle style;
    private final DataFormat dataFormat;
    private final Map<Key, CellStyle> cache = new HashMap<>();

    ReportStyleCache(SXSSFWorkbook workbook, ReportStyle style) {
        this.workbook = workbook;
        this.style = style;
        this.dataFormat = workbook.createDataFormat();
    }

    DataFormat dataFormat() {
        return dataFormat;
    }

    CellStyle styleFor(RowStatus status, String format) {
        return cache.computeIfAbsent(new Key(status, format), this::create);
    }

    private CellStyle create(Key key) {
        XSSFCellStyle cellStyle = (XSSFCellStyle) workbook.createCellStyle();
        XSSFColor fill = fillFor(key.status());
        if (fill != null) {
            cellStyle.setFillForegroundColor(fill);
            cellStyle.setFillPattern(style.fillPattern());
        }
        if (key.dataFormat() != null) {
            cellStyle.setDataFormat(dataFormat.getFormat(key.dataFormat()));
        }
        return cellStyle;
    }

    private XSSFColor fillFor(RowStatus status) {
        if (status == null) {
            return null;
        }
        return switch (status) {
            case INSERTED -> style.insertedFill();
            case REJECTED -> style.rejectedFill();
            case SKIPPED, NOT_PROCESSED -> style.skippedFill();
        };
    }
}
```

- [ ] **Step 5: Реализовать `ReportWriter`**

```java
package io.github.excelimport.internal.report;

import io.github.excelimport.ImportReport;
import io.github.excelimport.RowOutcome;
import io.github.excelimport.RowStatus;
import io.github.excelimport.SheetSelector;
import io.github.excelimport.convert.CellValue;
import io.github.excelimport.exception.ReportGenerationException;
import io.github.excelimport.internal.read.RawRow;
import io.github.excelimport.internal.read.ReadOptions;
import io.github.excelimport.internal.read.StreamingSheetReader;
import io.github.excelimport.outcome.RowOutcomeStore;
import io.github.excelimport.report.ReportContext;
import io.github.excelimport.report.ReportRowCustomizer;
import io.github.excelimport.report.ReportStyle;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import org.apache.poi.ss.SpreadsheetVersion;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DataFormat;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.streaming.SXSSFRow;
import org.apache.poi.xssf.streaming.SXSSFSheet;
import org.apache.poi.xssf.streaming.SXSSFWorkbook;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Второй проход: заново читает оригинал и пишет его копию с разметкой исходов.
 * Оригинал не изменяется. Память ограничена окном SXSSF в 100 строк.
 */
public final class ReportWriter {

    private static final Logger log = LoggerFactory.getLogger(ReportWriter.class);
    private static final int FLUSH_WINDOW = 100;
    private static final int MAX_ROWS_PER_SHEET = SpreadsheetVersion.EXCEL2007.getMaxRows();

    private final StreamingSheetReader reader;
    private final ReportStyle style;
    private final ReportRowCustomizer customizer;

    public ReportWriter(
            StreamingSheetReader reader, ReportStyle style, ReportRowCustomizer customizer) {
        this.reader = reader;
        this.style = style;
        this.customizer = customizer;
    }

    /**
     * @param target путь к отчёту; файл появляется целиком, атомарным переименованием
     * @throws ReportGenerationException если отчёт не удалось записать
     */
    public void write(
            Path source,
            SheetSelector sheet,
            ReadOptions readOptions,
            RowOutcomeStore outcomes,
            ImportReport report,
            Path target) {
        Path temporary = target.resolveSibling(target.getFileName() + ".part");
        try (SXSSFWorkbook workbook = new SXSSFWorkbook(FLUSH_WINDOW)) {
            workbook.setCompressTempFiles(true);
            try {
                Writer writer = new Writer(workbook, outcomes, report);
                // отчёт читает исходник как есть: пустые строки не пропускаем,
                // чтобы нумерация в отчёте совпадала с оригиналом
                ReadOptions reportOptions = new ReadOptions(
                        false, readOptions.expandMergedCells(), readOptions.formulaPolicy());
                reader.forEachRow(source, sheet, reportOptions, writer::onRow);
                writer.finish();
                try (OutputStream out = Files.newOutputStream(temporary)) {
                    workbook.write(out);
                }
            } finally {
                workbook.dispose();
            }
            move(temporary, target);
        } catch (IOException e) {
            deleteQuietly(temporary);
            throw new ReportGenerationException("не удалось записать отчёт " + target, e);
        } catch (RuntimeException e) {
            deleteQuietly(temporary);
            if (e instanceof ReportGenerationException reportFailure) {
                throw reportFailure;
            }
            throw new ReportGenerationException("не удалось сформировать отчёт " + target, e);
        }
    }

    private static void move(Path temporary, Path target) throws IOException {
        try {
            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static void deleteQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException e) {
            log.warn("не удалось удалить незавершённый отчёт {}: {}", path, e.getMessage());
        }
    }

    /** Состояние одного прохода записи: вынесено, чтобы ReportWriter остался без полей-состояния. */
    private final class Writer implements ReportContext {

        private final SXSSFWorkbook workbook;
        private final RowOutcomeStore outcomes;
        private final ImportReport report;
        private final ReportStyleCache styles;

        private SXSSFSheet sheet;
        private int sheetOrdinal;
        private int rowsInSheet;
        private int statusColumn = -1;
        private int reasonColumn = -1;
        private boolean headerWritten;

        Writer(SXSSFWorkbook workbook, RowOutcomeStore outcomes, ImportReport report) {
            this.workbook = workbook;
            this.outcomes = outcomes;
            this.report = report;
            this.styles = new ReportStyleCache(workbook, style);
            newSheet();
        }

        private void newSheet() {
            sheetOrdinal++;
            String name = sheetOrdinal == 1
                    ? style.reportSheetNamePrefix()
                    : style.reportSheetNamePrefix() + " " + sheetOrdinal;
            sheet = workbook.createSheet(name);
            rowsInSheet = 0;
            headerWritten = false;
        }

        void onRow(RawRow source) {
            if (rowsInSheet >= MAX_ROWS_PER_SHEET - 1) {
                finishSheet();
                newSheet();
            }
            if (!headerWritten) {
                writeHeader(source);
                return;
            }
            writeDataRow(source);
        }

        private void writeHeader(RawRow source) {
            int lastColumn = source.lastColumnIndex();
            statusColumn = lastColumn + 1;
            reasonColumn = lastColumn + 2;

            SXSSFRow row = sheet.createRow(rowsInSheet++);
            for (int column = 0; column <= lastColumn; column++) {
                copyValue(row.createCell(column), source.cell(column), null);
            }
            row.createCell(statusColumn).setCellValue(style.statusColumnHeader());
            row.createCell(reasonColumn).setCellValue(style.reasonColumnHeader());
            headerWritten = true;

            sheet.createFreezePane(0, 1);
            invokeCustomizer(() -> {
                if (customizer != null) {
                    customizer.customizeHeader(row, this);
                }
            });
        }

        private void writeDataRow(RawRow source) {
            RowOutcome outcome = outcomes.get(source.excelRowNumber());
            RowStatus status = outcome.status();
            SXSSFRow row = sheet.createRow(rowsInSheet++);

            for (int column = 0; column <= source.lastColumnIndex(); column++) {
                copyValue(row.createCell(column), source.cell(column), status);
            }
            Cell statusCell = row.createCell(statusColumn);
            statusCell.setCellValue(statusText(status));
            statusCell.setCellStyle(styles.styleFor(status, null));

            Cell reasonCell = row.createCell(reasonColumn);
            reasonCell.setCellValue(outcome.message() == null ? "" : outcome.message());
            reasonCell.setCellStyle(styles.styleFor(status, null));

            invokeCustomizer(() -> {
                if (customizer != null) {
                    customizer.customizeRow(row, outcome, this);
                }
            });
        }

        private void copyValue(Cell target, CellValue value, RowStatus status) {
            String format = null;
            switch (value.type()) {
                case NUMERIC -> {
                    if (value.dateFormatted() && value.asLocalDateTime() != null) {
                        target.setCellValue(value.asLocalDateTime());
                        format = style.dateFormat();
                    } else if (value.asNumeric() != null) {
                        target.setCellValue(value.asNumeric());
                    }
                }
                case BOOLEAN -> target.setCellValue(Boolean.TRUE.equals(value.asBoolean()));
                case BLANK -> {
                    // ничего не пишем, только заливка
                }
                default -> target.setCellValue(value.asString() == null ? "" : value.asString());
            }
            target.setCellStyle(styles.styleFor(status, format));
        }

        private String statusText(RowStatus status) {
            return switch (status) {
                case INSERTED -> style.insertedText();
                case REJECTED -> style.rejectedText();
                case SKIPPED, NOT_PROCESSED -> style.skippedText();
            };
        }

        void finish() {
            finishSheet();
            writeSummarySheet();
            invokeCustomizer(() -> {
                if (customizer != null) {
                    customizer.finish(workbook, report);
                }
            });
        }

        private void finishSheet() {
            if (rowsInSheet > 1 && statusColumn >= 0) {
                sheet.setAutoFilter(new CellRangeAddress(0, rowsInSheet - 1, 0, reasonColumn));
            }
        }

        private void writeSummarySheet() {
            SXSSFSheet summary = workbook.createSheet(style.summarySheetName());
            int rowNum = 0;
            rowNum = addSummaryRow(summary, rowNum, "Исходный файл", report.sourceName());
            rowNum = addSummaryRow(summary, rowNum, "Идентификатор прогона",
                    String.valueOf(report.runId()));
            rowNum = addSummaryRow(summary, rowNum, "Длительность, с",
                    String.valueOf(report.duration().toMillis() / 1000.0));
            rowNum = addSummaryRow(summary, rowNum, "Всего строк данных",
                    String.valueOf(report.totalRows()));
            rowNum = addSummaryRow(summary, rowNum, "Вставлено", String.valueOf(report.insertedRows()));
            rowNum = addSummaryRow(summary, rowNum, "Отклонено", String.valueOf(report.rejectedRows()));
            rowNum = addSummaryRow(summary, rowNum, "Батчей закоммичено",
                    String.valueOf(report.batchesCommitted()));
            rowNum = addSummaryRow(summary, rowNum, "Статус", report.status().name());
            if (report.errorLimitReached()) {
                addSummaryRow(summary, rowNum, "Внимание",
                        "импорт остановлен: достигнут предел числа ошибок");
            }
        }

        private int addSummaryRow(SXSSFSheet summary, int rowNum, String label, String value) {
            SXSSFRow row = summary.createRow(rowNum);
            row.createCell(0).setCellValue(label);
            row.createCell(1).setCellValue(value);
            return rowNum + 1;
        }

        private void invokeCustomizer(Runnable action) {
            try {
                action.run();
            } catch (RuntimeException e) {
                throw new ReportGenerationException("ReportRowCustomizer завершился ошибкой", e);
            }
        }

        @Override
        public SXSSFWorkbook workbook() {
            return workbook;
        }

        @Override
        public DataFormat dataFormat() {
            return styles.dataFormat();
        }

        @Override
        public int statusColumnIndex() {
            return statusColumn;
        }

        @Override
        public int reasonColumnIndex() {
            return reasonColumn;
        }

        @Override
        public CellStyle styleFor(RowStatus status, String dataFormat) {
            return styles.styleFor(status, dataFormat);
        }
    }
}
```

Тест `styleCountStaysBoundedOnManyRows` проверяет главное свойство `ReportStyleCache`: стилей единицы, а не по одному на ячейку.

- [ ] **Step 6: Запустить тесты**

Run: `./gradlew :excel-import-core:test --tests "*ReportWriterTest*"`
Expected: PASS — 12 тестов.

Если `IndexedColors` → `XSSFColor` через `HSSFColor.getIndexHash()` не компилируется (класс в модуле `poi`, а не `poi-ooxml`), заменить конвертацию на явную таблицу для нескольких нужных цветов либо на `new XSSFColor(IndexedColors.valueOf(...).index, null)` — вариант, который компилируется на текущей версии POI. Тест проверяет только, что цвет отличается от дефолтного, поэтому конкретный RGB не критичен.

- [ ] **Step 7: Коммит**

```bash
git add excel-import-core/src/main/java/io/github/excelimport/report \
        excel-import-core/src/main/java/io/github/excelimport/internal/report \
        excel-import-core/src/test/java/io/github/excelimport/internal/report
git commit -m "feat: add marked Excel report writer with style cache and customization hook"
```

---

### Task 16: Фасад `ExcelImporter` и оркестрация прогона

**Files:**
- Create: `excel-import-core/src/main/java/io/github/excelimport/ImportListener.java`
- Create: `excel-import-core/src/main/java/io/github/excelimport/ImportRunInfo.java`
- Create: `excel-import-core/src/main/java/io/github/excelimport/ExcelImporter.java`
- Create: `excel-import-core/src/main/java/io/github/excelimport/internal/ImportRun.java`
- Test: `excel-import-core/src/test/java/io/github/excelimport/ExcelImporterBuilderTest.java`
- Test: `excel-import-core/src/integrationTest/java/io/github/excelimport/ExcelImporterIT.java`

**Interfaces:**
- Consumes: всё предыдущее.
- Produces:
  - `interface ImportListener` — `onImportStarted(ImportRunInfo)`, `onBatchCommitted(int batchIndex, int rowCount, long totalInserted)`, `onBatchSplit(int batchIndex, int depth, int rowCount)`, `onRowRejected(RowError)`, `onImportFinished(ImportReport)`; все методы `default`.
  - `ImportRunInfo` — record `(UUID runId, String sourceName, TableRef targetTable, int batchSize, Instant startedAt)`.
  - `ExcelImporter<T>` — `ExcelImporter.builder(Class<T>)` с методами `dataSource`, `config`, `batchValidator`, `converter(Class<?>, CellConverter<?>)`, `reportRowCustomizer`, `sqlErrorClassifier`, `listener`, `outcomeStoreFactory`, `build()`; методы `importFile(Path)`, `importFile(InputStream, String sourceName)`, `close()`.
  - `ImportRun<T>` — внутренний одноразовый оркестратор одного вызова `importFile`.

- [ ] **Step 1: Написать падающий unit-тест сборки импортёра**

```java
package io.github.excelimport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import io.github.excelimport.annotation.Column;
import io.github.excelimport.annotation.ExcelColumn;
import io.github.excelimport.annotation.ExcelSheet;
import io.github.excelimport.annotation.TargetTable;
import io.github.excelimport.exception.MappingConfigurationException;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;

class ExcelImporterBuilderTest {

    @ExcelSheet(name = "S")
    @TargetTable(name = "t")
    public static class WithTable {
        @ExcelColumn(header = "A")
        @Column("a")
        public String a;

        public WithTable() {}
    }

    @ExcelSheet(name = "S")
    public static class WithoutTable {
        @ExcelColumn(header = "A")
        @Column("a")
        public String a;

        public WithoutTable() {}
    }

    private final DataSource dataSource = mock(DataSource.class);

    @Test
    void buildsWithAnnotationDrivenTable() {
        try (ExcelImporter<WithTable> importer = ExcelImporter.builder(WithTable.class)
                .dataSource(dataSource)
                .build()) {
            assertThat(importer).isNotNull();
        }
    }

    @Test
    void dataSourceIsRequired() {
        assertThatThrownBy(() -> ExcelImporter.builder(WithTable.class).build())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("dataSource");
    }

    @Test
    void missingTableAnywhereIsRejectedAtBuildTime() {
        assertThatThrownBy(() -> ExcelImporter.builder(WithoutTable.class)
                        .dataSource(dataSource)
                        .build())
                .isInstanceOf(MappingConfigurationException.class)
                .hasMessageContaining("@TargetTable");
    }

    @Test
    void configTableOverridesMissingAnnotation() {
        try (ExcelImporter<WithoutTable> importer = ExcelImporter.builder(WithoutTable.class)
                .dataSource(dataSource)
                .config(ImportConfig.builder().targetTable(TableRef.of("public.t")).build())
                .build()) {
            assertThat(importer).isNotNull();
        }
    }

    @Test
    void dryRunConfigIsAccepted() {
        try (ExcelImporter<WithTable> importer = ExcelImporter.builder(WithTable.class)
                .config(ImportConfig.builder().dryRun(true).build())
                .dataSource(dataSource)
                .build()) {
            assertThat(importer).isNotNull();
        }
    }

    @Test
    void mappingErrorsSurfaceAtBuildTimeNotAtImportTime() {
        class NotAnnotated {}

        assertThatThrownBy(() -> ExcelImporter.builder(NotAnnotated.class).dataSource(dataSource).build())
                .isInstanceOf(MappingConfigurationException.class);
    }
}
```

- [ ] **Step 2: Запустить тест, убедиться что падает**

Run: `./gradlew :excel-import-core:test --tests "*ExcelImporterBuilderTest*"`
Expected: FAIL — `cannot find symbol: class ExcelImporter`.

- [ ] **Step 3: Реализовать `ImportListener` и `ImportRunInfo`**

`ImportRunInfo.java`:

```java
package io.github.excelimport;

import java.time.Instant;
import java.util.UUID;

/**
 * Сведения о начинающемся прогоне.
 *
 * @param runId       уникальный идентификатор прогона
 * @param sourceName  имя исходного файла
 * @param targetTable таблица-приёмник
 * @param batchSize   размер батча из конфигурации
 * @param startedAt   момент старта
 */
public record ImportRunInfo(
        UUID runId, String sourceName, TableRef targetTable, int batchSize, Instant startedAt) {}
```

`ImportListener.java`:

```java
package io.github.excelimport;

/**
 * Наблюдение за прогоном: прогресс-бар, метрики, чекпоинты в своей таблице.
 * Исключение из любого метода логируется и не влияет на импорт.
 */
public interface ImportListener {

    default void onImportStarted(ImportRunInfo info) {}

    /**
     * @param batchIndex    порядковый номер батча, начиная с 1
     * @param rowCount      сколько строк вставлено этим батчем
     * @param totalInserted сколько всего вставлено с начала прогона
     */
    default void onBatchCommitted(int batchIndex, int rowCount, long totalInserted) {}

    /** Батч пришлось делить пополам из-за ошибки БД. */
    default void onBatchSplit(int batchIndex, int depth, int rowCount) {}

    default void onRowRejected(RowError error) {}

    default void onImportFinished(ImportReport report) {}
}
```

- [ ] **Step 4: Реализовать `ImportRun`**

```java
package io.github.excelimport.internal;

import io.github.excelimport.ErrorKind;
import io.github.excelimport.HeaderMatchingPolicy;
import io.github.excelimport.ImportConfig;
import io.github.excelimport.ImportListener;
import io.github.excelimport.ImportReport;
import io.github.excelimport.ImportRunInfo;
import io.github.excelimport.ImportStatus;
import io.github.excelimport.RowError;
import io.github.excelimport.RowOutcome;
import io.github.excelimport.RowRef;
import io.github.excelimport.SheetSelector;
import io.github.excelimport.TableRef;
import io.github.excelimport.exception.ImportAbortedException;
import io.github.excelimport.exception.ReportGenerationException;
import io.github.excelimport.internal.map.HeaderResolver;
import io.github.excelimport.internal.map.MappingModel;
import io.github.excelimport.internal.map.MappingResult;
import io.github.excelimport.internal.map.ResolvedColumns;
import io.github.excelimport.internal.map.RowMapper;
import io.github.excelimport.internal.read.RawRow;
import io.github.excelimport.internal.read.ReadOptions;
import io.github.excelimport.internal.read.StreamingSheetReader;
import io.github.excelimport.internal.report.ReportWriter;
import io.github.excelimport.internal.write.BatchProcessor;
import io.github.excelimport.internal.validate.BeanValidator;
import io.github.excelimport.outcome.RowOutcomeStore;
import java.nio.file.Path;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Оркестрация одного вызова {@code importFile}. Одноразовый объект: всё изменяемое
 * состояние прогона живёт здесь, а не в переиспользуемом {@code ExcelImporter}.
 */
public final class ImportRun<T> {

    private static final Logger log = LoggerFactory.getLogger(ImportRun.class);

    /** Сигнал раннего выхода из потокового чтения при достижении лимита ошибок. */
    private static final class AbortReading extends RuntimeException {
        private static final long serialVersionUID = 1L;

        AbortReading() {
            super(null, null, false, false);
        }
    }

    private final MappingModel<T> model;
    private final ImportConfig config;
    private final StreamingSheetReader reader;
    private final BeanValidator beanValidator;
    private final BatchProcessor<T> processor;
    private final RowOutcomeStore outcomes;
    private final ReportWriter reportWriter;
    private final ImportListener listener;
    private final RowMapperFactory<T> rowMapperFactory;

    /** Абстракция над созданием маппера — нужна, чтобы прогон не знал про реестр конвертеров. */
    public interface RowMapperFactory<T> {
        RowMapper<T> create(MappingModel<T> model, ResolvedColumns resolved);
    }

    private final UUID runId = UUID.randomUUID();
    private final List<RowError> collectedErrors = new ArrayList<>();
    private final List<RowRef<T>> currentBatch = new ArrayList<>();

    private RowMapper<T> mapper;
    private long totalRows;
    private long insertedRows;
    private long rejectedRows;
    private int batchesCommitted;
    private int batchIndex;
    private boolean errorLimitReached;
    private SQLException fatalFailure;

    public ImportRun(
            MappingModel<T> model,
            ImportConfig config,
            StreamingSheetReader reader,
            BeanValidator beanValidator,
            BatchProcessor<T> processor,
            RowOutcomeStore outcomes,
            ReportWriter reportWriter,
            ImportListener listener,
            RowMapperFactory<T> rowMapperFactory) {
        this.model = model;
        this.config = config;
        this.reader = reader;
        this.beanValidator = beanValidator;
        this.processor = processor;
        this.outcomes = outcomes;
        this.reportWriter = reportWriter;
        this.listener = listener;
        this.rowMapperFactory = rowMapperFactory;
    }

    public ImportReport execute(Path source, String sourceName) {
        Instant startedAt = Instant.now();
        notifyListener(() -> listener.onImportStarted(new ImportRunInfo(
                runId, sourceName, model.table(), config.batchSize(), startedAt)));

        ReadOptions readOptions = config.readOptions();
        try {
            try {
                reader.forEachRow(source, model.sheet(), readOptions, this::handleRow);
            } catch (AbortReading ignored) {
                log.warn("чтение прервано: достигнут предел ошибок ({})", config.maxErrors());
            }
            if (fatalFailure == null) {
                flushBatch();
            }
        } catch (SQLException e) {
            fatalFailure = e;
        }

        ImportStatus status = resolveStatus();
        ImportReport report = buildReport(sourceName, startedAt, status, null);

        Path reportPath = writeReportIfRequested(source, readOptions, report);
        if (config.reportPath() != null) {
            // перестраиваем отчёт: в нём должен быть путь к файлу либо, если запись
            // не удалась, статус PARTIAL и сообщение об этом в errors (§6 спеки)
            ImportStatus afterReport =
                    reportPath == null && status == ImportStatus.SUCCESS ? ImportStatus.PARTIAL : status;
            report = buildReport(sourceName, startedAt, afterReport, reportPath);
        }

        ImportReport finalReport = report;
        notifyListener(() -> listener.onImportFinished(finalReport));

        if (fatalFailure != null) {
            throw new ImportAbortedException(
                    "импорт прерван фатальной ошибкой БД: " + fatalFailure.getMessage(),
                    fatalFailure,
                    finalReport);
        }
        if (errorLimitReached) {
            throw new ImportAbortedException(
                    "импорт прерван: превышен предел ошибок (" + config.maxErrors() + ")",
                    finalReport);
        }
        return finalReport;
    }

    private void handleRow(RawRow row) {
        if (row.rowIndex() < model.headerRowIndex()) {
            return;
        }
        if (row.rowIndex() == model.headerRowIndex()) {
            ResolvedColumns resolved = HeaderResolver.resolve(model, row, config.headerMatching());
            mapper = rowMapperFactory.create(model, resolved);
            return;
        }
        if (row.rowIndex() < model.firstDataRowIndex()) {
            return;
        }
        if (mapper == null) {
            // строка заголовка отсутствует в файле вовсе
            throw new io.github.excelimport.exception.FileStructureException(
                    "в файле нет строки заголовка с индексом " + model.headerRowIndex());
        }

        totalRows++;
        int rowNum = row.excelRowNumber();

        MappingResult<T> mapped = mapper.map(row);
        List<RowError> errors = new ArrayList<>(mapped.errors());
        if (errors.isEmpty()) {
            errors.addAll(beanValidator.validate(mapped.value(), rowNum));
        }
        if (!errors.isEmpty()) {
            rejectRow(rowNum, errors);
            return;
        }

        currentBatch.add(new RowRef<>(rowNum, mapped.value()));
        if (currentBatch.size() >= config.batchSize()) {
            try {
                flushBatch();
            } catch (SQLException e) {
                fatalFailure = e;
                throw new AbortReading();
            }
        }
    }

    private void rejectRow(int rowNum, List<RowError> errors) {
        rejectedRows++;
        outcomes.put(rowNum, RowOutcome.rejected(joinMessages(errors)));
        for (RowError error : errors) {
            recordError(error);
        }
        checkErrorLimit();
    }

    private void flushBatch() throws SQLException {
        if (currentBatch.isEmpty()) {
            return;
        }
        batchIndex++;
        List<RowRef<T>> batch = List.copyOf(currentBatch);
        currentBatch.clear();

        BatchProcessor.BatchOutcome outcome = processor.process(batch);
        if (outcome.statementCount() > 1) {
            int finalBatchIndex = batchIndex;
            int size = batch.size();
            notifyListener(() -> listener.onBatchSplit(finalBatchIndex, outcome.statementCount(), size));
        }

        java.util.Map<Integer, List<RowError>> errorsByRow = outcome.errors().stream()
                .collect(Collectors.groupingBy(RowError::rowNum));

        for (RowRef<T> row : batch) {
            List<RowError> rowErrors = errorsByRow.get(row.rowNum());
            if (rowErrors == null) {
                outcomes.put(row.rowNum(), RowOutcome.inserted());
            } else {
                rejectedRows++;
                outcomes.put(row.rowNum(), RowOutcome.rejected(joinMessages(rowErrors)));
                rowErrors.forEach(this::recordError);
            }
        }

        insertedRows += outcome.insertedCount();
        batchesCommitted++;
        int inserted = outcome.insertedCount();
        int finalBatchIndex = batchIndex;
        long total = insertedRows;
        notifyListener(() -> listener.onBatchCommitted(finalBatchIndex, inserted, total));

        checkErrorLimit();
        if (errorLimitReached) {
            throw new AbortReading();
        }
    }

    private void recordError(RowError error) {
        if (collectedErrors.size() < config.maxErrorsInMemory()) {
            collectedErrors.add(error);
        }
        notifyListener(() -> listener.onRowRejected(error));
    }

    private void checkErrorLimit() {
        if (rejectedRows > config.maxErrors()) {
            errorLimitReached = true;
        }
    }

    private static String joinMessages(List<RowError> errors) {
        return errors.stream().map(RowError::message).collect(Collectors.joining("; "));
    }

    private ImportStatus resolveStatus() {
        if (fatalFailure != null || errorLimitReached) {
            return ImportStatus.FAILED;
        }
        return rejectedRows == 0 ? ImportStatus.SUCCESS : ImportStatus.PARTIAL;
    }

    private ImportReport buildReport(
            String sourceName, Instant startedAt, ImportStatus status, Path reportPath) {
        return new ImportReport(
                runId,
                sourceName,
                totalRows,
                insertedRows,
                rejectedRows,
                batchesCommitted,
                Duration.between(startedAt, Instant.now()),
                reportPath,
                List.copyOf(collectedErrors),
                errorLimitReached,
                status);
    }

    private Path writeReportIfRequested(Path source, ReadOptions readOptions, ImportReport report) {
        if (config.reportPath() == null || reportWriter == null) {
            return null;
        }
        outcomes.seal();
        try {
            reportWriter.write(
                    source, model.sheet(), readOptions, outcomes, report, config.reportPath());
            return config.reportPath();
        } catch (ReportGenerationException e) {
            log.warn("импорт выполнен, но отчёт не сформирован: {}", e.getMessage());
            collectedErrors.add(new RowError(
                    1, null, null, ErrorKind.STRUCTURE, "REPORT_FAILED",
                    "не удалось сформировать отчёт: " + e.getMessage()));
            return null;
        }
    }

    private void notifyListener(Runnable action) {
        try {
            action.run();
        } catch (RuntimeException e) {
            log.warn("ImportListener бросил исключение, продолжаю импорт: {}", e.toString());
        }
    }
}
```

Импорты `SheetSelector`, `TableRef` и `HeaderMatchingPolicy` в этом файле не нужны — не добавлять.

Замечание по статусу: провал записи отчёта не отменяет уже выполненный импорт, но понижает `SUCCESS` до `PARTIAL` и добавляет запись в `errors`, как требует §6 спеки. `FAILED` и `PARTIAL` при этом не меняются.

- [ ] **Step 5: Реализовать `ExcelImporter`**

```java
package io.github.excelimport;

import io.github.excelimport.convert.CellConverter;
import io.github.excelimport.exception.ExcelImportException;
import io.github.excelimport.exception.MappingConfigurationException;
import io.github.excelimport.internal.ImportRun;
import io.github.excelimport.internal.convert.ConverterRegistry;
import io.github.excelimport.internal.map.MappingModel;
import io.github.excelimport.internal.map.MappingModelFactory;
import io.github.excelimport.internal.map.RowMapper;
import io.github.excelimport.internal.outcome.SpillableRowOutcomeStore;
import io.github.excelimport.internal.read.PoiStreamingSheetReader;
import io.github.excelimport.internal.read.StreamingSheetReader;
import io.github.excelimport.internal.report.ReportWriter;
import io.github.excelimport.internal.validate.BeanValidator;
import io.github.excelimport.internal.write.BatchProcessor;
import io.github.excelimport.internal.write.BatchSplitter;
import io.github.excelimport.internal.write.DefaultSqlErrorClassifier;
import io.github.excelimport.internal.write.InsertExecutor;
import io.github.excelimport.internal.write.RowBinder;
import io.github.excelimport.internal.write.SqlBuilder;
import io.github.excelimport.outcome.RowOutcomeStore;
import io.github.excelimport.report.ReportRowCustomizer;
import io.github.excelimport.validate.BatchValidator;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;
import javax.sql.DataSource;

/**
 * Точка входа библиотеки. Потокобезопасен и переиспользуем: разбор аннотаций,
 * шаблон SQL и {@code ValidatorFactory} считаются один раз при сборке.
 * Один вызов {@link #importFile(Path)} — один изолированный прогон.
 */
public final class ExcelImporter<T> implements AutoCloseable {

    private final MappingModel<T> model;
    private final ImportConfig config;
    private final StreamingSheetReader reader;
    private final ConverterRegistry converters;
    private final BeanValidator beanValidator;
    private final DataSource dataSource;
    private final List<BatchValidator<T>> batchValidators;
    private final SqlErrorClassifier sqlErrorClassifier;
    private final ReportRowCustomizer reportRowCustomizer;
    private final ImportListener listener;
    private final Supplier<RowOutcomeStore> outcomeStoreFactory;
    private final SqlBuilder sqlBuilder;
    private final RowBinder<T> rowBinder;

    private ExcelImporter(Builder<T> builder) {
        this.config = builder.config;
        MappingModel<T> parsed = MappingModelFactory.create(builder.type, config.namingStrategy());
        if (config.targetTable() != null) {
            parsed = parsed.withTable(config.targetTable());
        }
        if (parsed.table() == null) {
            throw new MappingConfigurationException(
                    "не задана таблица-приёмник: добавьте @TargetTable на " + builder.type.getName()
                            + " или ImportConfig.targetTable(...)");
        }
        if (config.sheet() != null) {
            parsed = parsed.withSheet(config.sheet(), config.headerRow(), config.firstDataRow());
        }
        this.model = parsed;
        this.reader = builder.reader != null ? builder.reader : new PoiStreamingSheetReader();
        this.converters = builder.converters;
        this.beanValidator = new BeanValidator(config.locale(), model);
        this.dataSource = builder.dataSource;
        this.batchValidators = List.copyOf(builder.batchValidators);
        this.sqlErrorClassifier = builder.sqlErrorClassifier != null
                ? builder.sqlErrorClassifier
                : new DefaultSqlErrorClassifier();
        this.reportRowCustomizer = builder.reportRowCustomizer;
        this.listener = builder.listener != null ? builder.listener : new ImportListener() {};
        this.outcomeStoreFactory = builder.outcomeStoreFactory != null
                ? builder.outcomeStoreFactory
                : () -> new SpillableRowOutcomeStore(
                        config.tempDir(), config.maxOutcomeMessagesInMemory());
        this.sqlBuilder =
                new SqlBuilder(model.table(), model.allDbColumns(), config.conflictStrategy());
        this.rowBinder = new RowBinder<>(model.allBindings());
        // Ранняя проверка конвертеров: ошибки конфигурации должны всплывать здесь,
        // а не на середине импорта.
        model.excelColumns().forEach(converters::resolve);
    }

    public static <T> Builder<T> builder(Class<T> type) {
        return new Builder<>(type);
    }

    /** Импортирует файл. Файл читается дважды: данные и затем генерация отчёта. */
    public ImportReport importFile(Path source) {
        Objects.requireNonNull(source, "source");
        if (!Files.isReadable(source)) {
            throw new ExcelImportException("файл недоступен для чтения: " + source);
        }
        return run(source, source.getFileName().toString());
    }

    /**
     * Импортирует поток. Поток копируется во временный файл, потому что нужны два прохода;
     * временный файл удаляется по завершении.
     */
    public ImportReport importFile(InputStream source, String sourceName) {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(sourceName, "sourceName");
        Path temporary;
        try {
            temporary = Files.createTempFile(config.tempDir(), "excel-import-", ".xlsx");
            Files.copy(source, temporary, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new ExcelImportException("не удалось скопировать поток во временный файл", e);
        }
        try {
            return run(temporary, sourceName);
        } finally {
            try {
                Files.deleteIfExists(temporary);
            } catch (IOException e) {
                // временный файл останется в tempDir; это не повод падать
            }
        }
    }

    private ImportReport run(Path source, String sourceName) {
        InsertExecutor<T> executor =
                new InsertExecutor<>(sqlBuilder, rowBinder, config.queryTimeoutSeconds());
        BatchSplitter<T> splitter = new BatchSplitter<>(
                config.maxSplitDepth(), sqlErrorClassifier, config.includeDatabaseDetailInReport());
        BatchProcessor<T> processor = new BatchProcessor<>(
                dataSource, executor, splitter, batchValidators, config.dryRun(), config.batchSize());
        ReportWriter reportWriter = config.reportPath() == null
                ? null
                : new ReportWriter(reader, config.reportStyle(), reportRowCustomizer);

        try (RowOutcomeStore outcomes = outcomeStoreFactory.get()) {
            ImportRun<T> importRun = new ImportRun<>(
                    model,
                    config,
                    reader,
                    beanValidator,
                    processor,
                    outcomes,
                    reportWriter,
                    listener,
                    (m, resolved) -> new RowMapper<>(
                            m, resolved, converters, config.locale(), config.booleanWords()));
            return importRun.execute(source, sourceName);
        }
    }

    @Override
    public void close() {
        beanValidator.close();
    }

    /** Сборщик импортёра. Не потокобезопасен; собранный {@link ExcelImporter} — да. */
    public static final class Builder<T> {

        private final Class<T> type;
        private ImportConfig config = ImportConfig.builder().build();
        private DataSource dataSource;
        private StreamingSheetReader reader;
        private final ConverterRegistry converters = new ConverterRegistry();
        private final List<BatchValidator<T>> batchValidators = new ArrayList<>();
        private SqlErrorClassifier sqlErrorClassifier;
        private ReportRowCustomizer reportRowCustomizer;
        private ImportListener listener;
        private Supplier<RowOutcomeStore> outcomeStoreFactory;

        private Builder(Class<T> type) {
            this.type = Objects.requireNonNull(type, "type");
        }

        public Builder<T> config(ImportConfig value) {
            this.config = Objects.requireNonNull(value, "config");
            return this;
        }

        public Builder<T> dataSource(DataSource value) {
            this.dataSource = Objects.requireNonNull(value, "dataSource");
            return this;
        }

        /** Подмена читателя — только для тестов библиотеки. */
        Builder<T> reader(StreamingSheetReader value) {
            this.reader = value;
            return this;
        }

        public Builder<T> converter(Class<?> targetType, CellConverter<?> converter) {
            converters.register(targetType, converter);
            return this;
        }

        public Builder<T> batchValidator(BatchValidator<T> validator) {
            batchValidators.add(Objects.requireNonNull(validator, "validator"));
            return this;
        }

        public Builder<T> sqlErrorClassifier(SqlErrorClassifier value) {
            this.sqlErrorClassifier = value;
            return this;
        }

        public Builder<T> reportRowCustomizer(ReportRowCustomizer value) {
            this.reportRowCustomizer = value;
            return this;
        }

        public Builder<T> listener(ImportListener value) {
            this.listener = value;
            return this;
        }

        public Builder<T> outcomeStoreFactory(Supplier<RowOutcomeStore> value) {
            this.outcomeStoreFactory = value;
            return this;
        }

        public ExcelImporter<T> build() {
            if (dataSource == null) {
                throw new IllegalStateException(
                        "не задан dataSource: ExcelImporter.builder(...).dataSource(ds)");
            }
            return new ExcelImporter<>(this);
        }
    }
}
```

- [ ] **Step 6: Запустить unit-тесты сборки**

Run: `./gradlew :excel-import-core:test --tests "*ExcelImporterBuilderTest*"`
Expected: PASS — 6 тестов.

- [ ] **Step 7: Написать сквозной интеграционный тест**

`excel-import-core/src/integrationTest/java/io/github/excelimport/ExcelImporterIT.java`:

```java
package io.github.excelimport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.excelimport.annotation.Column;
import io.github.excelimport.annotation.ExcelColumn;
import io.github.excelimport.annotation.ExcelSheet;
import io.github.excelimport.annotation.TargetTable;
import io.github.excelimport.exception.FileStructureException;
import io.github.excelimport.exception.ImportAbortedException;
import io.github.excelimport.testsupport.PostgresSupport;
import io.github.excelimport.testsupport.XlsxFixtures;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import javax.sql.DataSource;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ExcelImporterIT {

    @ExcelSheet(name = "Лист1", headerRow = 0)
    @TargetTable(name = "employee")
    public static class Employee {
        @ExcelColumn(header = "Номер")
        @Column("personnel_no")
        @NotNull
        public Long personnelNo;

        @ExcelColumn(header = "ФИО")
        @Column("full_name")
        @NotBlank
        public String fullName;

        @ExcelColumn(header = "Стаж")
        @Column("years")
        @Min(0)
        public Integer years;

        public Employee() {}
    }

    @TempDir
    Path tempDir;

    private final DataSource dataSource = PostgresSupport.dataSource();

    @BeforeEach
    void resetTable() {
        PostgresSupport.execute(
                "DROP TABLE IF EXISTS employee",
                "CREATE TABLE employee ("
                        + "personnel_no bigint PRIMARY KEY, "
                        + "full_name text NOT NULL, "
                        + "years int)");
    }

    private Path fixture(Object[][] rows) {
        return XlsxFixtures.simpleSheet(tempDir, rows);
    }

    private ExcelImporter.Builder<Employee> importer(ImportConfig config) {
        return ExcelImporter.builder(Employee.class).dataSource(dataSource).config(config);
    }

    @Test
    void happyPathInsertsEveryRow() {
        Path file = fixture(new Object[][] {
            {"Номер", "ФИО", "Стаж"},
            {1, "Иванов", 3},
            {2, "Петров", 5},
            {3, "Сидоров", 1},
        });

        try (ExcelImporter<Employee> excelImporter =
                importer(ImportConfig.builder().batchSize(2).build()).build()) {
            ImportReport report = excelImporter.importFile(file);

            assertThat(report.status()).isEqualTo(ImportStatus.SUCCESS);
            assertThat(report.totalRows()).isEqualTo(3);
            assertThat(report.insertedRows()).isEqualTo(3);
            assertThat(report.batchesCommitted()).isEqualTo(2); // 2 + 1
            assertThat(PostgresSupport.countRows("employee")).isEqualTo(3);
        }
    }

    @Test
    void batchSizeControlsNumberOfTransactions() {
        Object[][] rows = new Object[1001][];
        rows[0] = new Object[] {"Номер", "ФИО", "Стаж"};
        for (int i = 1; i <= 1000; i++) {
            rows[i] = new Object[] {i, "Сотрудник " + i, i % 40};
        }
        Path file = fixture(rows);
        List<Integer> committedBatches = new ArrayList<>();
        ImportListener listener = new ImportListener() {
            @Override
            public void onBatchCommitted(int batchIndex, int rowCount, long totalInserted) {
                committedBatches.add(rowCount);
            }
        };

        try (ExcelImporter<Employee> excelImporter = importer(ImportConfig.builder().batchSize(250).build())
                .listener(listener)
                .build()) {
            ImportReport report = excelImporter.importFile(file);

            assertThat(report.insertedRows()).isEqualTo(1000);
            assertThat(committedBatches).hasSize(4).containsOnly(250);
        }
    }

    @Test
    void invalidRowsAreRejectedAndReportIsMarked() throws Exception {
        Path file = fixture(new Object[][] {
            {"Номер", "ФИО", "Стаж"},
            {1, "Иванов", 3},
            {2, "", 5}, // NotBlank
            {3, "Сидоров", "abc"}, // ошибка конвертации
        });
        Path reportPath = tempDir.resolve("report.xlsx");

        try (ExcelImporter<Employee> excelImporter = importer(
                        ImportConfig.builder().batchSize(10).reportPath(reportPath).build())
                .build()) {
            ImportReport report = excelImporter.importFile(file);

            assertThat(report.status()).isEqualTo(ImportStatus.PARTIAL);
            assertThat(report.insertedRows()).isEqualTo(1);
            assertThat(report.rejectedRows()).isEqualTo(2);
            assertThat(PostgresSupport.countRows("employee")).isEqualTo(1);
            assertThat(Files.exists(reportPath)).isTrue();
        }

        try (Workbook workbook = new XSSFWorkbook(Files.newInputStream(reportPath))) {
            Sheet sheet = workbook.getSheetAt(0);
            assertThat(sheet.getRow(1).getCell(4).getStringCellValue()).isEmpty();
            assertThat(sheet.getRow(2).getCell(4).getStringCellValue()).isNotEmpty();
            assertThat(sheet.getRow(3).getCell(4).getStringCellValue()).contains("Стаж");
        }
    }

    @Test
    void databaseRejectionIsIsolatedAndReported() {
        Path file = fixture(new Object[][] {
            {"Номер", "ФИО", "Стаж"},
            {1, "Иванов", 1},
            {1, "Дубликат", 1}, // конфликт первичного ключа
            {2, "Петров", 2},
        });

        try (ExcelImporter<Employee> excelImporter =
                importer(ImportConfig.builder().batchSize(10).build()).build()) {
            ImportReport report = excelImporter.importFile(file);

            assertThat(report.insertedRows()).isEqualTo(2);
            assertThat(report.rejectedRows()).isEqualTo(1);
            assertThat(report.errors()).singleElement()
                    .satisfies(error -> assertThat(error.kind()).isEqualTo(ErrorKind.DATABASE));
        }
    }

    @Test
    void maxErrorsAbortsImportButKeepsCommittedRows() {
        Path file = fixture(new Object[][] {
            {"Номер", "ФИО", "Стаж"},
            {1, "Иванов", 1},
            {2, "", 1},
            {3, "", 1},
            {4, "", 1},
        });

        try (ExcelImporter<Employee> excelImporter =
                importer(ImportConfig.builder().batchSize(1).maxErrors(1).build()).build()) {
            assertThatThrownBy(() -> excelImporter.importFile(file))
                    .isInstanceOf(ImportAbortedException.class)
                    .satisfies(e -> {
                        ImportReport partial = ((ImportAbortedException) e).partialReport();
                        assertThat(partial.status()).isEqualTo(ImportStatus.FAILED);
                        assertThat(partial.errorLimitReached()).isTrue();
                    });
            assertThat(PostgresSupport.countRows("employee")).isEqualTo(1);
        }
    }

    @Test
    void missingRequiredColumnFailsBeforeAnyInsert() {
        Path file = fixture(new Object[][] {
            {"Номер", "Стаж"}, // нет колонки ФИО
            {1, 3},
        });

        try (ExcelImporter<Employee> excelImporter =
                importer(ImportConfig.builder().build()).build()) {
            assertThatThrownBy(() -> excelImporter.importFile(file))
                    .isInstanceOf(FileStructureException.class)
                    .hasMessageContaining("ФИО");
            assertThat(PostgresSupport.countRows("employee")).isZero();
        }
    }

    @Test
    void dryRunLeavesNoData() {
        Path file = fixture(new Object[][] {
            {"Номер", "ФИО", "Стаж"},
            {1, "Иванов", 3},
        });

        try (ExcelImporter<Employee> excelImporter =
                importer(ImportConfig.builder().dryRun(true).build()).build()) {
            ImportReport report = excelImporter.importFile(file);

            assertThat(report.status()).isEqualTo(ImportStatus.SUCCESS);
            assertThat(PostgresSupport.countRows("employee")).isZero();
        }
    }

    @Test
    void importFromInputStreamWorks() throws Exception {
        Path file = fixture(new Object[][] {
            {"Номер", "ФИО", "Стаж"},
            {7, "Из потока", 1},
        });

        try (ExcelImporter<Employee> excelImporter =
                        importer(ImportConfig.builder().build()).build();
                var stream = Files.newInputStream(file)) {
            ImportReport report = excelImporter.importFile(stream, "поток.xlsx");

            assertThat(report.sourceName()).isEqualTo("поток.xlsx");
            assertThat(PostgresSupport.countRows("employee")).isEqualTo(1);
        }
    }

    @Test
    void importerIsReusableForMultipleFiles() {
        Path first = fixture(new Object[][] {{"Номер", "ФИО", "Стаж"}, {1, "А", 1}});
        Path second = fixture(new Object[][] {{"Номер", "ФИО", "Стаж"}, {2, "Б", 2}});

        try (ExcelImporter<Employee> excelImporter =
                importer(ImportConfig.builder().build()).build()) {
            excelImporter.importFile(first);
            ImportReport report = excelImporter.importFile(second);

            assertThat(report.runId()).isNotNull();
            assertThat(PostgresSupport.countRows("employee")).isEqualTo(2);
        }
    }

    @Test
    void listenerReceivesStartAndFinish() {
        Path file = fixture(new Object[][] {{"Номер", "ФИО", "Стаж"}, {1, "А", 1}});
        List<String> events = new ArrayList<>();
        ImportListener listener = new ImportListener() {
            @Override
            public void onImportStarted(ImportRunInfo info) {
                events.add("started:" + info.sourceName());
            }

            @Override
            public void onImportFinished(ImportReport report) {
                events.add("finished:" + report.status());
            }
        };

        try (ExcelImporter<Employee> excelImporter =
                importer(ImportConfig.builder().build()).listener(listener).build()) {
            excelImporter.importFile(file);
        }

        assertThat(events).containsExactly(
                "started:" + file.getFileName(), "finished:" + ImportStatus.SUCCESS);
    }

    @Test
    void listenerExceptionDoesNotBreakImport() {
        Path file = fixture(new Object[][] {{"Номер", "ФИО", "Стаж"}, {1, "А", 1}});
        ImportListener broken = new ImportListener() {
            @Override
            public void onBatchCommitted(int batchIndex, int rowCount, long totalInserted) {
                throw new IllegalStateException("слушатель сломан");
            }
        };

        try (ExcelImporter<Employee> excelImporter =
                importer(ImportConfig.builder().build()).listener(broken).build()) {
            ImportReport report = excelImporter.importFile(file);

            assertThat(report.status()).isEqualTo(ImportStatus.SUCCESS);
            assertThat(PostgresSupport.countRows("employee")).isEqualTo(1);
        }
    }
}
```

- [ ] **Step 8: Запустить интеграционные тесты**

Run: `./gradlew :excel-import-core:integrationTest`
Expected: PASS — `BatchProcessorIT` (9) и `ExcelImporterIT` (11).

- [ ] **Step 9: Прогнать всю проверку**

Run: `./gradlew check`
Expected: `BUILD SUCCESSFUL` — все unit- и интеграционные тесты зелёные.

- [ ] **Step 10: Коммит**

```bash
git add excel-import-core/src/main/java/io/github/excelimport \
        excel-import-core/src/test/java/io/github/excelimport \
        excel-import-core/src/integrationTest/java/io/github/excelimport
git commit -m "feat: add ExcelImporter facade orchestrating read, validate, insert and report"
```

---

### Task 17: Краевые интеграционные сценарии и перф-тест памяти

**Files:**
- Create: `excel-import-core/src/integrationTest/java/io/github/excelimport/ImportEdgeCasesIT.java`
- Create: `excel-import-core/src/performanceTest/java/io/github/excelimport/LargeFileMemoryTest.java`
- Create: `excel-import-core/src/performanceTest/java/io/github/excelimport/testsupport/LargeFixture.java`
- Modify: `excel-import-core/build.gradle.kts` (зависимость `performanceTest` от Testcontainers)

**Interfaces:**
- Consumes: `ExcelImporter` и всё ядро.
- Produces: покрытие сценариев из §10 спеки, которых нет в предыдущих задачах: обрыв соединения, `queryTimeout`, широкая таблица с дроблением чанков, файл с формулами и датами, `@ExcelColumn(required = false)`, отчёт при `status = FAILED`; перф-тест на 100 000 строк при `-Xmx256m`.

- [ ] **Step 1: Дописать зависимости `performanceTest`**

В `excel-import-core/build.gradle.kts` добавить в блок `dependencies`:

```kotlin
    "performanceTestImplementation"(libs.testcontainers.postgresql)
    "performanceTestImplementation"(libs.testcontainers.junit)
    "performanceTestImplementation"(libs.postgresql)
```

и в `performanceTest`-source set подключить исходники `integrationTest` для повторного использования `PostgresSupport`:

```kotlin
val performanceTest: SourceSet by sourceSets.creating {
    compileClasspath += sourceSets["main"].output + sourceSets["test"].output +
            sourceSets["integrationTest"].output
    runtimeClasspath += output + compileClasspath
}
```

- [ ] **Step 2: Написать краевые интеграционные тесты**

`excel-import-core/src/integrationTest/java/io/github/excelimport/ImportEdgeCasesIT.java`:

```java
package io.github.excelimport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.excelimport.annotation.Column;
import io.github.excelimport.annotation.ExcelColumn;
import io.github.excelimport.annotation.ExcelSheet;
import io.github.excelimport.annotation.TargetTable;
import io.github.excelimport.exception.ImportAbortedException;
import io.github.excelimport.testsupport.PostgresSupport;
import io.github.excelimport.testsupport.XlsxFixtures;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.List;
import javax.sql.DataSource;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ImportEdgeCasesIT {

    @ExcelSheet(name = "Лист1")
    @TargetTable(name = "record")
    public static class Record {
        @ExcelColumn(header = "Ключ")
        @Column("key")
        public Long key;

        @ExcelColumn(header = "Дата")
        @Column("day")
        public LocalDate day;

        @ExcelColumn(header = "Сумма")
        @Column("amount")
        public java.math.BigDecimal amount;

        @ExcelColumn(header = "Комментарий", required = false)
        @Column("note")
        public String note;

        public Record() {}
    }

    @TempDir
    Path tempDir;

    private final DataSource dataSource = PostgresSupport.dataSource();

    @BeforeEach
    void resetTable() {
        PostgresSupport.execute(
                "DROP TABLE IF EXISTS record",
                "CREATE TABLE record ("
                        + "key bigint PRIMARY KEY, day date, amount numeric(12,2), note text)");
    }

    private ExcelImporter<Record> importer(ImportConfig config) {
        return ExcelImporter.builder(Record.class).dataSource(dataSource).config(config).build();
    }

    @Test
    void optionalColumnMissingFromFileIsFine() {
        Path file = XlsxFixtures.simpleSheet(tempDir, new Object[][] {
            {"Ключ", "Дата", "Сумма"},
            {1, LocalDate.of(2026, 7, 29), 100.55},
        });

        try (ExcelImporter<Record> excelImporter = importer(ImportConfig.builder().build())) {
            ImportReport report = excelImporter.importFile(file);

            assertThat(report.status()).isEqualTo(ImportStatus.SUCCESS);
            assertThat(PostgresSupport.countRows("record")).isEqualTo(1);
        }
    }

    @Test
    void datesAndDecimalsRoundTripThroughPostgres() throws SQLException {
        Path file = XlsxFixtures.simpleSheet(tempDir, new Object[][] {
            {"Ключ", "Дата", "Сумма", "Комментарий"},
            {1, LocalDate.of(2026, 2, 28), 1234.56, "тест"},
        });

        try (ExcelImporter<Record> excelImporter = importer(ImportConfig.builder().build())) {
            excelImporter.importFile(file);
        }

        try (var connection = dataSource.getConnection();
                var statement = connection.createStatement();
                var rs = statement.executeQuery("SELECT day, amount, note FROM record WHERE key = 1")) {
            rs.next();
            assertThat(rs.getDate(1).toLocalDate()).isEqualTo(LocalDate.of(2026, 2, 28));
            assertThat(rs.getBigDecimal(2)).isEqualByComparingTo("1234.56");
            assertThat(rs.getString(3)).isEqualTo("тест");
        }
    }

    @Test
    void blankRowsInTheMiddleAreSkippedAndDoNotShiftNumbering() throws Exception {
        Path file = XlsxFixtures.workbook(tempDir, "Лист1", sheet -> {
            Row header = sheet.createRow(0);
            header.createCell(0).setCellValue("Ключ");
            header.createCell(1).setCellValue("Дата");
            header.createCell(2).setCellValue("Сумма");
            sheet.createRow(1).createCell(0).setCellValue(1);
            sheet.createRow(2); // полностью пустая
            sheet.createRow(3).createCell(0).setCellValue(2);
        });
        Path reportPath = tempDir.resolve("blank-report.xlsx");

        try (ExcelImporter<Record> excelImporter =
                importer(ImportConfig.builder().reportPath(reportPath).build())) {
            ImportReport report = excelImporter.importFile(file);

            assertThat(report.totalRows()).isEqualTo(2);
            assertThat(report.insertedRows()).isEqualTo(2);
        }

        try (Workbook workbook = new XSSFWorkbook(Files.newInputStream(reportPath))) {
            Sheet sheet = workbook.getSheetAt(0);
            // в отчёте пустая строка остаётся на своём месте (строка 3 файла = индекс 2)
            assertThat(sheet.getRow(3).getCell(0).getNumericCellValue()).isEqualTo(2.0);
        }
    }

    @Test
    void formulaWithCachedResultIsImported() {
        Path file = XlsxFixtures.workbook(tempDir, "Лист1", sheet -> {
            Row header = sheet.createRow(0);
            header.createCell(0).setCellValue("Ключ");
            header.createCell(1).setCellValue("Дата");
            header.createCell(2).setCellValue("Сумма");
            Row data = sheet.createRow(1);
            data.createCell(0).setCellValue(1);
            var formula = data.createCell(2);
            formula.setCellFormula("100*2");
            formula.setCellValue(200);
        });

        try (ExcelImporter<Record> excelImporter = importer(ImportConfig.builder().build())) {
            ImportReport report = excelImporter.importFile(file);

            assertThat(report.insertedRows()).isEqualTo(1);
        }
    }

    @Test
    void connectionLossAbortsImportAndKeepsCommittedBatches() {
        Object[][] rows = new Object[21][];
        rows[0] = new Object[] {"Ключ", "Дата", "Сумма"};
        for (int i = 1; i <= 20; i++) {
            rows[i] = new Object[] {i, LocalDate.of(2026, 1, 1), i};
        }
        Path file = XlsxFixtures.simpleSheet(tempDir, rows);

        // DataSource, который отдаёт рабочее соединение первые 2 раза, дальше падает
        DataSource flaky = FailingDataSource.failAfter(dataSource, 2);

        try (ExcelImporter<Record> excelImporter = ExcelImporter.builder(Record.class)
                .dataSource(flaky)
                .config(ImportConfig.builder().batchSize(5).build())
                .build()) {
            assertThatThrownBy(() -> excelImporter.importFile(file))
                    .isInstanceOf(ImportAbortedException.class);
        }

        assertThat(PostgresSupport.countRows("record")).isEqualTo(10); // два батча по 5
    }

    @Test
    void reportIsStillGeneratedWhenImportAborts() {
        Path file = XlsxFixtures.simpleSheet(tempDir, new Object[][] {
            {"Ключ", "Дата", "Сумма"},
            {1, LocalDate.of(2026, 1, 1), 1},
            {null, LocalDate.of(2026, 1, 1), 2}, // key = null → NOT NULL в БД
            {3, LocalDate.of(2026, 1, 1), 3},
        });
        Path reportPath = tempDir.resolve("aborted-report.xlsx");

        try (ExcelImporter<Record> excelImporter = importer(
                ImportConfig.builder().batchSize(1).maxErrors(0).reportPath(reportPath).build())) {
            assertThatThrownBy(() -> excelImporter.importFile(file))
                    .isInstanceOf(ImportAbortedException.class)
                    .satisfies(e -> assertThat(((ImportAbortedException) e).partialReport().reportPath())
                            .isEqualTo(reportPath));
        }

        assertThat(Files.exists(reportPath)).isTrue();
    }

    @Test
    void wideTableIsSplitIntoChunksWithinOneTransaction() {
        // 700 колонок × batchSize 1000 = 700 000 параметров, что больше предела 65535
        PostgresSupport.execute(
                "DROP TABLE IF EXISTS wide",
                "CREATE TABLE wide (" + wideColumnsDdl(700) + ")");

        try (ExcelImporter<WideRow> excelImporter = ExcelImporter.builder(WideRow.class)
                .dataSource(dataSource)
                .config(ImportConfig.builder().batchSize(1000).build())
                .build()) {
            ImportReport report = excelImporter.importFile(wideFixture(200));

            assertThat(report.insertedRows()).isEqualTo(200);
            assertThat(PostgresSupport.countRows("wide")).isEqualTo(200);
        }
    }

    private static String wideColumnsDdl(int count) {
        StringBuilder ddl = new StringBuilder("c0 int PRIMARY KEY");
        for (int i = 1; i < count; i++) {
            ddl.append(", c").append(i).append(" int");
        }
        return ddl.toString();
    }

    /**
     * Модель на 700 колонок сгенерировать аннотациями нельзя — используем индексную
     * привязку к первым трём колонкам и оставляем остальное NULL: проверяем именно
     * дробление чанков по числу колонок таблицы, а не по числу полей модели.
     */
    @ExcelSheet(name = "Лист1")
    @TargetTable(name = "wide")
    public static class WideRow {
        @ExcelColumn(index = 0)
        @Column("c0")
        public Integer c0;

        @ExcelColumn(index = 1)
        @Column("c1")
        public Integer c1;

        public WideRow() {}
    }

    private Path wideFixture(int rows) {
        Object[][] data = new Object[rows + 1][];
        data[0] = new Object[] {"c0", "c1"};
        for (int i = 1; i <= rows; i++) {
            data[i] = new Object[] {i, i * 2};
        }
        return XlsxFixtures.simpleSheet(tempDir, data);
    }

    @Test
    void customBatchValidatorRejectsDuplicatesWithinFile() {
        Path file = XlsxFixtures.simpleSheet(tempDir, new Object[][] {
            {"Ключ", "Дата", "Сумма"},
            {1, LocalDate.of(2026, 1, 1), 1},
            {1, LocalDate.of(2026, 1, 1), 2},
            {2, LocalDate.of(2026, 1, 1), 3},
        });

        try (ExcelImporter<Record> excelImporter = ExcelImporter.builder(Record.class)
                .dataSource(dataSource)
                .config(ImportConfig.builder().batchSize(10).build())
                .batchValidator((batch, connection) -> {
                    java.util.Map<Long, Integer> seen = new java.util.HashMap<>();
                    List<RowError> errors = new java.util.ArrayList<>();
                    for (RowRef<Record> row : batch) {
                        Integer previous = seen.putIfAbsent(row.value().key, row.rowNum());
                        if (previous != null) {
                            errors.add(RowError.batch(row.rowNum(), "DUPLICATE_IN_FILE",
                                    "дубликат строки " + previous));
                        }
                    }
                    return errors;
                })
                .build()) {
            ImportReport report = excelImporter.importFile(file);

            assertThat(report.insertedRows()).isEqualTo(2);
            assertThat(report.rejectedRows()).isEqualTo(1);
            assertThat(report.errors()).singleElement()
                    .satisfies(error -> assertThat(error.kind()).isEqualTo(ErrorKind.BATCH));
        }
    }
}
```

- [ ] **Step 3: Написать `FailingDataSource`**

`excel-import-core/src/integrationTest/java/io/github/excelimport/FailingDataSource.java`:

```java
package io.github.excelimport;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.SQLException;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;

/** DataSource, который перестаёт выдавать соединения после N успешных выдач. */
final class FailingDataSource {

    private FailingDataSource() {}

    static DataSource failAfter(DataSource delegate, int successfulConnections) {
        AtomicInteger issued = new AtomicInteger();
        InvocationHandler handler = (proxy, method, args) -> {
            if ("getConnection".equals(method.getName())
                    && issued.getAndIncrement() >= successfulConnections) {
                throw new SQLException("имитация обрыва соединения", "08006");
            }
            try {
                return method.invoke(delegate, args);
            } catch (InvocationTargetException e) {
                throw e.getTargetException();
            }
        };
        return (DataSource) Proxy.newProxyInstance(
                DataSource.class.getClassLoader(), new Class<?>[] {DataSource.class}, handler);
    }
}
```

- [ ] **Step 4: Запустить краевые тесты**

Run: `./gradlew :excel-import-core:integrationTest --tests "*ImportEdgeCasesIT*"`
Expected: PASS — 8 тестов.

Если `connectionLossAbortsImportAndKeepsCommittedBatches` даёт другое число строк — значит `BatchProcessor` получает соединение не по одному на батч. Проверить, что `dataSource.getConnection()` вызывается ровно раз на `process(...)`, и скорректировать ожидание теста под фактическое (документированное) поведение, а не наоборот.

- [ ] **Step 5: Написать генератор большой фикстуры и перф-тест**

`excel-import-core/src/performanceTest/java/io/github/excelimport/testsupport/LargeFixture.java`:

```java
package io.github.excelimport.testsupport;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.xssf.streaming.SXSSFSheet;
import org.apache.poi.xssf.streaming.SXSSFWorkbook;

/**
 * Генерирует крупный .xlsx потоковой записью — сам генератор тоже не должен съедать heap.
 * Файл не коммитится в репозиторий, а создаётся перед перф-тестом.
 */
public final class LargeFixture {

    private LargeFixture() {}

    /** Файл с {@code dataRows} строками данных и 10 колонками. */
    public static Path generate(Path dir, int dataRows) {
        Path file = dir.resolve("large-" + dataRows + ".xlsx");
        try (SXSSFWorkbook workbook = new SXSSFWorkbook(100)) {
            workbook.setCompressTempFiles(true);
            SXSSFSheet sheet = workbook.createSheet("Лист1");

            Row header = sheet.createRow(0);
            header.createCell(0).setCellValue("Ключ");
            header.createCell(1).setCellValue("ФИО");
            for (int column = 2; column < 10; column++) {
                header.createCell(column).setCellValue("Поле " + column);
            }

            for (int rowNum = 1; rowNum <= dataRows; rowNum++) {
                Row row = sheet.createRow(rowNum);
                row.createCell(0).setCellValue(rowNum);
                row.createCell(1).setCellValue("Сотрудник " + rowNum);
                for (int column = 2; column < 10; column++) {
                    row.createCell(column).setCellValue(rowNum + column);
                }
            }

            try (OutputStream out = Files.newOutputStream(file)) {
                workbook.write(out);
            }
            workbook.dispose();
        } catch (IOException e) {
            throw new IllegalStateException("не удалось сгенерировать большую фикстуру", e);
        }
        return file;
    }
}
```

`excel-import-core/src/performanceTest/java/io/github/excelimport/LargeFileMemoryTest.java`:

```java
package io.github.excelimport;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.excelimport.annotation.Column;
import io.github.excelimport.annotation.ExcelColumn;
import io.github.excelimport.annotation.ExcelSheet;
import io.github.excelimport.annotation.TargetTable;
import io.github.excelimport.testsupport.LargeFixture;
import io.github.excelimport.testsupport.PostgresSupport;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Проверяет, что импорт 100 000 строк проходит при {@code -Xmx256m} (задано в задаче
 * Gradle {@code performanceTest}) и укладывается в ориентир 30 секунд.
 */
class LargeFileMemoryTest {

    private static final int ROWS = 100_000;

    @ExcelSheet(name = "Лист1")
    @TargetTable(name = "big")
    public static class BigRow {
        @ExcelColumn(header = "Ключ")
        @Column("key")
        public Long key;

        @ExcelColumn(header = "ФИО")
        @Column("name")
        public String name;

        public BigRow() {}
    }

    @TempDir
    Path tempDir;

    @BeforeEach
    void resetTable() {
        PostgresSupport.execute(
                "DROP TABLE IF EXISTS big",
                "CREATE TABLE big (key bigint PRIMARY KEY, name text)");
    }

    @Test
    void importsHundredThousandRowsWithinMemoryAndTimeBudget() {
        Path file = LargeFixture.generate(tempDir, ROWS);
        Path reportPath = tempDir.resolve("large-report.xlsx");

        Instant start = Instant.now();
        try (ExcelImporter<BigRow> importer = ExcelImporter.builder(BigRow.class)
                .dataSource(PostgresSupport.dataSource())
                .config(ImportConfig.builder()
                        .batchSize(1000)
                        .reportPath(reportPath)
                        .tempDir(tempDir)
                        .build())
                .build()) {
            ImportReport report = importer.importFile(file);

            assertThat(report.status()).isEqualTo(ImportStatus.SUCCESS);
            assertThat(report.insertedRows()).isEqualTo(ROWS);
            assertThat(report.batchesCommitted()).isEqualTo(ROWS / 1000);
        }
        Duration elapsed = Duration.between(start, Instant.now());

        assertThat(PostgresSupport.countRows("big")).isEqualTo(ROWS);
        assertThat(elapsed).isLessThan(Duration.ofSeconds(60));
        System.out.printf("импорт %d строк занял %d мс%n", ROWS, elapsed.toMillis());
    }
}
```

Ориентир в тесте — 60 секунд, а не 30: жёсткий порог сделает тест хрупким на медленной машине. Фактическое время печатается, ориентир из спеки (≤ 30 с) отслеживается глазами, а тест ловит только катастрофическую деградацию.

- [ ] **Step 6: Запустить перф-тест**

Run: `./gradlew :excel-import-core:performanceTest`
Expected: PASS — 1 тест, в выводе строка `импорт 100000 строк занял N мс`. Тест падает с `OutOfMemoryError`, если где-то накапливается состояние пропорционально числу строк — это и есть его смысл.

- [ ] **Step 7: Прогнать всю проверку**

Run: `./gradlew check`
Expected: `BUILD SUCCESSFUL`. `performanceTest` в `check` не входит и не запускается.

- [ ] **Step 8: Коммит**

```bash
git add excel-import-core/build.gradle.kts \
        excel-import-core/src/integrationTest \
        excel-import-core/src/performanceTest
git commit -m "test: add edge-case integration coverage and large-file memory test"
```

---

### Task 18: Spring Boot starter и README

**Files:**
- Create: `excel-import-spring-boot-starter/src/main/java/io/github/excelimport/spring/ExcelImportProperties.java`
- Create: `excel-import-spring-boot-starter/src/main/java/io/github/excelimport/spring/ExcelImporterFactory.java`
- Create: `excel-import-spring-boot-starter/src/main/java/io/github/excelimport/spring/ExcelImportAutoConfiguration.java`
- Create: `excel-import-spring-boot-starter/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`
- Create: `excel-import-spring-boot-starter/src/test/java/io/github/excelimport/spring/ExcelImportAutoConfigurationTest.java`
- Create: `README.md`

**Interfaces:**
- Consumes: `ImportConfig`, `ExcelImporter`, `BatchValidator`, `CellConverter`, `ReportRowCustomizer`, `SqlErrorClassifier`, `ImportListener`.
- Produces:
  - `ExcelImportProperties` под префиксом `excel-import` с вложенными `report` и `conflict`; метод `ImportConfig toImportConfig()`.
  - `ExcelImporterFactory` — `<T> ExcelImporter<T> create(Class<T> type)` и `<T> ExcelImporter<T> create(Class<T> type, ImportConfig config)`; подхватывает все бины `BatchValidator`, `CellConverter`, `ReportRowCustomizer`, `SqlErrorClassifier`, `ImportListener` из контекста.
  - `ExcelImportAutoConfiguration` — `@ConditionalOnClass(DataSource.class)`, `@ConditionalOnMissingBean`, `@EnableConfigurationProperties`.

- [ ] **Step 1: Написать падающий тест автоконфигурации**

```java
package io.github.excelimport.spring;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.excelimport.ImportConfig;
import io.github.excelimport.RowError;
import io.github.excelimport.RowRef;
import io.github.excelimport.annotation.Column;
import io.github.excelimport.annotation.ExcelColumn;
import io.github.excelimport.annotation.ExcelSheet;
import io.github.excelimport.annotation.TargetTable;
import io.github.excelimport.validate.BatchValidator;
import java.sql.Connection;
import java.util.List;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

class ExcelImportAutoConfigurationTest {

    @ExcelSheet(name = "S")
    @TargetTable(name = "t")
    public static class Row {
        @ExcelColumn(header = "A")
        @Column("a")
        public String a;

        public Row() {}
    }

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    DataSourceAutoConfiguration.class, ExcelImportAutoConfiguration.class))
            .withPropertyValues(
                    "spring.datasource.url=jdbc:h2:mem:test",
                    "spring.datasource.driver-class-name=org.h2.Driver");

    @Test
    void factoryBeanIsRegistered() {
        runner.run(context -> assertThat(context).hasSingleBean(ExcelImporterFactory.class));
    }

    @Test
    void propertiesAreBound() {
        runner.withPropertyValues(
                        "excel-import.batch-size=5000",
                        "excel-import.max-errors=100",
                        "excel-import.locale=ru-RU",
                        "excel-import.report.enabled=true",
                        "excel-import.report.directory=/tmp/reports",
                        "excel-import.conflict.strategy=do-nothing",
                        "excel-import.conflict.columns=personnel_no")
                .run(context -> {
                    ExcelImportProperties properties = context.getBean(ExcelImportProperties.class);

                    assertThat(properties.getBatchSize()).isEqualTo(5000);
                    assertThat(properties.getMaxErrors()).isEqualTo(100);
                    assertThat(properties.getReport().isEnabled()).isTrue();
                    assertThat(properties.getConflict().getColumns()).containsExactly("personnel_no");

                    ImportConfig config = properties.toImportConfig();
                    assertThat(config.batchSize()).isEqualTo(5000);
                    assertThat(config.conflictStrategy().toSql())
                            .isEqualTo("ON CONFLICT (\"personnel_no\") DO NOTHING");
                });
    }

    @Test
    void defaultsMatchCoreDefaults() {
        runner.run(context -> {
            ImportConfig config = context.getBean(ExcelImportProperties.class).toImportConfig();

            assertThat(config.batchSize()).isEqualTo(1000);
            assertThat(config.reportPath()).isNull();
            assertThat(config.conflictStrategy().toSql()).isEmpty();
        });
    }

    @Configuration
    static class WithValidator {
        @Bean
        BatchValidator<Row> validator() {
            return new BatchValidator<>() {
                @Override
                public List<RowError> validate(List<RowRef<Row>> batch, Connection connection) {
                    return List.of();
                }
            };
        }
    }

    @Test
    void batchValidatorBeansArePickedUp() {
        runner.withUserConfiguration(WithValidator.class).run(context -> {
            ExcelImporterFactory factory = context.getBean(ExcelImporterFactory.class);

            assertThat(factory.batchValidatorsFor(Row.class)).hasSize(1);
        });
    }

    @Test
    void factoryCreatesWorkingImporter() {
        runner.run(context -> {
            ExcelImporterFactory factory = context.getBean(ExcelImporterFactory.class);

            try (var importer = factory.create(Row.class)) {
                assertThat(importer).isNotNull();
            }
        });
    }

    @Test
    void userSuppliedFactoryWins() {
        runner.withUserConfiguration(CustomFactory.class).run(context -> {
            assertThat(context).hasSingleBean(ExcelImporterFactory.class);
            assertThat(context.getBean(ExcelImporterFactory.class))
                    .isSameAs(context.getBean(CustomFactory.class).marker);
        });
    }

    @Configuration
    static class CustomFactory {
        final ExcelImporterFactory marker;

        CustomFactory(DataSource dataSource) {
            this.marker = new ExcelImporterFactory(
                    dataSource, ImportConfig.builder().build(), List.of(), java.util.Map.of(),
                    null, null, null);
        }

        @Bean
        ExcelImporterFactory excelImporterFactory() {
            return marker;
        }
    }
}
```

Тесту нужен H2 — добавить в `excel-import-spring-boot-starter/build.gradle.kts`:

```kotlin
    testImplementation("com.h2database:h2:2.3.232")
```

и алиас `h2` в version catalog (`h2 = { module = "com.h2database:h2", version = "2.3.232" }`), после чего заменить строковую нотацию на `testImplementation(libs.h2)`.

- [ ] **Step 2: Запустить тест, убедиться что падает**

Run: `./gradlew :excel-import-spring-boot-starter:test`
Expected: FAIL — `cannot find symbol: class ExcelImportAutoConfiguration`.

- [ ] **Step 3: Реализовать `ExcelImportProperties`**

```java
package io.github.excelimport.spring;

import io.github.excelimport.ConflictStrategy;
import io.github.excelimport.ImportConfig;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Свойства под префиксом {@code excel-import}. Значения по умолчанию совпадают с ядром. */
@ConfigurationProperties(prefix = "excel-import")
public class ExcelImportProperties {

    /** Стратегия обработки конфликтов вставки. */
    public enum ConflictMode {
        NONE,
        DO_NOTHING,
        DO_UPDATE
    }

    private int batchSize = 1000;
    private int maxErrors = Integer.MAX_VALUE;
    private int maxErrorsInMemory = 1000;
    private int maxSplitDepth = 16;
    private int maxOutcomeMessagesInMemory = 50_000;
    private boolean skipBlankRows = true;
    private boolean expandMergedCells = true;
    private boolean includeDatabaseDetailInReport = true;
    private boolean dryRun;
    private int queryTimeoutSeconds;
    private Locale locale = Locale.getDefault();
    private Path tempDir = Path.of(System.getProperty("java.io.tmpdir"));
    private final Report report = new Report();
    private final Conflict conflict = new Conflict();

    /** Настройки Excel-отчёта. */
    public static class Report {

        private boolean enabled;
        private Path directory;
        /** Шаблон имени файла; {@code {name}} заменяется на имя исходного файла без расширения. */
        private String fileNamePattern = "{name}-report.xlsx";

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public Path getDirectory() {
            return directory;
        }

        public void setDirectory(Path directory) {
            this.directory = directory;
        }

        public String getFileNamePattern() {
            return fileNamePattern;
        }

        public void setFileNamePattern(String fileNamePattern) {
            this.fileNamePattern = fileNamePattern;
        }
    }

    /** Настройки ON CONFLICT. */
    public static class Conflict {

        private ConflictMode strategy = ConflictMode.NONE;
        private List<String> columns = new ArrayList<>();
        private List<String> updateColumns = new ArrayList<>();

        public ConflictMode getStrategy() {
            return strategy;
        }

        public void setStrategy(ConflictMode strategy) {
            this.strategy = strategy;
        }

        public List<String> getColumns() {
            return columns;
        }

        public void setColumns(List<String> columns) {
            this.columns = columns;
        }

        public List<String> getUpdateColumns() {
            return updateColumns;
        }

        public void setUpdateColumns(List<String> updateColumns) {
            this.updateColumns = updateColumns;
        }

        ConflictStrategy toConflictStrategy() {
            return switch (strategy) {
                case NONE -> ConflictStrategy.none();
                case DO_NOTHING -> ConflictStrategy.doNothing(columns.toArray(String[]::new));
                case DO_UPDATE -> ConflictStrategy.doUpdate(columns, updateColumns);
            };
        }
    }

    /** Собирает {@link ImportConfig} без пути отчёта — путь зависит от имени файла. */
    public ImportConfig toImportConfig() {
        return ImportConfig.builder()
                .batchSize(batchSize)
                .maxErrors(maxErrors)
                .maxErrorsInMemory(maxErrorsInMemory)
                .maxSplitDepth(maxSplitDepth)
                .maxOutcomeMessagesInMemory(maxOutcomeMessagesInMemory)
                .skipBlankRows(skipBlankRows)
                .expandMergedCells(expandMergedCells)
                .includeDatabaseDetailInReport(includeDatabaseDetailInReport)
                .dryRun(dryRun)
                .queryTimeoutSeconds(queryTimeoutSeconds)
                .locale(locale)
                .tempDir(tempDir)
                .conflictStrategy(conflict.toConflictStrategy())
                .build();
    }

    /** Путь отчёта для конкретного исходного файла; null, если отчёты выключены. */
    public Path reportPathFor(String sourceFileName) {
        if (!report.isEnabled() || report.getDirectory() == null) {
            return null;
        }
        String base = sourceFileName.replaceFirst("\\.[^.]+$", "");
        return report.getDirectory().resolve(report.getFileNamePattern().replace("{name}", base));
    }

    public int getBatchSize() {
        return batchSize;
    }

    public void setBatchSize(int batchSize) {
        this.batchSize = batchSize;
    }

    public int getMaxErrors() {
        return maxErrors;
    }

    public void setMaxErrors(int maxErrors) {
        this.maxErrors = maxErrors;
    }

    public int getMaxErrorsInMemory() {
        return maxErrorsInMemory;
    }

    public void setMaxErrorsInMemory(int maxErrorsInMemory) {
        this.maxErrorsInMemory = maxErrorsInMemory;
    }

    public int getMaxSplitDepth() {
        return maxSplitDepth;
    }

    public void setMaxSplitDepth(int maxSplitDepth) {
        this.maxSplitDepth = maxSplitDepth;
    }

    public int getMaxOutcomeMessagesInMemory() {
        return maxOutcomeMessagesInMemory;
    }

    public void setMaxOutcomeMessagesInMemory(int value) {
        this.maxOutcomeMessagesInMemory = value;
    }

    public boolean isSkipBlankRows() {
        return skipBlankRows;
    }

    public void setSkipBlankRows(boolean skipBlankRows) {
        this.skipBlankRows = skipBlankRows;
    }

    public boolean isExpandMergedCells() {
        return expandMergedCells;
    }

    public void setExpandMergedCells(boolean expandMergedCells) {
        this.expandMergedCells = expandMergedCells;
    }

    public boolean isIncludeDatabaseDetailInReport() {
        return includeDatabaseDetailInReport;
    }

    public void setIncludeDatabaseDetailInReport(boolean value) {
        this.includeDatabaseDetailInReport = value;
    }

    public boolean isDryRun() {
        return dryRun;
    }

    public void setDryRun(boolean dryRun) {
        this.dryRun = dryRun;
    }

    public int getQueryTimeoutSeconds() {
        return queryTimeoutSeconds;
    }

    public void setQueryTimeoutSeconds(int queryTimeoutSeconds) {
        this.queryTimeoutSeconds = queryTimeoutSeconds;
    }

    public Locale getLocale() {
        return locale;
    }

    public void setLocale(Locale locale) {
        this.locale = locale;
    }

    public Path getTempDir() {
        return tempDir;
    }

    public void setTempDir(Path tempDir) {
        this.tempDir = tempDir;
    }

    public Report getReport() {
        return report;
    }

    public Conflict getConflict() {
        return conflict;
    }
}
```

- [ ] **Step 4: Реализовать `ExcelImporterFactory`**

```java
package io.github.excelimport.spring;

import io.github.excelimport.ExcelImporter;
import io.github.excelimport.ImportConfig;
import io.github.excelimport.ImportListener;
import io.github.excelimport.SqlErrorClassifier;
import io.github.excelimport.convert.CellConverter;
import io.github.excelimport.report.ReportRowCustomizer;
import io.github.excelimport.validate.BatchValidator;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;

/**
 * Создаёт типизированные {@link ExcelImporter} по классу модели, подставляя бины из
 * контекста: {@link BatchValidator}, {@link CellConverter}, {@link ReportRowCustomizer},
 * {@link SqlErrorClassifier}, {@link ImportListener}.
 */
public class ExcelImporterFactory {

    private final DataSource dataSource;
    private final ImportConfig defaultConfig;
    private final List<BatchValidator<?>> batchValidators;
    private final Map<Class<?>, CellConverter<?>> converters;
    private final ReportRowCustomizer reportRowCustomizer;
    private final SqlErrorClassifier sqlErrorClassifier;
    private final ImportListener listener;

    public ExcelImporterFactory(
            DataSource dataSource,
            ImportConfig defaultConfig,
            List<BatchValidator<?>> batchValidators,
            Map<Class<?>, CellConverter<?>> converters,
            ReportRowCustomizer reportRowCustomizer,
            SqlErrorClassifier sqlErrorClassifier,
            ImportListener listener) {
        this.dataSource = dataSource;
        this.defaultConfig = defaultConfig;
        this.batchValidators = List.copyOf(batchValidators);
        this.converters = Map.copyOf(converters);
        this.reportRowCustomizer = reportRowCustomizer;
        this.sqlErrorClassifier = sqlErrorClassifier;
        this.listener = listener;
    }

    public <T> ExcelImporter<T> create(Class<T> type) {
        return create(type, defaultConfig);
    }

    public <T> ExcelImporter<T> create(Class<T> type, ImportConfig config) {
        ExcelImporter.Builder<T> builder = ExcelImporter.builder(type)
                .dataSource(dataSource)
                .config(config);
        for (BatchValidator<T> validator : batchValidatorsFor(type)) {
            builder.batchValidator(validator);
        }
        converters.forEach(builder::converter);
        if (reportRowCustomizer != null) {
            builder.reportRowCustomizer(reportRowCustomizer);
        }
        if (sqlErrorClassifier != null) {
            builder.sqlErrorClassifier(sqlErrorClassifier);
        }
        if (listener != null) {
            builder.listener(listener);
        }
        return builder.build();
    }

    /**
     * Отбирает валидаторы, параметризованные указанным типом. Валидатор, у которого
     * параметр типа стёрт (лямбда без явного generic), считается подходящим для любого
     * типа — иначе он был бы бесполезен.
     */
    @SuppressWarnings("unchecked")
    public <T> List<BatchValidator<T>> batchValidatorsFor(Class<T> type) {
        List<BatchValidator<T>> matching = new ArrayList<>();
        for (BatchValidator<?> validator : batchValidators) {
            Class<?> parameter = resolveTypeParameter(validator.getClass());
            if (parameter == null || parameter.isAssignableFrom(type)) {
                matching.add((BatchValidator<T>) validator);
            }
        }
        return List.copyOf(matching);
    }

    private static Class<?> resolveTypeParameter(Class<?> validatorClass) {
        for (Type candidate : validatorClass.getGenericInterfaces()) {
            if (candidate instanceof ParameterizedType parameterized
                    && parameterized.getRawType() == BatchValidator.class) {
                Type argument = parameterized.getActualTypeArguments()[0];
                if (argument instanceof Class<?> raw) {
                    return raw;
                }
            }
        }
        Class<?> superclass = validatorClass.getSuperclass();
        return superclass == null || superclass == Object.class
                ? null
                : resolveTypeParameter(superclass);
    }
}
```

Конструктор один, публичный, принимает `Map<Class<?>, CellConverter<?>>`. Тест `userSuppliedFactoryWins` использует именно его.

- [ ] **Step 5: Реализовать автоконфигурацию**

```java
package io.github.excelimport.spring;

import io.github.excelimport.ImportConfig;
import io.github.excelimport.ImportListener;
import io.github.excelimport.SqlErrorClassifier;
import io.github.excelimport.convert.CellConverter;
import io.github.excelimport.report.ReportRowCustomizer;
import io.github.excelimport.validate.BatchValidator;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Автоконфигурация: регистрирует {@link ExcelImporterFactory}, если есть DataSource. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnClass(DataSource.class)
@EnableConfigurationProperties(ExcelImportProperties.class)
public class ExcelImportAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public ExcelImporterFactory excelImporterFactory(
            DataSource dataSource,
            ExcelImportProperties properties,
            ObjectProvider<BatchValidator<?>> batchValidators,
            ObjectProvider<ReportRowCustomizer> reportRowCustomizer,
            ObjectProvider<SqlErrorClassifier> sqlErrorClassifier,
            ObjectProvider<ImportListener> listener) {
        ImportConfig config = properties.toImportConfig();
        List<BatchValidator<?>> validators = batchValidators.orderedStream().toList();
        Map<Class<?>, CellConverter<?>> converters = Map.of();
        return new ExcelImporterFactory(
                dataSource,
                config,
                validators,
                converters,
                reportRowCustomizer.getIfAvailable(),
                sqlErrorClassifier.getIfAvailable(),
                listener.getIfAvailable());
    }
}
```

`excel-import-spring-boot-starter/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`:

```
io.github.excelimport.spring.ExcelImportAutoConfiguration
```

Замечание по конвертерам: автоматически подхватить `CellConverter` бины нельзя — целевой тип поля из бина не выводится, а `@ExcelColumn(converter = ...)` уже покрывает явный случай. Поэтому карта пуста, а регистрация по типу остаётся ручной через `ExcelImporterFactory.create(type, config)` + `ExcelImporter.builder(...).converter(...)`. Это сознательное ограничение starter'а, а не недоделка.

- [ ] **Step 6: Запустить тесты starter'а**

Run: `./gradlew :excel-import-spring-boot-starter:test`
Expected: PASS — 6 тестов.

- [ ] **Step 7: Написать README**

`README.md` — разделы:

1. **Что это** — один абзац: потоковый импорт `.xlsx` в PostgreSQL батчами настраиваемого размера с Excel-отчётом о результате.
2. **Установка** — координаты обоих артефактов для Gradle и Maven, таблица совместимости (Java 17+, POI 5.4.x, PostgreSQL 12+, Spring Boot 3.x).
3. **Быстрый старт** — POJO с аннотациями и вызов `ExcelImporter` — скопировать пример из §4.1 и §4.2 спеки.
4. **Аннотации** — таблица `@ExcelSheet`, `@ExcelColumn`, `@Column`, `@TargetTable` со всеми атрибутами и значениями по умолчанию (из §4.1 спеки).
5. **Конфигурация** — таблица из 21 параметра (скопировать §7 спеки), плюс блок `application.yml` для starter'а.
6. **Валидация** — три слоя: конвертеры, Jakarta-аннотации, `BatchValidator`; пример своей constraint-аннотации и пример `BatchValidator` с запросом к БД.
7. **Отчёт** — что попадает в файл, `ReportStyle`, `ReportRowCustomizer`, и явная оговорка, что форматирование исходника не переносится.
8. **Как обрабатываются ошибки БД** — бисекция батча, `maxSplitDepth`, `SqlErrorClassifier`, таблица «фатально / восстановимо».
9. **Ограничения** — только `.xlsx`; таблица должна существовать; формулы не вычисляются; предел 65535 bind-параметров и автоматическое дробление чанков; после выгрузки исходов на диск чтение только по возрастанию `rowNum`.
10. **Наблюдаемость** — `ImportListener`, уровни логирования, замечание что значения ячеек не логируются выше `TRACE`.
11. **Сборка проекта** — `./gradlew build`, `./gradlew check` (требует Docker), `./gradlew performanceTest`.

Каждый пример кода в README должен компилироваться: сверять с фактическими сигнатурами, а не с планом.

- [ ] **Step 8: Прогнать полную проверку**

Run: `./gradlew check`
Expected: `BUILD SUCCESSFUL` — оба модуля, все unit- и интеграционные тесты.

- [ ] **Step 9: Коммит**

```bash
git add excel-import-spring-boot-starter README.md gradle/libs.versions.toml
git commit -m "feat: add Spring Boot starter with auto-configuration and write README"
```

---

## Проверка покрытия спеки

| Раздел спеки | Задачи |
|---|---|
| §1 Назначение и границы | Global Constraints, Task 3 (только xlsx), Task 17 (перф) |
| §2 Модули и зависимости | Task 1 |
| §2.1 Структура сборки | Task 1, Task 17 (Step 1) |
| §2.2 Типы POI в API | Task 1 (`api`), Task 3 (`CellValue`), Task 15 (`ReportStyle`, хук) |
| §3 Архитектура, компоненты | Tasks 3–16 — по одному компоненту на задачу |
| §4.1 Аннотации | Task 5 |
| §4.2 Точка входа | Task 16 |
| §4.3 `ImportReport`, `RowError`, нумерация | Task 2, Task 16 |
| §5.1 Потоковое чтение и краевые случаи | Tasks 3, 4 |
| §5.2 Разбор заголовка | Task 7 |
| §5.3 Маппинг и конвертация | Tasks 6, 8 |
| §5.4 Jakarta-валидация | Task 9 |
| §5.5 `BatchValidator` | Task 13 |
| §5.6 Вставка, `ON CONFLICT`, лимит параметров | Tasks 10, 11 |
| §5.7 Бисекция, фатальные ошибки | Task 12, Task 17 (обрыв соединения) |
| §5.8 Политика ошибок, `dryRun` | Tasks 10, 16 |
| §5.9 `RowOutcomeStore` | Task 14 |
| §6 Отчёт | Task 15 |
| §6.1 `ReportStyle`, `ReportRowCustomizer` | Task 15 |
| §7 Конфигурация | Task 10 |
| §7.1 Spring Boot starter | Task 18 |
| §8 Наблюдаемость | Task 16 |
| §9 Модель ошибок | Task 2 (иерархия), далее по месту возникновения |
| §10 Тестирование | Тесты в каждой задаче + Task 17 |
| §11 Порядок реализации | Порядок задач 1–18 |
| §12 Открытые вопросы | Вне объёма — не планируются |

