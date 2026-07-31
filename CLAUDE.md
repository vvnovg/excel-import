# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Что это за проект

Java-библиотека `excel-import`: потоковый импорт `.xlsx` в PostgreSQL. Читает файл через
POI SAX-стриминг (потребление heap не зависит от числа строк), отображает строки на
пользовательские POJO по аннотациям, валидирует (Jakarta Bean Validation), вставляет
multi-row `INSERT` батчами и генерирует Excel-отчёт с зелёной/красной разметкой строк.

Три источника истины, в порядке приоритета для разных вопросов:

- `README.md` — пользовательский контракт (публичное API, свойства стартера, поведение).
  Меняете публичное API — правьте README в том же коммите; расхождение README и кода
  считается багом.
- `docs/superpowers/specs/2026-07-29-excel-to-postgres-importer-design.md` — дизайн-спека
  (ссылки вида «§5.1» ведут туда). Нужен точный контракт компонента — сюда.
- `docs/superpowers/plans/2026-07-29-excel-to-postgres-importer.md` — поэтапный план
  (18 задач). Чекбоксы в нём **не проставлялись**; фактический статус — в
  `.superpowers/sdd/progress.md`.

## Состояние работы

Все 18 задач плана выполнены; ветка `feature/excel-importer` прошла финальное ревью.
Реализовано всё: чтение, маппинг, конвертеры, валидация, вставка с бисекцией,
`RowOutcomeStore` с выгрузкой на диск, `ReportWriter`, фасад `ExcelImporter`,
интеграционные и перф-тесты, Spring Boot starter, README.

Текущая фаза — исправления по находкам финального ревью. Статус, номера коммитов,
отклонения от плана и незакрытые находки — `.superpowers/sdd/progress.md` и
`.superpowers/sdd/final-review-fixes-report.md`. **Читайте progress.md перед началом
работы**: там же перечислены известные мелкие дефекты, до которых ещё не дошли руки.
Каталог `.superpowers/` в `.gitignore` (там же архив diff'ов по каждой задаче) — он
локальный, в коммиты не попадает.

Параллельная работа: лендинг библиотеки (Astro, EN/RU, Cloudflare Pages). Спека и план —
в `docs/` этого репозитория, но **код лендинга живёт в отдельном репозитории**
`/Users/vvnovg/projects/excel-import-site`; коммиты по лендингу идут туда, а не сюда.

## Сборка и тесты

Gradle 8.12 (Kotlin DSL), multi-project, wrapper закоммичен — используйте `./gradlew`.

```bash
./gradlew build              # компиляция + unit-тесты + javadoc/sources jar
./gradlew test               # только unit-тесты (быстрые, без Docker)
./gradlew check              # = test + integrationTest — ТРЕБУЕТ запущенный Docker (Testcontainers)
./gradlew integrationTest    # только интеграционные (Testcontainers PostgreSQL 16), нужен Docker
./gradlew performanceTest    # перф-тесты, в check НЕ входит, maxHeap=256m, запускается вручную

# один тест (любой из вариантов):
./gradlew test --tests "org.novgorodtsev.excelimport.internal.write.SqlBuilderTest"
./gradlew test --tests "*SqlBuilderTest"
# один метод:
./gradlew test --tests "org.novgorodtsev.excelimport.internal.write.SqlBuilderTest" --tests "*.buildsMultiRowInsert*"
# то же для других source sets:
./gradlew integrationTest --tests "*ExcelImporterIT"
```

- Компиляция с `-Xlint:all -Werror` — любое предупреждение ломает сборку. Чините варнинги,
  не глушите. Единственное исключение (осознанное и точечное): `-Xlint:-processing` на
  `compileJava` стартера — spring-boot-configuration-processor не «claim'ит» аннотации.
- Кодировка UTF-8. Java 17 (toolchain фиксируется Gradle, локальный JDK не важен).
- Все версии зависимостей — только в `gradle/libs.versions.toml`. В `build.gradle.kts`
  версий быть не должно.
- `integrationTest` и `performanceTest` — отдельные source sets в `excel-import-core`
  (`src/integrationTest/java`, `src/performanceTest/java`), наследуют `testImplementation`
  и видят классы `test` (включая `testsupport/`).
- Testcontainers: один контейнер `postgres:16-alpine` на весь прогон —
  `integrationTest/.../testsupport/PostgresSupport.java` (static-инициализация +
  shutdown hook). В обеих задачах явно задан `systemProperty("api.version", "1.44")`:
  docker-java по умолчанию просит API 1.32, а Docker Engine 29+ её не принимает. При
  обновлении testcontainers эту строку не потеряйте (она в двух местах).
- `LargeFileMemoryTest` проверяет 100 000 строк под `-Xmx256m` — потолок heap задан в
  `performanceTest`-задаче, не в тесте.
- Коммиты — Conventional Commits (`feat:`, `fix:`, `docs:`).

## Архитектура (большая картина)

Два прохода по файлу — это ключевое решение, и оно вынужденное: SAX-стриминг не позволяет
править исходный файл на месте, а грузить книгу в `XSSFWorkbook` ради раскраски убило бы
выигрыш от стриминга. Между проходами в памяти живёт только компактная карта исходов строк.

```
проход 1:  .xlsx → StreamingSheetReader (XSSFReader+SAX) → RawRow
           → RowMapper<T> (@ExcelColumn + CellConverter) → T
           → BeanValidator (Jakarta) → батч → BatchValidator (SPI, GuardedConnection)
           → BatchProcessor (multi-row INSERT, 1 tx на батч, бисекция сбойного) → RowOutcomeStore
проход 2:  .xlsx → ReportWriter (повторное чтение + SXSSFWorkbook) → report.xlsx
```

Точка входа — `ExcelImporter` (публичный фасад, builder, `AutoCloseable`, потокобезопасен
и переиспользуем). Сборка модели, SQL-шаблона и `ValidatorFactory` — один раз в
конструкторе; один вызов `importFile` — один изолированный прогон, вся его оркестрация
живёт в `internal/ImportRun`. Каждый компонент — отдельный интерфейс с одной
ответственностью, тестируемый изолированно; полный список и контракты — §3.1 спеки.

## Ключевые конвенции (не очевидны из кода, важны)

- **Граница публичного API.** Публичный контракт — пакет `org.novgorodtsev.excelimport` и его
  подпакеты, **кроме** `org.novgorodtsev.excelimport.internal.*` (приватный, без гарантий
  совместимости). Не тащите типы из `internal` в публичные сигнатуры и наоборот.
  Обратите внимание на пары: SPI-интерфейс публичен (`outcome/RowOutcomeStore`,
  `validate/BatchValidator`, `report/ReportRowCustomizer`, `SqlErrorClassifier`), а
  реализация лежит в `internal` (`internal/outcome/SpillableRowOutcomeStore`,
  `internal/write/DefaultSqlErrorClassifier`, `internal/report/ReportWriter`).
- **POI как часть публичного ABI.** `poi`/`poi-ooxml` экспортированы как `api` сознательно:
  потребитель работает с привычными POI-типами (`CellType`, `XSSFColor`, `SXSSFRow` и т.д.),
  а не с обёртками. Подъём мажорной версии POI — breaking change. На первом проходе POI
  `Row`/`Cell` физически не существует (SAX-события), поэтому `CellConverter` получает лёгкий
  `CellValue`, а не POI `Cell`. На втором проходе (отчёт) книга наша, POI-объекты реальны.
- **Ядро не зависит от Spring и не тянет драйвер PostgreSQL в рантайм** — драйвер
  `compileOnly` + `testImplementation`, в рантайме приходит через `DataSource` потребителя.
- **Нумерация строк.** В конфигурации и аннотациях (`headerRow`, `firstDataRow`,
  `@ExcelColumn.index`) — 0-based (как в POI). Во всём, что видит пользователь
  (`RowError.rowNum`, сообщения, Excel-отчёт) — 1-based (как в Excel). Конвертация ровно в
  одном месте, на границе публичного API; внутри ядра всё 0-based.
- **`headerRow`/`firstDataRow` из `ImportConfig` и `@ExcelSheet` сводятся независимо.**
  У `ImportConfig` есть флаги «задано явно» (`headerRowExplicit`, `firstDataRowExplicit`) —
  без них нельзя отличить «не трогали» от «выставили 0». Смена листа не сбрасывает
  `headerRow` из аннотации, и наоборот. Логика сведения — в конструкторе `ExcelImporter`;
  `ReportWriter` обязан использовать тот же эффективный `headerRow` (это была Critical-находка
  ревью, коммит c55b229).
- **Ошибки уровня строки никогда не выбрасываются наружу** — только попадают в `RowError`.
  Наружу идут только структурные/фатальные: `FileStructureException`,
  `ImportAbortedException`, `ReportGenerationException` (см. §9 спеки).
- **Безопасность данных.** Ни одно значение ячейки не логируется на уровне выше `TRACE`
  (данные могут быть персональными). `includeDatabaseDetailInReport` позволяет отключить
  попадание `detail` из PG-ошибок в отчёт.
- **Лимит параметров PostgreSQL 65535** на запрос учитывается автоматически: эффективный
  размер чанка = `min(batchSize, 65535 / columnCount)`, избыточный батч дробится внутри
  транзакции. Не дублируйте эту логику вручную.
- **`BatchValidator` получает `GuardedConnection`** — обёртку, запрещающую commit/rollback/
  close на транзакции импорта. Это защита от выстрела в ногу, а не security boundary
  (обходится через `unwrap`); не переусложняйте её.
- **Тестовые фикстуры `.xlsx`** — собираются в памяти: `testsupport/XlsxFixtures.java`,
  `testsupport/RawRows.java` (построение `RawRow`). Готовых `.xlsx` в `src/test/resources`
  нет и заводить их не нужно.
- **POI логирует через log4j-api** — мост `log4j-to-slf4j` подключён только как
  `testRuntimeOnly`, в рантайм библиотеки не попадает.

## Структура модулей

- `excel-import-core` — всё ядро. Публичные пакеты: корневой (`ExcelImporter`,
  `ImportConfig`, `ImportReport`, `RowError`, перечисления), `annotation`, `convert`,
  `exception`, `outcome`, `report`, `validate`. Приватные:
  `internal/{convert,map,outcome,read,report,validate,write}` + `internal/ImportRun`.
- `excel-import-spring-boot-starter` — `ExcelImportAutoConfiguration`,
  `ExcelImportProperties` (префикс `excel-import`), `ExcelImporterFactory`. Регистрируется
  через `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`,
  включается при наличии `DataSource`, отключается своим бином `ExcelImporterFactory`.
  Тесты — на `ApplicationContextRunner` + H2 (Spring-модуль Docker не требует).

## Сообщения валидации

`ValidationMessages.properties` / `ValidationMessages_ru.properties` в
`excel-import-core/src/main/resources/io/github/excelimport/`. При правке сообщений
учитывайте интерполяцию и порядок подстановки — в истории были фиксы именно тут
(экранирование скобок, EL-детекция, сортировка ошибок).
