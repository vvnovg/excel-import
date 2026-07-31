# excel-import

**English** | [Русский](README.ru.md)

Streaming import of `.xlsx` files into PostgreSQL. The library reads the file with
SAX streaming (memory consumption does not depend on the number of rows), maps rows
onto user-defined POJOs by annotations, validates them (Jakarta Bean Validation),
inserts them in configurable-size batches using a single multi-row `INSERT` per
batch, and finally produces an Excel report: a copy of the source file where
inserted rows are shaded green, rejected rows red, and an added column states the
rejection reason for each row.

The file is read twice. The first pass handles data (reading, mapping, validation,
insertion); between passes only a compact row-outcome map lives in memory (statuses
in a `byte[]`, ~1 MB per million rows; messages above the limit are spilled to a
temporary file). The second pass generates the report via `SXSSFWorkbook`. Two passes
are unavoidable: SAX streaming cannot modify the source file in place, and loading
the workbook into an `XSSFWorkbook` just for coloring would negate the streaming
benefit.

## Installation

Gradle (Kotlin DSL):

```kotlin
implementation("org.novgorodtsev.excelimport:excel-import-core:0.1.0-SNAPSHOT")
```

For Spring Boot — a single dependency on the starter, the core is pulled transitively:

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

The PostgreSQL driver is not pulled at runtime: it is `compileOnly` in the core and
arrives via the consumer's `DataSource`. POI (`poi`, `poi-ooxml`) is exported as
`api` deliberately — the consumer works with familiar POI types (`SXSSFRow`,
`XSSFColor`, etc.) in hooks, not with wrappers.

| Component | Requirement |
|---|---|
| Java | 17+ |
| Apache POI | 5.4.x (bumping POI's major version is a breaking change) |
| PostgreSQL | 12+ |
| Spring Boot | 3.x (starter only; the core does not depend on Spring) |

## Quick start

Describe your row model with annotations:

```java
@ExcelSheet(name = "Employees", headerRow = 0)
@TargetTable(schema = "hr", name = "employee")
public class EmployeeRow {

    @ExcelColumn(header = "Personnel No.")
    @Column("personnel_no")
    @NotNull
    private Long personnelNo;

    @ExcelColumn(header = "Full name")
    @Column("full_name")
    @NotBlank
    @Size(max = 200)
    private String fullName;

    @ExcelColumn(header = "Hire date", formats = {"dd.MM.yyyy", "yyyy-MM-dd"})
    @Column("hired_at")
    @PastOrPresent
    private LocalDate hiredAt;

    @ExcelColumn(header = "Salary")
    @Column("salary")
    @DecimalMin("0.00")
    private BigDecimal salary;

    @ExcelColumn(header = "Department", required = false)
    @Column("department")
    private String department;

    // getters/setters
}
```

and run the import:

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
        .reportRowCustomizer(new HrReportCustomizer())   // optional
        .listener(new LoggingImportListener())
        .build();

ImportReport report = importer.importFile(Path.of("employees.xlsx"));

System.out.printf("Total %d, inserted %d, rejected %d, report: %s%n",
        report.totalRows(), report.insertedRows(), report.rejectedRows(),
        report.reportPath());
```

`ExcelImporter` is thread-safe and reusable: annotation parsing, the SQL template,
and the `ValidatorFactory` are computed once at build time. One `importFile` call is
one isolated run. Overloads: `importFile(Path)` and
`importFile(InputStream, String sourceName)` (the stream is copied to a temporary
file — two passes are required; the temp file is deleted on completion).
`ExcelImporter` implements `AutoCloseable` (closes the `ValidatorFactory`), so it
works well with try-with-resources.

The result is an `ImportReport`:

```java
public record ImportReport(
        UUID runId,
        String sourceName,
        long totalRows,          // data rows read
        long insertedRows,
        long rejectedRows,
        long batchesCommitted,
        Duration duration,
        Path reportPath,         // null if the report is disabled
        List<RowError> errors,   // truncated to maxErrorsInMemory
        boolean errorLimitReached,
        ImportStatus status)     // SUCCESS | PARTIAL | FAILED
```

Each `RowError` carries `rowNum` (1-based, as in Excel), `columnHeader`, `rawValue`,
`kind` (`STRUCTURE | CONVERSION | CONSTRAINT | BATCH | DATABASE`), `code`
(`"NotNull"`, `"23505"`, `"DUPLICATE_KEY"`, …), and a human-readable, localizable
`message`. Row-level errors are never thrown as exceptions — they only end up in
`RowError`. Only fatal conditions propagate outward: `FileStructureException`,
`ImportAbortedException`, `ReportGenerationException`.

## Annotations

| Annotation | Attribute | Default | Meaning |
|---|---|---|---|
| `@ExcelSheet` (on class) | `name` | `""` | Sheet name. Exactly one of `name`/`index` is set |
| | `index` | `-1` | 0-based sheet index |
| | `headerRow` | `0` | 0-based header row index |
| | `firstDataRow` | `headerRow + 1` | 0-based first data row index |
| `@TargetTable` (on class) | `schema` | `""` (search_path) | Target table schema |
| | `name` | — | Table name (may be overridden via `ImportConfig.targetTable`) |
| `@ExcelColumn` (on field) | `header` | `""` | Header-row text (by default: trim + collapse whitespace + case-insensitive) |
| | `index` | `-1` | 0-based column index |
| | `letter` | `""` | Column letter (`"A"`, `"AB"`). Exactly one of `header`/`index`/`letter` is set |
| | `required` | `true` | Missing column in the file is a fatal structure error |
| | `formats` | `{}` | Date/number parse formats, tried in order |
| | `trim` | `true` | Trim surrounding whitespace |
| | `emptyAsNull` | `true` | Treat empty string as `null` |
| | `converter` | by field type | A custom `CellConverter` class |
| `@Column` (on field) | `value` | — | DB column name; without the annotation it is derived via `NamingStrategy` (`SNAKE_CASE` by default) |

Fields without `@ExcelColumn` are ignored when reading. A field with `@Column` but
without `@ExcelColumn` participates in the insert — a `BatchValidator` may place a
value into it.

All indices in annotations and configuration are 0-based (as in POI). Everything the
user sees (`RowError.rowNum`, messages, the Excel report) is 1-based, as in Excel.

## Configuration

`ImportConfig` is immutable, built via a builder; validations run in `build()`
(`batchSize >= 1`, `firstDataRow > headerRow`, etc.) — a violation throws
`IllegalArgumentException` at build time, not in the middle of an import.

| Parameter | Default | Meaning |
|---|---|---|
| `batchSize` | 1000 | Rows per INSERT and per transaction |
| `sheet` | from `@ExcelSheet` | Sheet name or index |
| `headerRow` | 0 | 0-based header row |
| `firstDataRow` | `headerRow + 1` | Where data begins |
| `skipBlankRows` | `true` | Blank rows are not counted or reported |
| `expandMergedCells` | `true` | Propagate a merged cell's value |
| `formulaPolicy` | `AS_NULL` | What to do with a formula that has no cached value |
| `headerMatching` | trim + collapse + ignore case | Header matching rules |
| `namingStrategy` | `SNAKE_CASE` | Field → DB column without `@Column` |
| `targetTable` | from `@TargetTable` | Override schema/table |
| `conflictStrategy` | `none()` | `ON CONFLICT`: `none()`, `doNothing(cols...)`, `doUpdate(cols, updateCols)` |
| `maxErrors` | no limit | Import stop threshold |
| `maxErrorsInMemory` | 1000 | Size of `ImportReport.errors` |
| `maxSplitDepth` | 16 | Bisection depth of a failing batch |
| `maxOutcomeMessagesInMemory` | 50 000 | Threshold for spilling row-outcome messages to disk |
| `reportPath` | `null` | Report path; `null` — do not generate |
| `reportStyle` | green/red/grey | Report colors and column headers |
| `includeDatabaseDetailInReport` | `true` | Whether to put PG-error `detail` into the report |
| `dryRun` | `false` | Run without inserting |
| `locale` | `Locale.getDefault()` | Validation message locale |
| `tempDir` | `java.io.tmpdir` | Temp-file directory |
| `queryTimeoutSeconds` | 0 (no limit) | `Statement.setQueryTimeout` |

### Spring Boot starter

The starter registers an `ExcelImporterFactory` if a `DataSource` is present in the
context, and binds properties under the `excel-import` prefix (`@ConfigurationProperties`):

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
    columns: [personnel_no]     # ON CONFLICT target columns
    # update-columns: [full_name, salary]   # do-update only
```

Usage:

```java
try (ExcelImporter<EmployeeRow> importer = importerFactory.create(EmployeeRow.class,
        properties.toImportConfig(properties.reportPathFor("employees.xlsx")))) {
    ImportReport report = importer.importFile(Path.of("employees.xlsx"));
}
```

- `importerFactory.create(EmployeeRow.class)` uses the configuration from properties
  (`create(type, config)` lets you override it for a specific import).
- The properties accept the same parameter set as `ImportConfig` (see the table
  above); defaults match the core. `properties.toImportConfig(reportPath)` assembles
  an `ImportConfig` from all configured properties and sets the report path in one
  call — unlike re-running `ImportConfig.builder()` manually, which would reset
  `batchSize`, `conflictStrategy`, `maxErrors`, and the rest back to the core
  defaults. There is also a no-arg `toImportConfig()` — for cases with no report or
  when the path is not yet known at build time.
- `reportPathFor(fileName)` builds the report path from `report.directory` and
  `report.file-name-pattern` (the `{name}` placeholder is the source file name
  without extension); returns `null` if reports are disabled.
- The factory auto-wires all `BatchValidator<?>` beans into the importer, as well as
  (one each, if declared) `ReportRowCustomizer`, `SqlErrorClassifier`, and
  `ImportListener`. Validators are selected by type parameter:
  `BatchValidator<EmployeeRow>` applies to `EmployeeRow`. The type parameter is
  resolved reflectively and is erased at runtime for some validator shapes — a lambda
  (`BatchValidator<Row> v = (batch, conn) -> ...`) and a reusable generic class
  (`class GenericValidator<T> implements BatchValidator<T>`) yield an unresolvable
  parameter. Such a validator is **not** wired to any importer (otherwise it would
  silently attach to another importer's rows and throw `ClassCastException` mid-import) — instead,
  a single `WARN` with the bean class name is logged at context startup. If you need
  a generic validator, register it explicitly via
  `ExcelImporter.builder(...).batchValidator(...)`, not as a bean. An anonymous class
  without an explicit generic argument (`new BatchValidator<>() {...}`) does **not**
  fall into this category — it retains its type parameter and wires up as usual.
- `CellConverter` beans are not auto-discovered: the field's target type cannot be
  inferred from a bean, and the explicit case is already covered by
  `@ExcelColumn(converter = ...)`. Type-based registration is manual via
  `ExcelImporter.builder(...).converter(TargetType.class, conv)` / `create(type,
  config)` with subsequent tuning — a deliberate starter limitation.
- Your own `ExcelImporterFactory` bean disables auto-configuration (`@ConditionalOnMissingBean`).

## Validation

Three sequential layers; an error in any layer marks the row rejected (red in the
report), while the remaining rows keep being processed:

1. **Converters.** A `CellConverter` turns a `CellValue` into the field's target
   type. Built-ins exist for primitives, strings, dates/times, `BigDecimal`, boolean
   (with a `booleanWords` dictionary), and enums. A custom converter — via
   `@ExcelColumn(converter = ...)` or
   `ExcelImporter.builder(...).converter(TargetType.class, myConverter)`.
   A conversion error is `ErrorKind.CONVERSION`.
2. **Jakarta Bean Validation** (Hibernate Validator) on the mapped object — standard
   annotations `@NotNull`, `@Size`, `@PastOrPresent`, …
   Errors are `ErrorKind.CONSTRAINT`. Non-standard row-level logic is expressed as a
   custom constraint annotation (a separate imperative SPI is intentionally absent at
   this level):

   ```java
   @Target(ElementType.TYPE) @Retention(RetentionPolicy.RUNTIME)
   @Constraint(validatedBy = HireDateBeforeFireDateValidator.class)
   public @interface HireDateBeforeFireDate {
       String message() default "Fire date is earlier than hire date";
       Class<?>[] groups() default {};
       Class<? extends Payload>[] payload() default {};
   }
   ```

   Messages are taken from `ValidationMessages.properties`; the locale is set by
   `ImportConfig.locale` (Russian messages are bundled).

   The library bundle is intentionally named
   `org.novgorodtsev.excelimport.ValidationMessages` and **not** `ValidationMessages`
   at the classpath root — so it does not clash with a consumer's same-named bundle
   (`ResourceBundle` resolves a whole bundle, not per key: two same-named bundles on
   the classpath and one silently "loses"). Message resolution order: first the
   consumer's root `ValidationMessages`, then the library bundle, and only then the
   built-in Hibernate Validator messages — so any library message can be overridden
   by your own file. EL expressions `${...}` are never evaluated (no `jakarta.el`
   implementation is present on the classpath by design) and are left verbatim in the
   message; `{param}` placeholders are interpolated normally. Literal `{`, `}`, and
   `$` in message text are escaped with a backslash: `\{`, `\}`, `\$`.
3. **`BatchValidator<T>`** — the only imperative user hook. It is called on an
   assembled batch before insertion, within the same transaction — so it sees data
   inserted by previous batches of the run and can verify everything with a single
   DB query:

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
                   errors.add(new RowError(ref.rowNum(), "Personnel No.", String.valueOf(no),
                           ErrorKind.BATCH, "DUPLICATE_IN_FILE",
                           "Duplicate of row " + prev + " in this file"));
               }
           }
           // ... plus a check against already-existing rows via one SELECT ... WHERE key IN (...)
           return errors;
       }
   }
   ```

   Contract: the validator must not commit, roll back, or close the connection (the
   proxy blocks such calls with `IllegalStateException`). Rows for which errors are
   returned are excluded from the batch; the rest are inserted. Several validators
   run in registration order; errors are merged.

## Report

If `reportPath` is set, a `.xlsx` report is generated after the import (a separate
streaming pass over the source file):

- the header row is copied, and **"Import status"** and **"Reason"** columns are
  added on the right (the "Reason" holds all of the row's errors joined with `; `);
- cell values are copied as-is (numbers as numbers, dates as dates with the original
  format);
- data rows are shaded: green `#C6EFCE` — inserted, red `#FFC7CE` — rejected, grey
  `#F2F2F2` — never reached processing (stopped by `maxErrors`/a fatal error);
- the header is frozen and auto-filter is enabled; there is more than one sheet if
  the row count exceeds the sheet limit (1 048 576) — `Report 1`, `Report 2`, …;
- a second sheet — **"Summary"**: file, `runId`, start time/duration, counters,
  top-10 error codes;
- the file is written to a temp file next to the target and renamed atomically
  (`ATOMIC_MOVE`) — the consumer never sees a half-written file;
- a report-generation error does not roll back the already-completed import: the
  status becomes `PARTIAL` and the error lands in `ImportReport.errors`.

**Formatting caveat.** Styles from the source file are not carried over (they are
expensive under streaming): the report applies its own minimal style plus a date
format. This is a deliberate trade-off — the report is functional, not a
pixel-perfect copy.

Styling is configurable via `ReportStyle` (in POI terms):

```java
ReportStyle style = ReportStyle.builder()
        .insertedFill(new XSSFColor(new byte[] {(byte) 0xC6, (byte) 0xEF, (byte) 0xCE}))
        .rejectedFill(IndexedColors.ROSE)
        .skippedFill(IndexedColors.GREY_25_PERCENT)
        .fillPattern(FillPatternType.SOLID_FOREGROUND)
        .statusColumnHeader("Import status")
        .reasonColumnHeader("Reason")
        .reasonAlignment(HorizontalAlignment.LEFT)
        .dateFormat("dd.MM.yyyy")
        .build();
```

For non-standard requirements — the `ReportRowCustomizer` hook, which receives real
POI objects of the workbook being written:

```java
public interface ReportRowCustomizer {
    default void customizeHeader(SXSSFRow header, ReportContext ctx) {}
    void customizeRow(SXSSFRow row, RowOutcome outcome, ReportContext ctx);
    default void finish(SXSSFWorkbook workbook, ImportReport report) {}
}
```

Contract: do not touch rows outside the SXSSF flush window and do not call
`workbook.write()`/`dispose()` — `ReportWriter` manages that. Pull styles from
`ReportContext.styleFor(status, dataFormat)` to avoid hitting the 64 000 unique
`CellStyle` per-workbook limit.

## How DB errors are handled

One batch — one multi-row `INSERT ... VALUES (...), (...), ...` and one transaction
(`commit` after the batch; the connection returns to the pool). On `SQLException`
the batch transaction is rolled back entirely and **recursive bisection** starts:
the failing batch is split in half, each half is inserted in its own transaction,
and so on down to a single row — eventually the "bad" row gets its own `DATABASE`
error (SQLState, constraint name, message) while all sound rows of the batch are
inserted. Split depth is bounded by `maxSplitDepth` (16 by default — enough for a
batch of up to 65 536 rows); when depth is exhausted, all remaining rows of a part
are marked with a common error.

Error classification (what is fatal vs. recoverable) is factored into
`SqlErrorClassifier` and is replaceable:

The default implementation is conservative: an unknown SQLState or a stateless
exception is treated as fatal, to avoid splitting a batch blind during a systemic
problem.

| SQLState | Category | Behavior |
|---|---|---|
| `08*` (connection loss) | fatal | import is aborted, `status = FAILED` |
| `53*` (resources exhausted), `57*`, `58*`, `F0*`, `XX*` | fatal | import is aborted |
| `42P01`, `42703`, `42P07` (no table/column, duplicate table) | fatal | import is aborted |
| `42501` (no privileges), `42601`, `3D000`, `28P01`, `28000` | fatal | import is aborted |
| `23*` (constraint violation) | recoverable | bisection down to the culprit row |
| `22*` (data type errors) | recoverable | bisection down to the culprit row |
| anything else / undeterminable | fatal | conservative default |

Already-committed batches are not rolled back on a fatal error; the report is still
generated with a stop note.

The DB error message that lands in the report is extracted from `PSQLException`
(`ServerErrorMessage`: `constraint`, `detail`, `column`). The
`includeDatabaseDetailInReport` option (default `true`) turns off putting `detail`
into the report — enable it deliberately if `detail` may contain sensitive values.

**The 65 535 bind-parameter limit** of PostgreSQL per query is handled
automatically: the effective chunk size is `min(batchSize, 65535 / columnCount)`,
an oversized batch is silently split into sub-queries within the same transaction
(a warning is logged once per run). The SQL template is cached by chunk size.
`reWriteBatchedInserts=true` in the driver URL is not required (multi-row VALUES
does the same thing) but does no harm.

## Limitations

- Only `.xlsx` (OOXML). Legacy `.xls` (BIFF/HSSF) is not supported.
- The target table must exist: the library does not create or migrate the schema.
- `INSERT` only (optionally with `ON CONFLICT`). No `UPDATE`/`DELETE`.
- Formulas are not evaluated: the cached value is read; if the cache is empty,
  behavior is governed by `formulaPolicy` (the cell is treated as `null` by default).
- 65 535 bind-parameter limit: chunk splitting is automatic (see above), no need to
  account for it manually.
- After row outcomes are spilled to disk (above `maxOutcomeMessagesInMemory`
  messages), outcomes can only be read in ascending `rowNum` order — the report's
  second pass does exactly that.
- Maximum rows per Excel sheet — 1 048 576; beyond that the report is split across
  sheets.

## Observability

Run progress and events — via `ImportListener` (progress bar, checkpoints in your
own table, metrics):

```java
public interface ImportListener {
    default void onImportStarted(ImportRunInfo info) {}
    default void onBatchCommitted(int batchIndex, int rowCount, long totalInserted) {}
    default void onBatchSplit(int batchIndex, int depth, int rowCount) {}
    default void onRowRejected(RowError error) {}
    default void onImportFinished(ImportReport report) {}
}
```

An exception from any listener method is logged and does not affect the import.

Logging via SLF4J: `INFO` on start/finish/batch commit, `WARN` on batch splitting
and rejections, `DEBUG` on the SQL template and timings. **No cell value is logged
above `TRACE` level** — the data may be personal.

## Performance

Reading is streaming (XSSFReader + SAX); the report goes through `SXSSFWorkbook`
with a 100-row window; between passes only the compact row-outcome map is in memory.
Verified by a performance test: importing 100 000 rows succeeds with a 256 MB heap
(`./gradlew performanceTest`, `maxHeapSize = 256m`).

## Building the project

Gradle 8.12 (wrapper committed), multi-project, Java 17 (toolchain):

```bash
./gradlew build              # compile + unit tests + javadoc/sources jar
./gradlew test               # unit tests only (no Docker)
./gradlew check              # unit + integration (Testcontainers PostgreSQL 16) — Docker required
./gradlew integrationTest    # integration tests only
./gradlew performanceTest    # performance tests (256 MB heap), not part of check
```

Compilation uses `-Xlint:all -Werror` — a warning breaks the build. The public API
is the package `org.novgorodtsev.excelimport` and its subpackages, **except**
`org.novgorodtsev.excelimport.internal.*` (private, no compatibility guarantees).