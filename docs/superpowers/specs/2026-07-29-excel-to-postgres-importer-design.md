# Excel → PostgreSQL Importer: спецификация

**Дата:** 2026-07-29
**Статус:** утверждено к реализации

## 1. Назначение

Java-библиотека, которая загружает `.xlsx`-файл, отображает строки на пользовательские
Java-объекты, валидирует их и вставляет в PostgreSQL пакетами настраиваемого размера.
По итогам работы формируется Excel-отчёт: копия исходного файла, где успешно
загруженные строки залиты зелёным, отклонённые — красным, а в добавленном столбце
указана причина отклонения.

Библиотека рассчитана на файлы, не помещающиеся в память: чтение потоковое,
потребление памяти не зависит от числа строк.

### Что library не делает

- Не поддерживает устаревший формат `.xls` (BIFF/HSSF) — только OOXML `.xlsx`.
- Не создаёт и не мигрирует схему БД: целевая таблица должна существовать.
- Не вычисляет формулы. Читается кэшированное значение формулы; если кэш пуст,
  ячейка считается пустой (поведение настраивается, см. §7).
- Не выполняет UPDATE/DELETE. Только INSERT, опционально с `ON CONFLICT`.

## 2. Модули и зависимости

| Модуль | Назначение | Зависимости |
|---|---|---|
| `excel-import-core` | Всё ядро: чтение, маппинг, валидация, вставка, отчёт | `api`: POI `poi` + `poi-ooxml`, Jakarta Bean Validation API, SLF4J API. `implementation`: Hibernate Validator. `javax.sql.DataSource` — из JDK |
| `excel-import-spring-boot-starter` | Auto-configuration, `@ConfigurationProperties`, бин `ExcelImporter` | `excel-import-core`, Spring Boot autoconfigure |

- Java 17, сборка Gradle (Kotlin DSL), multi-project.
- Ядро не зависит от Spring и от драйвера PostgreSQL (драйвер — `compileOnly` +
  `testImplementation`; в рантайме приходит через `DataSource` потребителя).
- Публичный API — пакет `io.github.excelimport`; всё под `...internal.*` считается
  приватным и не покрывается гарантиями совместимости.

### 2.1 Структура сборки Gradle

```
excel-import/
├── settings.gradle.kts          // include("excel-import-core", "excel-import-spring-boot-starter")
├── build.gradle.kts             // общие плагины и настройки для subprojects
├── gradle/libs.versions.toml    // version catalog — единственное место с версиями
├── gradle/wrapper/              // wrapper коммитится в репозиторий
├── excel-import-core/
│   └── build.gradle.kts
└── excel-import-spring-boot-starter/
    └── build.gradle.kts
```

Решения по сборке:

- **Kotlin DSL** (`.gradle.kts`) — типизированный, с автодополнением в IDE.
- **Version catalog** (`gradle/libs.versions.toml`) — все версии в одном файле,
  в модулях только `implementation(libs.poi.ooxml)`. Никаких версий в `build.gradle.kts`.
- **Gradle wrapper коммитится**, версия фиксируется; сборка не зависит от локально
  установленного Gradle.
- **Конвенции в корневом `build.gradle.kts`** через блок `subprojects { }`: Java
  toolchain 17, `-Xlint:all -Werror`, кодировка UTF-8, JUnit 5 platform,
  `maven-publish` + `signing` для публикации, репродусибл-архивы
  (`isPreserveFileTimestamps = false`).
- **API vs implementation.** Плагин `java-library` обязателен для обоих модулей.
  Через `api` торчат `poi`/`poi-ooxml`, Jakarta Validation API и SLF4J API — типы
  из них встречаются в публичных сигнатурах (см. §2.2). Hibernate Validator —
  `implementation`: это реализация за Jakarta-фасадом, наружу она не видна.
- **Тестовые задачи разделены**: `test` (unit, быстрые, идут в CI на каждый коммит)
  и `integrationTest` (Testcontainers, отдельный source set `src/integrationTest/java`,
  задача типа `Test`). `check` зависит от `integrationTest` — то есть `./gradlew check`
  требует доступного Docker-демона; в CI Docker есть, это принятое условие.
  Перф-тесты — третий source set `src/performanceTest/java`, в `check` не входит
  и запускается вручную.
- **Тестовые фикстуры** `.xlsx` лежат в `src/test/resources/fixtures/`. Крупная
  фикстура на 100 000 строк не коммитится, а генерируется задачей
  `generateLargeFixture` перед `performanceTest`.
- Публикация в Maven Central: `maven-publish`, POM с лицензией и SCM, подпись GPG
  через `signing`, публикация обоих модулей с одной версией.

### 2.2 Типы POI в публичном API

POI сознательно является частью публичного контракта: потребитель работает с
привычными POI-типами вместо параллельной иерархии-обёртки.

**Где POI-типы появляются в публичных сигнатурах:**

| Место | POI-типы |
|---|---|
| `CellValue` (вход `CellConverter`) | `CellType`, `CellAddress`, `DateUtil` для serial date |
| `ReportStyle` | `IndexedColors`, `XSSFColor`, `FillPatternType`, `HorizontalAlignment`, `short` формат из `DataFormat` |
| `ReportRowCustomizer` (хук, §6.1) | `SXSSFRow`, `Cell`, `CellStyle`, `SXSSFWorkbook` |
| `SheetSelector` | — (только имя/индекс) |
| Исключения структуры | `CellReference` в сообщениях |

**Чего в публичном API нет и почему.** На первом проходе POI-объектов `Row` и `Cell`
физически не существует: чтение идёт через `XSSFReader` + SAX, на входе — XML-события,
а не объектная модель. Поэтому `CellConverter` получает `CellValue` — свой лёгкий
тип, несущий POI-семантику (`CellType`, `CellAddress`, флаг «формат ячейки —
дата»), а не POI `Cell`. Отдавать наружу `Cell` на этом проходе можно было бы только
через материализацию книги в память, что противоречит требованию постоянного
потребления памяти (§1).

```java
public interface CellValue {
    CellAddress address();
    CellType type();               // POI: STRING, NUMERIC, BOOLEAN, FORMULA, ERROR, BLANK
    boolean dateFormatted();       // результат DateUtil.isADateFormat по стилю ячейки
    String asString();             // null для пустой ячейки
    Double asNumeric();            // null, если type() != NUMERIC
    Boolean asBoolean();
    String formula();              // null, если ячейка не формула
    byte errorCode();              // POI FormulaError, значим при type() == ERROR
}
```

На втором проходе (генерация отчёта) книга наша и пишется через `SXSSFWorkbook` —
там POI-объекты реальны и отдаются наружу без ограничений.

**Последствие для версионирования.** Мажорная версия POI входит в наш ABI: её
подъём — breaking change библиотеки и требует мажорного релиза. Версия POI
фиксируется в version catalog и указывается в README в таблице совместимости.

## 3. Архитектура

Импорт состоит из двух проходов по файлу.

```
                       ┌──────────────────────────────────────────┐
   .xlsx  ──proход 1──▶│ StreamingSheetReader (XSSFReader + SAX)  │
                       └───────────────┬──────────────────────────┘
                                       │ RawRow (rowNum, Map<colIdx, String/typed>)
                                       ▼
                               ┌───────────────┐
                               │  RowMapper<T> │  @ExcelColumn + CellConverter
                               └───────┬───────┘
                                       │ T + ошибки конвертации
                                       ▼
                             ┌─────────────────────┐
                             │ BeanValidator       │  Jakarta constraints
                             └─────────┬───────────┘
                                       │ валидные строки
                                       ▼
                             ┌─────────────────────┐
                             │ BatchAccumulator    │  копит до batchSize
                             └─────────┬───────────┘
                                       │ полный батч
                                       ▼
                             ┌─────────────────────┐
                             │ BatchValidator<T>   │  пользовательский SPI
                             └─────────┬───────────┘
                                       ▼
                             ┌─────────────────────┐
                             │ BatchWriter (JDBC)  │  multi-row INSERT, 1 tx на батч
                             └─────────┬───────────┘
                                       │ статус каждой строки
                                       ▼
                             ┌─────────────────────┐
                             │ RowOutcomeStore     │  rowNum → (status, message)
                             └─────────┬───────────┘
                                       │
   .xlsx  ──проход 2──▶ ReportWriter (StreamingSheetReader + SXSSFWorkbook) ──▶ report.xlsx
```

Разделение на два прохода вынужденное: потоковое чтение через SAX не позволяет
править исходный файл на месте, а загрузка книги в `XSSFWorkbook` ради раскраски
свела бы на нет выигрыш от стриминга. Между проходами в памяти живёт только
компактная карта исходов строк.

### 3.1 Компоненты ядра

Каждый компонент — отдельный интерфейс с одной ответственностью, тестируемый
изолированно.

| Компонент | Ответственность | Ключевой контракт |
|---|---|---|
| `StreamingSheetReader` | Читает лист по строкам, отдаёт `RawRow` | `void forEachRow(InputStream, SheetSelector, Consumer<RawRow>)` |
| `RowMapper<T>` | `RawRow` → `T`, ошибки конвертации | `MappingResult<T> map(RawRow)` |
| `CellConverter<V>` | `String`/`CellValue` → значение поля | `V convert(CellValue, ConversionContext)` |
| `BeanValidator` | Jakarta-валидация объекта | `List<RowError> validate(T, int rowNum)` |
| `BatchValidator<T>` | Межстрочные проверки (SPI) | `List<RowError> validate(List<RowRef<T>>, Connection)` |
| `BatchWriter<T>` | Multi-row INSERT + деление при сбое | `BatchResult write(List<RowRef<T>>)` |
| `RowOutcomeStore` | Хранение исходов строк с выгрузкой на диск | `void put(int rowNum, RowOutcome)`, `RowOutcome get(int)` |
| `ReportWriter` | Второй проход, генерация отчёта | `void write(Path source, RowOutcomeStore, Path target)` |
| `ExcelImporter<T>` | Фасад, оркестрация | `ImportReport importFile(Path, Class<T>)` |

## 4. Публичный API

### 4.1 Описание маппинга: аннотации на POJO

```java
@ExcelSheet(name = "Сотрудники", headerRow = 0)
@TargetTable(schema = "hr", name = "employee")
public class EmployeeRow {

    @ExcelColumn(header = "Табельный номер")
    @Column("personnel_no")
    @NotNull
    private Long personnelNo;

    @ExcelColumn(header = "ФИО")
    @Column("full_name")
    @NotBlank
    @Size(max = 200)
    private String fullName;

    @ExcelColumn(header = "Дата приёма", formats = {"dd.MM.yyyy", "yyyy-MM-dd"})
    @Column("hired_at")
    @PastOrPresent
    private LocalDate hiredAt;

    @ExcelColumn(header = "Оклад")
    @Column("salary")
    @DecimalMin("0.00")
    private BigDecimal salary;

    @ExcelColumn(header = "Подразделение", required = false)
    @Column("department")
    private String department;

    // геттеры/сеттеры
}
```

**`@ExcelSheet`** — `name` или `index` (ровно одно из двух), `headerRow` (0-based,
по умолчанию 0), `firstDataRow` (по умолчанию `headerRow + 1`).

**`@ExcelColumn`** — идентификация колонки одним из способов:
- `header` — по тексту в строке заголовка (сравнение с `trim` и нормализацией
  пробелов, регистронезависимо; настраивается);
- `index` — по 0-based индексу;
- `letter` — по букве колонки (`"A"`, `"AB"`).

Дополнительно: `required` (по умолчанию `true` — отсутствие колонки в файле даёт
фатальную ошибку структуры), `formats` (для дат/чисел), `trim` (по умолчанию `true`),
`emptyAsNull` (по умолчанию `true`), `converter` (класс `CellConverter`).

**`@Column`** — имя колонки в БД. Если аннотация отсутствует, имя выводится из имени
поля через `NamingStrategy` (по умолчанию `snake_case`).

**`@TargetTable`** — схема и таблица. Может быть переопределено в `ImportConfig`.

Поля без `@ExcelColumn` игнорируются при чтении. Поле с `@Column`, но без
`@ExcelColumn`, участвует во вставке — значение туда кладёт `BatchValidator` или
`RowEnricher` (например, `import_run_id`).

### 4.2 Точка входа

```java
ImportConfig config = ImportConfig.builder()
        .batchSize(1_000)
        .conflictStrategy(ConflictStrategy.doNothing("personnel_no"))
        .reportPath(Path.of("/var/reports/employees-report.xlsx"))
        .maxErrors(10_000)
        .build();

ExcelImporter<EmployeeRow> importer = ExcelImporter.builder(EmployeeRow.class)
        .dataSource(dataSource)
        .config(config)
        .batchValidator(new NoDuplicatePersonnelNoValidator())
        .reportRowCustomizer(new HrReportCustomizer())   // опционально, см. §6.1
        .listener(new LoggingImportListener())
        .build();

ImportReport report = importer.importFile(Path.of("employees.xlsx"));

System.out.printf("Всего %d, вставлено %d, отклонено %d, отчёт: %s%n",
        report.totalRows(), report.insertedRows(), report.rejectedRows(),
        report.reportPath());
```

`ExcelImporter` потокобезопасен и переиспользуем: тяжёлые вещи (разбор аннотаций,
компиляция `PreparedStatement`-шаблона, `ValidatorFactory`) считаются один раз при
сборке. Один вызов `importFile` — один изолированный `ImportRun`.

Перегрузки: `importFile(Path)`, `importFile(InputStream, String sourceName)`. Для
`InputStream` файл сначала копируется во временный, потому что нужны два прохода;
временный файл удаляется в `finally`.

### 4.3 Результат

```java
public record ImportReport(
        UUID runId,
        String sourceName,
        long totalRows,          // строк данных прочитано
        long insertedRows,
        long rejectedRows,
        long batchesCommitted,
        Duration duration,
        Path reportPath,         // null, если отчёт отключён
        List<RowError> errors,   // усечён до maxErrorsInMemory
        boolean errorLimitReached,
        ImportStatus status      // SUCCESS | PARTIAL | FAILED
) {}

public record RowError(
        int rowNum,              // 1-based, как в Excel
        String columnHeader,     // null для ошибок уровня строки/батча
        String rawValue,
        ErrorKind kind,          // STRUCTURE | CONVERSION | CONSTRAINT | BATCH | DATABASE
        String code,             // "NotNull", "23505", "DUPLICATE_KEY", ...
        String message           // человекочитаемое, локализуемое
) {}
```

`status`: `SUCCESS` — все строки вставлены; `PARTIAL` — часть отклонена;
`FAILED` — импорт прерван (структурная ошибка, превышен лимит ошибок, фатальный сбой БД).

**Нумерация строк.** В конфигурации и аннотациях (`headerRow`, `firstDataRow`,
`@ExcelColumn.index`) индексы 0-based — как в API POI. Во всём, что видит конечный
пользователь (`RowError.rowNum`, сообщения, Excel-отчёт), номера 1-based — как в
интерфейсе Excel. Конвертация происходит ровно в одном месте, на границе публичного
API; внутри ядра всё 0-based.

## 5. Проход 1: чтение, маппинг, валидация, вставка

### 5.1 Потоковое чтение

`XSSFReader` + `SharedStringsTable` (в режиме `ReadOnlySharedStringsTable`) +
`SAXParser`. Обрабатывается один лист, выбранный `SheetSelector`.

Особенности, которые обязаны обрабатываться корректно:

- **Пропущенные ячейки.** SAX отдаёт только непустые ячейки; отсутствующие в XML
  колонки восстанавливаются по атрибуту `r` (`"C7"`) и добиваются `null`.
- **Пропущенные строки.** Аналогично — по атрибуту `r` строки; разрывы нумерации
  не сдвигают `rowNum`.
- **Полностью пустые строки** пропускаются и не попадают в `totalRows`
  (настраивается: `skipBlankRows`, по умолчанию `true`).
- **Даты** отличаются от чисел по `numFmtId`/`styleIndex` из `styles.xml`
  (`DateUtil.isADateFormat`). Значение — serial date, конвертируется с учётом
  флага `date1904` книги.
- **Объединённые ячейки.** В стриминге значение хранится только в верхней левой
  ячейке диапазона. Диапазоны `mergeCells` вычитываются до основного прохода и
  значение размножается на весь диапазон (`expandMergedCells`, по умолчанию `true`).
- **Формулы.** Читается элемент `<v>` (кэшированный результат). Если кэша нет,
  поведение по `formulaPolicy`: `AS_NULL` (по умолчанию), `AS_ERROR`, `AS_FORMULA_TEXT`.
- **Inline strings** (`t="inlineStr"`) и **булевы** (`t="b"`) поддерживаются наравне
  с shared strings.
- **Ячейки с ошибкой** (`t="e"`, например `#N/A`) дают `CONVERSION`-ошибку с исходным
  текстом ошибки в `rawValue`.

### 5.2 Разбор заголовка

Строка `headerRow` читается первой. Для каждого `@ExcelColumn(header = ...)` ищется
совпадение. Правила сопоставления: `trim`, схлопывание повторяющихся пробелов,
удаление неразрывных пробелов, регистронезависимое сравнение (всё настраивается
через `HeaderMatchingPolicy`).

- Не найдена колонка с `required = true` → импорт останавливается со `status = FAILED`
  и `RowError(kind = STRUCTURE)`. Ни одной строки не вставляется, отчёт не генерируется.
- Дублирующиеся заголовки → `STRUCTURE`-ошибка (неоднозначность).
- Лишние колонки в файле игнорируются.

### 5.3 Маппинг и конвертация

`RowMapper` строится один раз на класс: список `(columnIndex, Field, CellConverter)`,
доступ к полям — через `MethodHandle`s, не через `Field.setAccessible` в горячем цикле.

Встроенные конвертеры: `String`, `Integer`/`Long`/`Short`/`Byte`, `BigDecimal`,
`Double`/`Float`, `Boolean` (`да/нет`, `true/false`, `1/0`, `y/n` — набор настраивается),
`LocalDate`, `LocalDateTime`, `LocalTime`, `OffsetDateTime`, `UUID`, `enum` (по `name()`
и по `toString()`), `String[]` (разделитель настраивается).

Ошибка конвертации не прерывает разбор строки: собираются все ошибки по всем колонкам,
строка целиком помечается отклонённой.

Пользовательский конвертер:

```java
public class InnConverter implements CellConverter<String> {
    @Override
    public String convert(CellValue value, ConversionContext ctx) {
        String raw = value.asString();
        if (raw == null) return null;
        String digits = raw.replaceAll("\\D", "");
        if (digits.length() != 10 && digits.length() != 12) {
            throw new ConversionException("INN_LENGTH", "ИНН должен содержать 10 или 12 цифр");
        }
        return digits;
    }
}
```

### 5.4 Валидация уровня строки

Jakarta Bean Validation (Hibernate Validator) применяется к смапленному объекту.
Используются стандартные аннотации; нестандартная логика уровня строки оформляется
собственной constraint-аннотацией — отдельного императивного SPI на этом уровне нет
намеренно, чтобы был один способ делать одно и то же.

```java
@Target(TYPE) @Retention(RUNTIME)
@Constraint(validatedBy = HireDateBeforeFireDateValidator.class)
public @interface HireDateBeforeFireDate {
    String message() default "Дата увольнения раньше даты приёма";
    Class<?>[] groups() default {};
    Class<? extends Payload>[] payload() default {};
}
```

Сообщения нарушений берутся из `ValidationMessages.properties`; `Locale` задаётся
в `ImportConfig`.

### 5.5 Пользовательский `BatchValidator`

Единственный императивный хук пользователя. Вызывается на собранном батче до вставки,
внутри той же транзакции — значит может делать запросы к БД и видеть данные,
вставленные предыдущими батчами этого же прогона.

```java
public interface BatchValidator<T> {
    List<RowError> validate(List<RowRef<T>> batch, Connection connection) throws SQLException;
}

public record RowRef<T>(int rowNum, T value) {}
```

Типичные применения: поиск дубликатов внутри батча и по уже существующим записям
одним `SELECT ... WHERE key IN (...)`, проверка ссылочной целостности по справочникам,
агрегатные ограничения.

```java
public class NoDuplicatePersonnelNoValidator implements BatchValidator<EmployeeRow> {

    @Override
    public List<RowError> validate(List<RowRef<EmployeeRow>> batch, Connection conn)
            throws SQLException {
        List<RowError> errors = new ArrayList<>();
        Map<Long, Integer> seen = new HashMap<>();

        for (RowRef<EmployeeRow> ref : batch) {
            Long no = ref.value().getPersonnelNo();
            Integer prev = seen.putIfAbsent(no, ref.rowNum());
            if (prev != null) {
                errors.add(new RowError(ref.rowNum(), "Табельный номер", String.valueOf(no),
                        ErrorKind.BATCH, "DUPLICATE_IN_FILE",
                        "Дубликат строки " + prev + " в этом же файле"));
            }
        }
        // ... плюс проверка по БД одним запросом
        return errors;
    }
}
```

Контракт: валидатор не должен коммитить, откатывать или закрывать соединение.
Нарушение — `IllegalStateException` (соединение оборачивается прокси, который
блокирует эти методы). Строки, на которые валидатор вернул ошибки, исключаются из
батча; остальные вставляются.

Несколько валидаторов выполняются в порядке регистрации, ошибки объединяются.

### 5.6 Вставка

Один батч — один `PreparedStatement` с multi-row `VALUES`:

```sql
INSERT INTO hr.employee (personnel_no, full_name, hired_at, salary, department)
VALUES (?,?,?,?,?), (?,?,?,?,?), ... (?,?,?,?,?)
ON CONFLICT (personnel_no) DO NOTHING
```

- `ConflictStrategy`: `none()` (по умолчанию), `doNothing(conflictColumns...)`,
  `doUpdate(conflictColumns, updateColumns...)` (генерирует
  `DO UPDATE SET col = EXCLUDED.col`).
- **Ограничение параметров.** У PostgreSQL лимит 65535 bind-параметров на запрос.
  Эффективный размер чанка — `min(batchSize, 65535 / columnCount)`. Если
  `batchSize` больше, он молча дробится на подзапросы внутри одной транзакции, а в
  лог пишется предупреждение (один раз за прогон).
- SQL-шаблон кэшируется по размеру чанка; на неполный хвостовой батч генерируется
  отдельный шаблон.
- `autoCommit = false`, `commit()` после успешного батча. Соединение берётся из
  `DataSource` на батч и возвращается в пул после коммита.
- `reWriteBatchedInserts=true` в URL драйвера не требуется (multi-row VALUES уже
  делает то же самое), но не мешает.

### 5.7 Сбой батча: рекурсивное деление

При `SQLException`/`BatchUpdateException` транзакция батча откатывается целиком,
после чего запускается бисекция:

```
insert(batch):
    try: выполнить и закоммитить batch → все строки OK
    catch SQLException e:
        rollback
        if isFatal(e):            → прервать импорт, status = FAILED
        if batch.size() == 1:     → строка помечается DATABASE-ошибкой (e.getSQLState(),
                                     имя constraint, e.getMessage())
        if depth >= maxSplitDepth → все строки батча помечаются общей DATABASE-ошибкой
        else:
            insert(левая половина, depth + 1)
            insert(правая половина, depth + 1)
```

Каждая половина — отдельная транзакция. Худший случай — `2n - 1` запросов при
`n` одиночных сбоях, но на практике плохих строк единицы, и деление быстро сходится.
`maxSplitDepth` по умолчанию `16` (достаточно для батча до 65536 строк).

**Фатальные ошибки** (импорт прерывается, а не делится): потеря соединения
(`SQLState` класса `08`), несуществующая таблица или колонка (`42P01`, `42703`),
отказ в правах (`42501`), исчерпание ресурсов (`53*`). Классификация вынесена в
`SqlErrorClassifier`, потребитель может её заменить.

**Нефатальные** (ожидаемые, ведут к делению): нарушение ограничений (`23*`),
ошибки типов данных (`22*`).

Сообщение об ошибке БД, попадающее в отчёт, извлекается из `PSQLException`
(`ServerErrorMessage`): `constraint`, `detail`, `column`. Опция
`includeDatabaseDetailInReport` (по умолчанию `true`) позволяет выключить это, если
в `detail` могут попасть чувствительные значения.

### 5.8 Политика ошибок и лимиты

- `maxErrors` — предел отклонённых строк, после которого импорт прерывается со
  `status = FAILED`. По умолчанию `Integer.MAX_VALUE` (без предела). Уже
  закоммиченные батчи не откатываются; отчёт при этом всё равно генерируется и
  включает пометку, что импорт остановлен на строке N.
- `maxErrorsInMemory` — сколько `RowError` попадёт в `ImportReport.errors`
  (по умолчанию 1000). Полный список всегда есть в Excel-отчёте.
- `dryRun` — прогон без вставки: чтение, валидация, отчёт. Транзакций к БД нет,
  `BatchValidator` вызывается (ему нужен `Connection` — выдаётся read-only соединение).

### 5.9 `RowOutcomeStore`

Между проходами нужно помнить исход каждой строки. Наивная `HashMap<Integer, String>`
на файле в миллион строк с длинными сообщениями съест сотни мегабайт.

Реализация по умолчанию — `SpillableRowOutcomeStore`:

- Статусы хранятся в растущем `byte[]`, индексированном по `rowNum`
  (`0 = не обработана`, `1 = вставлена`, `2 = отклонена`, `3 = пропущена`).
  Миллион строк — 1 МБ.
- Сообщения об ошибках хранятся в `Map<Integer, String>` до порога
  `maxOutcomeMessagesInMemory` (по умолчанию 50 000), после чего вытесняются в
  временный файл (последовательная запись `rowNum \t message`), а на втором проходе
  читаются потоково — второй проход идёт по строкам по возрастанию, как и файл
  вытеснения, поэтому достаточно merge-join без индекса.
- Временный файл создаётся в `java.io.tmpdir` (настраивается) и удаляется
  в `finally`.

Интерфейс публичный — потребитель может подменить хранилище (например, на Redis для
распределённого сценария).

## 6. Проход 2: Excel-отчёт

Исходный файл читается вторым потоковым проходом; параллельно пишется новая книга
через `SXSSFWorkbook` (окно 100 строк, остальное на диск).

Что делает `ReportWriter`:

1. Копирует строку заголовка и добавляет две колонки справа: **«Статус импорта»**
   и **«Причина»**.
2. Копирует значения ячеек данных как есть (типы сохраняются: числа числами, даты
   датами с исходным форматом).
3. Заливает всю строку данных: зелёный `#C6EFCE` для вставленных, красный `#FFC7CE`
   для отклонённых, серый `#F2F2F2` для пропущенных (не дошли до обработки из-за
   остановки импорта). Цвета настраиваются через `ReportStyle`.
4. Пишет в «Статус импорта» текст (`Загружено` / `Ошибка` / `Не обработано`), в
   «Причина» — все ошибки строки, объединённые через `; `.
5. Закрепляет строку заголовка (freeze pane) и включает автофильтр.
6. Добавляет лист **«Сводка»**: имя исходного файла, `runId`, время старта и
   длительность, всего строк, вставлено, отклонено, размер батча, топ-10 кодов ошибок
   с количествами.

Ограничения и решения:

- Стили не переносятся из исходника (в стриминге они дороги): в отчёте применяется
  собственный минимальный стиль плюс формат даты для дат. Это осознанный размен —
  отчёт функциональный, а не пиксель-в-пиксель копия.
- Число уникальных `CellStyle` в книге ограничено (64 000), поэтому стили создаются
  ровно один раз на комбинацию (заливка × формат) и переиспользуются.
- Кэш-файлы `SXSSF` удаляются через `dispose()` в `finally`.
- Если строк данных больше лимита листа (1 048 576), отчёт разбивается на листы
  `Отчёт 1`, `Отчёт 2`, …
- Отчёт пишется во временный файл рядом с целевым и переименовывается атомарно
  (`ATOMIC_MOVE`), чтобы потребитель не увидел недописанный файл.
- Генерацию можно выключить: `reportPath(null)`.
- Ошибка генерации отчёта не отменяет уже выполненный импорт: она логируется,
  попадает в `ImportReport.errors` и переводит статус в `PARTIAL`.

### 6.1 Настройка отчёта: `ReportStyle` и `ReportRowCustomizer`

Оформление задаётся `ReportStyle` в POI-терминах:

```java
ReportStyle style = ReportStyle.builder()
        .insertedFill(new XSSFColor(new byte[] {(byte) 0xC6, (byte) 0xEF, (byte) 0xCE}))
        .rejectedFill(IndexedColors.ROSE)
        .skippedFill(IndexedColors.GREY_25_PERCENT)
        .fillPattern(FillPatternType.SOLID_FOREGROUND)
        .statusColumnHeader("Статус импорта")
        .reasonColumnHeader("Причина")
        .reasonAlignment(HorizontalAlignment.LEFT)
        .dateFormat("dd.MM.yyyy")
        .build();
```

Для нестандартных требований — хук, получающий настоящие POI-объекты уже
записываемой книги:

```java
public interface ReportRowCustomizer {

    /** Вызывается после записи строки заголовка. */
    default void customizeHeader(SXSSFRow header, ReportContext ctx) {}

    /**
     * Вызывается после того, как ReportWriter записал значения и применил заливку,
     * но до перехода к следующей строке. Строка ещё во flush-окне SXSSF и доступна
     * для правки.
     */
    void customizeRow(SXSSFRow row, RowOutcome outcome, ReportContext ctx);

    /** Вызывается перед записью книги на диск — можно дописать свои листы. */
    default void finish(SXSSFWorkbook workbook, ImportReport report) {}
}
```

`ReportContext` даёт доступ к `SXSSFWorkbook`, `DataFormat`, индексам служебных
колонок и кэшу стилей (`CellStyle styleFor(RowStatus status, String dataFormat)`),
чтобы кастомизация не плодила стили и не упёрлась в лимит 64 000 уникальных
`CellStyle` на книгу.

Контракт хука: нельзя обращаться к строкам, уже вышедшим из flush-окна SXSSF
(попытка даёт `IllegalStateException` от POI) и нельзя вызывать `workbook.write()`
или `dispose()` — этим управляет `ReportWriter`. Исключение из хука превращается в
`ReportGenerationException`; импорт при этом уже выполнен, статус — `PARTIAL`.

## 7. Конфигурация

`ImportConfig` иммутабельна, собирается билдером, все значения имеют разумные
умолчания.

| Параметр | По умолчанию | Смысл |
|---|---|---|
| `batchSize` | 1000 | Строк в одном INSERT и в одной транзакции |
| `sheet` | первый лист | Имя или индекс листа |
| `headerRow` | 0 | 0-based строка заголовка |
| `firstDataRow` | `headerRow + 1` | Откуда начинаются данные |
| `skipBlankRows` | `true` | Пустые строки не считаются и не отчитываются |
| `expandMergedCells` | `true` | Размножать значение объединённой ячейки |
| `formulaPolicy` | `AS_NULL` | Что делать с формулой без кэша |
| `headerMatching` | trim + collapse + ignore case | Правила сопоставления заголовков |
| `namingStrategy` | `SNAKE_CASE` | Поле → колонка БД без `@Column` |
| `targetTable` | из `@TargetTable` | Переопределение схемы/таблицы |
| `conflictStrategy` | `none()` | `ON CONFLICT` |
| `maxErrors` | без предела | Порог остановки импорта |
| `maxErrorsInMemory` | 1000 | Размер `ImportReport.errors` |
| `maxSplitDepth` | 16 | Глубина бисекции сбойного батча |
| `maxOutcomeMessagesInMemory` | 50 000 | Порог выгрузки сообщений на диск |
| `reportPath` | `null` | Путь к отчёту; `null` — не генерировать |
| `reportStyle` | зелёный/красный/серый | Цвета и заголовки колонок отчёта |
| `includeDatabaseDetailInReport` | `true` | Класть ли `detail` из PG в отчёт |
| `dryRun` | `false` | Прогон без вставки |
| `locale` | `Locale.getDefault()` | Локаль сообщений валидации |
| `tempDir` | `java.io.tmpdir` | Каталог временных файлов |
| `queryTimeoutSeconds` | 0 (без лимита) | `Statement.setQueryTimeout` |

**Валидация конфигурации при сборке:** `batchSize >= 1`; `headerRow >= 0`;
`firstDataRow > headerRow`; `maxSplitDepth >= 0`; задан ровно один из `sheet.name`/
`sheet.index`; `conflictStrategy.doUpdate` требует непустой список обновляемых колонок.
Нарушение — `IllegalArgumentException` в момент `build()`, не в момент импорта.

### 7.1 Spring Boot starter

```yaml
excel-import:
  batch-size: 5000
  max-errors: 1000
  report:
    enabled: true
    directory: /var/reports
  conflict:
    strategy: do-nothing
    columns: [personnel_no]
  locale: ru-RU
```

Starter поставляет `ExcelImportProperties` → `ImportConfig`, автоконфигурацию
`ExcelImportAutoConfiguration` (`@ConditionalOnClass(DataSource.class)`,
`@ConditionalOnMissingBean`), подхватывает все бины `BatchValidator<?>` и
`CellConverter<?>` из контекста, регистрирует `ExcelImporterFactory` для создания
типизированных импортёров. Метрики Micrometer (`excel.import.rows`,
`excel.import.batches`, `excel.import.duration`) — при наличии Micrometer на classpath.

## 8. Наблюдаемость

```java
public interface ImportListener {
    default void onImportStarted(ImportRunInfo info) {}
    default void onBatchCommitted(int batchIndex, int rowCount, long totalInserted) {}
    default void onBatchSplit(int batchIndex, int depth, int rowCount) {}
    default void onRowRejected(RowError error) {}
    default void onImportFinished(ImportReport report) {}
}
```

Слушатель позволяет вести прогресс-бар, писать чекпоинты в свою таблицу, слать
метрики. Исключение из слушателя логируется и не влияет на импорт.

Логирование через SLF4J: `INFO` на старт/финиш/коммит батча, `WARN` на деление
батча и отклонения, `DEBUG` на SQL-шаблон и тайминги, ни одного значения ячейки на
уровне выше `TRACE` (данные могут быть персональными).

## 9. Модель ошибок

| Исключение | Когда |
|---|---|
| `ExcelImportException` | Базовое (unchecked) |
| `MappingConfigurationException` | Некорректные аннотации: нет `@ExcelSheet`, дубли `@Column`, отсутствующий конвертер. Бросается при сборке `ExcelImporter`, не при импорте |
| `FileStructureException` | Нет листа, нет обязательной колонки, дубли заголовков, битый zip |
| `ConversionException` | Из `CellConverter`; перехватывается и превращается в `RowError` |
| `ImportAbortedException` | Превышен `maxErrors` или фатальная ошибка БД; несёт частичный `ImportReport` |
| `ReportGenerationException` | Не удалось записать отчёт; импорт при этом уже выполнен |

Ошибка на уровне отдельной строки никогда не выбрасывается наружу — только
попадает в `RowError`.

## 10. Тестирование

**Unit:**
- Разбор аннотаций: корректные, конфликтующие, отсутствующие.
- Сопоставление заголовков: пробелы, регистр, неразрывные пробелы, дубли, отсутствие.
- Каждый встроенный конвертер: валидные, невалидные, граничные, `null`, локали.
- Алгоритм бисекции — на моке `BatchWriter`: одна плохая строка в начале/середине/
  конце, несколько плохих, все плохие, превышение `maxSplitDepth`.
- Классификатор SQL-ошибок: фатальные vs. нефатальные `SQLState`.
- `SpillableRowOutcomeStore`: до порога, после порога, merge-join на втором проходе.
- Расчёт размера чанка под лимит 65535 параметров.

**Интеграционные (Testcontainers, PostgreSQL 16):**
- Happy path: 10 000 строк, `batchSize = 500` → 20 коммитов, все строки в таблице.
- `ON CONFLICT DO NOTHING` и `DO UPDATE` на пересекающихся данных.
- Уникальный индекс ломает одну строку в середине батча → бисекция, остальные
  вставлены, отчёт корректен.
- Ошибка `NOT NULL` на уровне БД (аннотация не покрыла случай).
- `BatchValidator`, ходящий в БД: дубликат относительно ранее вставленного батча.
- Обрыв соединения посреди импорта → `status = FAILED`, закоммиченные батчи на месте.
- `dryRun` не оставляет данных.

**Ресурсные фикстуры `.xlsx`:**
- пустые ячейки и пропущенные строки;
- объединённые ячейки;
- формулы с кэшем и без;
- даты в разных форматах, включая `date1904`;
- inline strings;
- ячейки с `#N/A`;
- 100 000 строк — тест на потолок памяти (запуск с `-Xmx256m`, проверка, что импорт
  проходит);
- файл, не являющийся zip; zip, не являющийся xlsx.

**Тесты отчёта:** открыть сгенерированный файл через POI и проверить заливку,
текст причины, число строк, лист «Сводка», разбиение при переполнении листа.
Отдельно: `ReportStyle` с кастомными цветами применяется; `ReportRowCustomizer`
получает строку и может её изменить; исключение из хука даёт
`ReportGenerationException` и `status = PARTIAL`, а не теряет данные; кэш стилей
не плодит `CellStyle` (счётчик `workbook.getNumCellStyles()` ограничен).

**Производительность** (source set `performanceTest`, вне `check` и вне CI): ориентир — 100 000 строк
× 10 колонок за ≤ 30 с на локальном PostgreSQL при `batchSize = 1000`, потребление
heap ≤ 256 МБ.

## 11. Порядок реализации

1. Каркас Gradle: `settings.gradle.kts`, version catalog, конвенции в корневом
   `build.gradle.kts`, wrapper, два модуля, source sets `test`/`integrationTest`.
2. `StreamingSheetReader` + фикстуры (самая рискованная часть — делать первой).
3. Аннотации, `RowMapper`, встроенные конвертеры.
4. Интеграция Jakarta Bean Validation.
5. `BatchWriter` с multi-row INSERT и `ConflictStrategy`.
6. Бисекция сбойного батча + `SqlErrorClassifier`.
7. `BatchValidator` SPI и прокси на `Connection`.
8. `RowOutcomeStore` с выгрузкой на диск.
9. `ReportWriter` (второй проход), затем `ReportStyle` и хук `ReportRowCustomizer`.
10. `ExcelImporter`-фасад, `ImportConfig`, `ImportListener`.
11. Spring Boot starter.
12. README с примерами и таблицей конфигурации.

## 12. Открытые вопросы на будущее (вне текущего объёма)

- Поддержка `.xls` через отдельный `HssfSheetReader` за тем же интерфейсом.
- Режим `COPY` (`CopyManager`) для чистых больших загрузок без `ON CONFLICT`.
- Возобновление прерванного импорта по чекпоинту в служебной таблице.
- Параллельная обработка батчей несколькими соединениями.
- CSV как второй источник за тем же `RowMapper`.
