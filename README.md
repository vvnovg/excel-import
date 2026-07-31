# excel-import

Потоковый импорт `.xlsx`-файлов в PostgreSQL. Библиотека читает файл SAX-стримингом
(потребление памяти не зависит от числа строк), отображает строки на пользовательские
POJO по аннотациям, валидирует их (Jakarta Bean Validation), вставляет батчами
настраиваемого размера одним multi-row `INSERT` на батч и по итогам формирует
Excel-отчёт: копию исходного файла, где вставленные строки залиты зелёным, отклонённые
— красным, а в добавленной колонке указана причина отклонения каждой строки.

Файл читается дважды. Первый проход — данные (чтение, маппинг, валидация, вставка);
между проходами в памяти живёт только компактная карта исходов строк (статусы — в
`byte[]`, ~1 МБ на миллион строк; сообщения сверх лимита вытесняются во временный файл).
Второй проход — генерация отчёта через `SXSSFWorkbook`. Два прохода вынуждены:
SAX-стриминг не позволяет править исходный файл на месте, а загрузка книги в
`XSSFWorkbook` ради раскраски убила бы выигрыш от стриминга.

## Установка

Gradle (Kotlin DSL):

```kotlin
implementation("org.novgorodtsev.excelimport:excel-import-core:0.1.0-SNAPSHOT")
```

Для Spring Boot — одна зависимость на стартер, ядро подтянется транзитивно:

```kotlin
implementation("org.novgorodtsev.excelimport:excel-import-spring-boot-starter:0.1.0-SNAPSHOT")
```

Maven:

```xml
<dependency>
    <groupId>org.novgorodtsev.excelimport</groupId>
    <artifactId>excel-import-core</artifactId>
    <version>0.1.0-SNAPSHOT</version>
</dependency>
```

```xml
<dependency>
    <groupId>org.novgorodtsev.excelimport</groupId>
    <artifactId>excel-import-spring-boot-starter</artifactId>
    <version>0.1.0-SNAPSHOT</version>
</dependency>
```

Драйвер PostgreSQL в рантайм не тащится: он `compileOnly` в ядре и приходит через
`DataSource` потребителя. POI (`poi`, `poi-ooxml`) экспортирован как `api`
сознательно — потребитель работает с привычными POI-типами (`SXSSFRow`, `XSSFColor`
и т.д.) в хуках, а не с обёртками.

| Компонент | Требование |
|---|---|
| Java | 17+ |
| Apache POI | 5.4.x (подъём мажорной версии POI — breaking change) |
| PostgreSQL | 12+ |
| Spring Boot | 3.x (только для стартера; ядро от Spring не зависит) |

## Быстрый старт

Описываете модель строки аннотациями:

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

и запускаете импорт:

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
        .reportRowCustomizer(new HrReportCustomizer())   // опционально
        .listener(new LoggingImportListener())
        .build();

ImportReport report = importer.importFile(Path.of("employees.xlsx"));

System.out.printf("Всего %d, вставлено %d, отклонено %d, отчёт: %s%n",
        report.totalRows(), report.insertedRows(), report.rejectedRows(),
        report.reportPath());
```

`ExcelImporter` потокобезопасен и переиспользуем: разбор аннотаций, шаблон SQL и
`ValidatorFactory` считаются один раз при сборке. Один вызов `importFile` — один
изолированный прогон. Перегрузки: `importFile(Path)` и
`importFile(InputStream, String sourceName)` (поток копируется во временный файл —
нужны два прохода; временный файл удаляется по завершении). `ExcelImporter`
реализует `AutoCloseable` (закрывает `ValidatorFactory`), поэтому удобен с
try-with-resources.

Результат — `ImportReport`:

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
        ImportStatus status)     // SUCCESS | PARTIAL | FAILED
```

Каждый `RowError` содержит `rowNum` (1-based, как в Excel), `columnHeader`,
`rawValue`, `kind` (`STRUCTURE | CONVERSION | CONSTRAINT | BATCH | DATABASE`),
`code` (`"NotNull"`, `"23505"`, `"DUPLICATE_KEY"`, …) и человекочитаемое,
локализуемое `message`. Ошибки уровня строки никогда не выбрасываются наружу как
исключения — только попадают в `RowError`. Наружу идут только фатальные:
`FileStructureException`, `ImportAbortedException`, `ReportGenerationException`.

## Аннотации

| Аннотация | Атрибут | По умолчанию | Смысл |
|---|---|---|---|
| `@ExcelSheet` (на классе) | `name` | `""` | Имя листа. Задаётся ровно одно из `name`/`index` |
| | `index` | `-1` | 0-based индекс листа |
| | `headerRow` | `0` | 0-based индекс строки заголовка |
| | `firstDataRow` | `headerRow + 1` | 0-based индекс первой строки данных |
| `@TargetTable` (на классе) | `schema` | `""` (search_path) | Схема таблицы-приёмника |
| | `name` | — | Имя таблицы (может быть переопределено в `ImportConfig.targetTable`) |
| `@ExcelColumn` (на поле) | `header` | `""` | Текст в строке заголовка (по умолчанию trim + схлопывание пробелов + без учёта регистра) |
| | `index` | `-1` | 0-based индекс колонки |
| | `letter` | `""` | Буква колонки (`"A"`, `"AB"`). Колонка задаётся ровно одним из `header`/`index`/`letter` |
| | `required` | `true` | Отсутствие колонки в файле — фатальная ошибка структуры |
| | `formats` | `{}` | Форматы разбора дат/чисел, пробуются по порядку |
| | `trim` | `true` | Обрезать пробелы по краям |
| | `emptyAsNull` | `true` | Пустую строку считать `null` |
| | `converter` | по типу поля | Класс своего `CellConverter` |
| `@Column` (на поле) | `value` | — | Имя колонки в БД; без аннотации выводится через `NamingStrategy` (по умолчанию `snake_case`) |

Поля без `@ExcelColumn` игнорируются при чтении. Поле с `@Column`, но без
`@ExcelColumn`, участвует во вставке — значение туда может положить `BatchValidator`.

Все индексы в аннотациях и конфигурации — 0-based (как в POI). Всё, что видит
пользователь (`RowError.rowNum`, сообщения, Excel-отчёт), — 1-based, как в Excel.

## Конфигурация

`ImportConfig` иммутабельна, собирается билдером, проверки выполняются в `build()`
(`batchSize >= 1`, `firstDataRow > headerRow` и т.д.) — нарушение даёт
`IllegalArgumentException` в момент сборки, а не в середине импорта.

| Параметр | По умолчанию | Смысл |
|---|---|---|
| `batchSize` | 1000 | Строк в одном INSERT и в одной транзакции |
| `sheet` | из `@ExcelSheet` | Имя или индекс листа |
| `headerRow` | 0 | 0-based строка заголовка |
| `firstDataRow` | `headerRow + 1` | Откуда начинаются данные |
| `skipBlankRows` | `true` | Пустые строки не считаются и не отчитываются |
| `expandMergedCells` | `true` | Размножать значение объединённой ячейки |
| `formulaPolicy` | `AS_NULL` | Что делать с формулой без кэшированного значения |
| `headerMatching` | trim + collapse + ignore case | Правила сопоставления заголовков |
| `namingStrategy` | `SNAKE_CASE` | Поле → колонка БД без `@Column` |
| `targetTable` | из `@TargetTable` | Переопределение схемы/таблицы |
| `conflictStrategy` | `none()` | `ON CONFLICT`: `none()`, `doNothing(cols...)`, `doUpdate(cols, updateCols)` |
| `maxErrors` | без предела | Порог остановки импорта |
| `maxErrorsInMemory` | 1000 | Размер `ImportReport.errors` |
| `maxSplitDepth` | 16 | Глубина бисекции сбойного батча |
| `maxOutcomeMessagesInMemory` | 50 000 | Порог выгрузки сообщений об исходах строк на диск |
| `reportPath` | `null` | Путь к отчёту; `null` — не генерировать |
| `reportStyle` | зелёный/красный/серый | Цвета и заголовки колонок отчёта |
| `includeDatabaseDetailInReport` | `true` | Класть ли `detail` из PG-ошибок в отчёт |
| `dryRun` | `false` | Прогон без вставки |
| `locale` | `Locale.getDefault()` | Локаль сообщений валидации |
| `tempDir` | `java.io.tmpdir` | Каталог временных файлов |
| `queryTimeoutSeconds` | 0 (без лимита) | `Statement.setQueryTimeout` |

### Spring Boot starter

Стартер регистрирует `ExcelImporterFactory`, если в контексте есть `DataSource`, и
связывает свойства под префиксом `excel-import` (`@ConfigurationProperties`):

```yaml
excel-import:
  batch-size: 5000
  max-errors: 1000
  locale: ru-RU
  report:
    enabled: true
    directory: /var/reports
    file-name-pattern: "{name}-report.xlsx"
  conflict:
    strategy: do-nothing        # none | do-nothing | do-update
    columns: [personnel_no]     # целевые колонки ON CONFLICT
    # update-columns: [full_name, salary]   # только для do-update
```

Использование:

```java
try (ExcelImporter<EmployeeRow> importer = importerFactory.create(EmployeeRow.class,
        properties.toImportConfig(properties.reportPathFor("employees.xlsx")))) {
    ImportReport report = importer.importFile(Path.of("employees.xlsx"));
}
```

- `importerFactory.create(EmployeeRow.class)` использует конфигурацию из свойств
  (`create(type, config)` позволяет переопределить её для конкретного импорта).
- Свойства подхватывают тот же набор параметров, что и `ImportConfig` (см. таблицу
  выше); значения по умолчанию совпадают с ядром. `properties.toImportConfig(reportPath)`
  собирает `ImportConfig` из всех настроенных свойств и подставляет путь отчёта одним
  вызовом — в отличие от повторного вызова `ImportConfig.builder()` вручную, который
  сбросил бы `batchSize`, `conflictStrategy`, `maxErrors` и остальное обратно к
  дефолтам ядра. Есть и `toImportConfig()` без аргумента — для случаев без отчёта или
  когда путь ещё не известен на момент сборки.
- `reportPathFor(fileName)` строит путь отчёта из `report.directory` и
  `report.file-name-pattern` (плейсхолдер `{name}` — имя исходного файла без
  расширения); возвращает `null`, если отчёты выключены.
- Фабрика автоматически подставляет в импортёр все бины `BatchValidator<?>`,
  а также (по одному, если объявлены) `ReportRowCustomizer`, `SqlErrorClassifier`,
  `ImportListener`. Валидаторы отбираются по параметру типа: `BatchValidator<EmployeeRow>`
  применится к `EmployeeRow`. Параметр типа определяется рефлексией и у некоторых форм
  валидатора стирается во время выполнения — лямбда (`BatchValidator<Row> v = (batch,
  conn) -> ...`) и переиспользуемый generic-класс (`class GenericValidator<T> implements
  BatchValidator<T>`) дают нерезолвируемый параметр. Такой валидатор **не** подключается
  ни к одному импортёру (иначе он молча цеплялся бы к чужим строкам и падал
  `ClassCastException` посреди импорта) — вместо этого при старте контекста пишется один
  `WARN` с именем класса бина. Если нужен именно generic-валидатор, зарегистрируйте его
  явно через `ExcelImporter.builder(...).batchValidator(...)`, а не как бин. Анонимный
  класс без явного generic-аргумента (`new BatchValidator<>() {...}`) в эту категорию
  **не** попадает — он сохраняет параметр типа и подключается как обычно.
- `CellConverter`-бины автоматически не подхватываются: целевой тип поля из бина не
  выводится, а явный случай уже покрыт `@ExcelColumn(converter = ...)`. Регистрация
  по типу — вручную через `ExcelImporter.builder(...).converter(TargetType.class, conv)`
  / `create(type, config)` с последующей настройкой — сознательное ограничение стартера.
- Свой бин `ExcelImporterFactory` отключает автоконфигурацию (`@ConditionalOnMissingBean`).

## Валидация

Три последовательных слоя; ошибка любого слоя помечает строку отклонённой (красной
в отчёте), остальные строки обрабатываются дальше:

1. **Конвертеры.** `CellConverter` превращает `CellValue` в целевой тип поля. Есть
   встроенные для примитивов, строк, дат/времени, `BigDecimal`, boolean (со словарём
   `booleanWords`), enum. Свой конвертер — через `@ExcelColumn(converter = ...)`
   или `ExcelImporter.builder(...).converter(TargetType.class, myConverter)`.
   Ошибка конвертации — `ErrorKind.CONVERSION`.
2. **Jakarta Bean Validation** (Hibernate Validator) на смапленном объекте —
   стандартные аннотации `@NotNull`, `@Size`, `@PastOrPresent`, …
   Ошибки — `ErrorKind.CONSTRAINT`. Нестандартная логика уровня строки оформляется
   собственной constraint-аннотацией (отдельного императивного SPI на этом уровне
   нет намеренно):

   ```java
   @Target(ElementType.TYPE) @Retention(RetentionPolicy.RUNTIME)
   @Constraint(validatedBy = HireDateBeforeFireDateValidator.class)
   public @interface HireDateBeforeFireDate {
       String message() default "Дата увольнения раньше даты приёма";
       Class<?>[] groups() default {};
       Class<? extends Payload>[] payload() default {};
   }
   ```

   Сообщения берутся из `ValidationMessages.properties`; локаль задаётся
   `ImportConfig.locale` (русские сообщения входят в поставку).

   Бандл библиотеки специально называется `org.novgorodtsev.excelimport.ValidationMessages`, а
   **не** `ValidationMessages` в корне classpath — так он не конфликтует с одноимённым
   бандлом потребителя (`ResourceBundle` резолвит бандл целиком, а не по ключам: два
   бандла с одинаковым именем на classpath — и один молча "проигрывает"). Порядок
   резолва сообщения: сперва корневой `ValidationMessages` потребителя, затем бандл
   библиотеки, и только затем встроенные сообщения Hibernate Validator — то есть любое
   сообщение библиотеки можно переопределить своим файлом. EL-выражения `${...}`
   никогда не вычисляются (в classpath намеренно нет реализации `jakarta.el`) и остаются
   в сообщении как есть; плейсхолдеры `{параметр}` подставляются штатно. Литеральные
   `{`, `}` и `$` в тексте сообщения экранируются обратным слэшем: `\{`, `\}`, `\$`.
3. **`BatchValidator<T>`** — единственный императивный хук пользователя. Вызывается
   на собранном батче до вставки, в той же транзакции — значит видит данные,
   вставленные предыдущими батчами этого прогона, и может всё проверить одним
   запросом к БД:

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
           // ... плюс проверка по уже существующим записям одним SELECT ... WHERE key IN (...)
           return errors;
       }
   }
   ```

   Контракт: валидатор не должен коммитить, откатывать или закрывать соединение
   (прокси блокирует такие вызовы `IllegalStateException`). Строки, на которые
   возвращены ошибки, исключаются из батча; остальные вставляются. Несколько
   валидаторов выполняются в порядке регистрации, ошибки объединяются.

## Отчёт

Если задан `reportPath`, после импорта генерируется `.xlsx`-отчёт (отдельным
потоковым проходом по исходному файлу):

- строка заголовка копируется, справа добавляются колонки **«Статус импорта»**
  и **«Причина»** (в «Причина» — все ошибки строки через `; `);
- значения ячеек копируются как есть (числа числами, даты датами с исходным форматом);
- строки данных залиты: зелёный `#C6EFCE` — вставленные, красный `#FFC7CE` —
  отклонённые, серый `#F2F2F2` — не дошли до обработки (остановка по
  `maxErrors`/фатальной ошибке);
- закреплён заголовок и включён автофильтр; листов больше одного, если строк
  больше лимита листа (1 048 576) — `Отчёт 1`, `Отчёт 2`, …;
- второй лист — **«Сводка»**: файл, `runId`, время старта/длительность, счётчики,
  топ-10 кодов ошибок;
- файл пишется во временный рядом с целевым и переименовывается атомарно
  (`ATOMIC_MOVE`) — потребитель не видит недописанный файл;
- ошибка генерации отчёта не отменяет уже выполненный импорт: статус переводится
  в `PARTIAL`, ошибка попадает в `ImportReport.errors`.

**Оговорка по форматированию.** Стили из исходного файла не переносятся (в
стриминге они дороги): в отчёте применяется собственный минимальный стиль плюс
формат дат. Это осознанный размен — отчёт функциональный, а не пиксель-в-пиксель
копия.

Оформление настраивается `ReportStyle` (в POI-терминах):

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

Для нестандартных требований — хук `ReportRowCustomizer`, получающий настоящие
POI-объекты уже записываемой книги:

```java
public interface ReportRowCustomizer {
    default void customizeHeader(SXSSFRow header, ReportContext ctx) {}
    void customizeRow(SXSSFRow row, RowOutcome outcome, ReportContext ctx);
    default void finish(SXSSFWorkbook workbook, ImportReport report) {}
}
```

Контракт: нельзя обращаться к строкам за пределами flush-окна SXSSF и нельзя
вызывать `workbook.write()`/`dispose()` — этим управляет `ReportWriter`. Стили
берите из `ReportContext.styleFor(status, dataFormat)`, чтобы не упереться в лимит
64 000 уникальных `CellStyle` на книгу.

## Как обрабатываются ошибки БД

Один батч — один multi-row `INSERT ... VALUES (...), (...), ...` и одна транзакция
(`commit` после батча, соединение возвращается в пул). При `SQLException`
транзакция батча откатывается целиком и запускается **рекурсивная бисекция**:
сбойный батч делится пополам, каждая половина вставляется в своей транзакции,
и так до одиночной строки — в итоге «плохая» строка получает свою `DATABASE`-ошибку
(SQLState, имя constraint, сообщение), а все исправные строки батча вставляются.
Глубина деления ограничена `maxSplitDepth` (по умолчанию 16 — хватает на батч до
65 536 строк); при исчерпании глубины все оставшиеся строки части помечаются общей
ошибкой.

Классификация ошибок (что фатально, что восстановимо) вынесена в
`SqlErrorClassifier` и заменяема:

Реализация по умолчанию консервативна: неизвестный SQLState или исключение без
состояния считаются фатальными, чтобы не делить батч вслепую при системной проблеме.

| SQLState | Категория | Поведение |
|---|---|---|
| `08*` (потеря соединения) | фатально | импорт прерывается, `status = FAILED` |
| `53*` (исчерпание ресурсов), `57*`, `58*`, `F0*`, `XX*` | фатально | импорт прерывается |
| `42P01`, `42703`, `42P07` (нет таблицы/колонки, дубликат таблицы) | фатально | импорт прерывается |
| `42501` (нет прав), `42601`, `3D000`, `28P01`, `28000` | фатально | импорт прерывается |
| `23*` (нарушение constraint) | восстановимо | бисекция до виновной строки |
| `22*` (ошибки типа данных) | восстановимо | бисекция до виновной строки |
| всё остальное / не удалось определить | фатально | консервативный дефолт |

Уже закоммиченные батчи фатальной ошибкой не откатываются; отчёт всё равно
генерируется с пометкой об остановке.

Сообщение об ошибке БД, попадающее в отчёт, извлекается из `PSQLException`
(`ServerErrorMessage`: `constraint`, `detail`, `column`). Опция
`includeDatabaseDetailInReport` (по умолчанию `true`) отключает попадание
`detail` в отчёт — включайте осознанно, если в `detail` могут быть чувствительные
значения.

**Лимит 65 535 bind-параметров** PostgreSQL на один запрос учитывается
автоматически: эффективный размер чанка — `min(batchSize, 65535 / columnCount)`,
избыточный батч молча дробится на подзапросы внутри той же транзакции (один раз
за прогон пишется предупреждение в лог). SQL-шаблон кэшируется по размеру чанка.
`reWriteBatchedInserts=true` в URL драйвера не требуется (multi-row VALUES делает
то же самое), но не мешает.

## Ограничения

- Только `.xlsx` (OOXML). Устаревший `.xls` (BIFF/HSSF) не поддерживается.
- Таблица-приёмник должна существовать: библиотека не создаёт и не мигрирует схему.
- Только `INSERT` (опционально с `ON CONFLICT`). `UPDATE`/`DELETE` нет.
- Формулы не вычисляются: читается кэшированное значение; если кэш пуст — поведение
  задаётся `formulaPolicy` (по умолчанию ячейка считается `null`).
- Лимит 65 535 bind-параметров: дробление чанков автоматическое (см. выше),
  вручную учитывать не нужно.
- После выгрузки исходов строк на диск (свыше `maxOutcomeMessagesInMemory`
  сообщений) чтение исходов возможно только по возрастанию `rowNum` — второй проход
  отчёта именно так и идёт.
- Максимум строк в листе Excel — 1 048 576; сверх этого отчёт разбивается на листы.

## Наблюдаемость

Прогресс и события прогона — через `ImportListener` (прогресс-бар, чекпоинты в
своей таблице, метрики):

```java
public interface ImportListener {
    default void onImportStarted(ImportRunInfo info) {}
    default void onBatchCommitted(int batchIndex, int rowCount, long totalInserted) {}
    default void onBatchSplit(int batchIndex, int depth, int rowCount) {}
    default void onRowRejected(RowError error) {}
    default void onImportFinished(ImportReport report) {}
}
```

Исключение из любого метода слушателя логируется и не влияет на импорт.

Логирование через SLF4J: `INFO` на старт/финиш/коммит батча, `WARN` на деление
батча и отклонения, `DEBUG` на SQL-шаблон и тайминги. **Ни одно значение ячейки
не логируется на уровне выше `TRACE`** — данные могут быть персональными.

## Производительность

Чтение потоковое (XSSFReader + SAX), отчёт — через `SXSSFWorkbook` с окном в 100
строк; в памяти между проходами только компактная карта исходов строк. Проверено
перф-тестом: импорт 100 000 строк проходит при куче 256 МБ (`./gradlew performanceTest`,
`maxHeapSize = 256m`).

## Сборка проекта

Gradle 8.12 (wrapper закоммичен), multi-project, Java 17 (toolchain):

```bash
./gradlew build              # компиляция + unit-тесты + javadoc/sources jar
./gradlew test               # только unit-тесты (без Docker)
./gradlew check              # unit + интеграционные (Testcontainers PostgreSQL 16) — нужен Docker
./gradlew integrationTest    # только интеграционные
./gradlew performanceTest    # перф-тесты (256 МБ куча), в check не входит
```

Компиляция с `-Xlint:all -Werror` — предупреждение ломает сборку. Публичный API —
пакет `org.novgorodtsev.excelimport` и подпакеты, **кроме** `org.novgorodtsev.excelimport.internal.*`
(приватный, без гарантий совместимости).
