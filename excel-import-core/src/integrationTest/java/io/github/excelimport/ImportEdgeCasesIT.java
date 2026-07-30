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

    /** Число колонок в «широкой» модели: чанк = 65535 / 70 = 936 < batchSize 1000. */
    private static final int WIDE_COLUMNS = 70;

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

    /**
     * Отклонение от первоначального брифа: дробление чанков считается по числу колонок
     * МОДЕЛИ ({@code SqlBuilder.columnCount()}), а не по ширине таблицы в БД — INSERT
     * содержит только смапленные колонки. Поэтому вместо модели на 2 поля против таблицы
     * на 700 колонок (там дробления не было бы вовсе) модель имеет {@value #WIDE_COLUMNS}
     * колонок: батч из 1000 строк = 70 000 параметров > предела 65535, и без дробления
     * на чанки по 936 строк драйвер PostgreSQL отклонил бы запрос. Успех вставки —
     * доказательство того, что дробление отработало внутри одной транзакции.
     */
    @Test
    void wideTableIsSplitIntoChunksWithinOneTransaction() {
        PostgresSupport.execute(
                "DROP TABLE IF EXISTS wide",
                "CREATE TABLE wide (" + wideColumnsDdl(WIDE_COLUMNS) + ")");

        try (ExcelImporter<WideRow> excelImporter = ExcelImporter.builder(WideRow.class)
                .dataSource(dataSource)
                .config(ImportConfig.builder().batchSize(1000).build())
                .build()) {
            ImportReport report = excelImporter.importFile(wideFixture(1200));

            assertThat(report.status()).isEqualTo(ImportStatus.SUCCESS);
            assertThat(report.insertedRows()).isEqualTo(1200);
            assertThat(PostgresSupport.countRows("wide")).isEqualTo(1200);
        }
    }

    private static String wideColumnsDdl(int count) {
        StringBuilder ddl = new StringBuilder("c0 int PRIMARY KEY");
        for (int i = 1; i < count; i++) {
            ddl.append(", c").append(i).append(" int");
        }
        return ddl.toString();
    }

    /** Модель на {@value #WIDE_COLUMNS} колонок с индексной привязкой. */
    @ExcelSheet(name = "Лист1")
    @TargetTable(name = "wide")
    public static class WideRow {
        @ExcelColumn(index = 0)
        @Column("c0")
        public Integer c0;

        @ExcelColumn(index = 1)
        @Column("c1")
        public Integer c1;

        @ExcelColumn(index = 2)
        @Column("c2")
        public Integer c2;

        @ExcelColumn(index = 3)
        @Column("c3")
        public Integer c3;

        @ExcelColumn(index = 4)
        @Column("c4")
        public Integer c4;

        @ExcelColumn(index = 5)
        @Column("c5")
        public Integer c5;

        @ExcelColumn(index = 6)
        @Column("c6")
        public Integer c6;

        @ExcelColumn(index = 7)
        @Column("c7")
        public Integer c7;

        @ExcelColumn(index = 8)
        @Column("c8")
        public Integer c8;

        @ExcelColumn(index = 9)
        @Column("c9")
        public Integer c9;

        @ExcelColumn(index = 10)
        @Column("c10")
        public Integer c10;

        @ExcelColumn(index = 11)
        @Column("c11")
        public Integer c11;

        @ExcelColumn(index = 12)
        @Column("c12")
        public Integer c12;

        @ExcelColumn(index = 13)
        @Column("c13")
        public Integer c13;

        @ExcelColumn(index = 14)
        @Column("c14")
        public Integer c14;

        @ExcelColumn(index = 15)
        @Column("c15")
        public Integer c15;

        @ExcelColumn(index = 16)
        @Column("c16")
        public Integer c16;

        @ExcelColumn(index = 17)
        @Column("c17")
        public Integer c17;

        @ExcelColumn(index = 18)
        @Column("c18")
        public Integer c18;

        @ExcelColumn(index = 19)
        @Column("c19")
        public Integer c19;

        @ExcelColumn(index = 20)
        @Column("c20")
        public Integer c20;

        @ExcelColumn(index = 21)
        @Column("c21")
        public Integer c21;

        @ExcelColumn(index = 22)
        @Column("c22")
        public Integer c22;

        @ExcelColumn(index = 23)
        @Column("c23")
        public Integer c23;

        @ExcelColumn(index = 24)
        @Column("c24")
        public Integer c24;

        @ExcelColumn(index = 25)
        @Column("c25")
        public Integer c25;

        @ExcelColumn(index = 26)
        @Column("c26")
        public Integer c26;

        @ExcelColumn(index = 27)
        @Column("c27")
        public Integer c27;

        @ExcelColumn(index = 28)
        @Column("c28")
        public Integer c28;

        @ExcelColumn(index = 29)
        @Column("c29")
        public Integer c29;

        @ExcelColumn(index = 30)
        @Column("c30")
        public Integer c30;

        @ExcelColumn(index = 31)
        @Column("c31")
        public Integer c31;

        @ExcelColumn(index = 32)
        @Column("c32")
        public Integer c32;

        @ExcelColumn(index = 33)
        @Column("c33")
        public Integer c33;

        @ExcelColumn(index = 34)
        @Column("c34")
        public Integer c34;

        @ExcelColumn(index = 35)
        @Column("c35")
        public Integer c35;

        @ExcelColumn(index = 36)
        @Column("c36")
        public Integer c36;

        @ExcelColumn(index = 37)
        @Column("c37")
        public Integer c37;

        @ExcelColumn(index = 38)
        @Column("c38")
        public Integer c38;

        @ExcelColumn(index = 39)
        @Column("c39")
        public Integer c39;

        @ExcelColumn(index = 40)
        @Column("c40")
        public Integer c40;

        @ExcelColumn(index = 41)
        @Column("c41")
        public Integer c41;

        @ExcelColumn(index = 42)
        @Column("c42")
        public Integer c42;

        @ExcelColumn(index = 43)
        @Column("c43")
        public Integer c43;

        @ExcelColumn(index = 44)
        @Column("c44")
        public Integer c44;

        @ExcelColumn(index = 45)
        @Column("c45")
        public Integer c45;

        @ExcelColumn(index = 46)
        @Column("c46")
        public Integer c46;

        @ExcelColumn(index = 47)
        @Column("c47")
        public Integer c47;

        @ExcelColumn(index = 48)
        @Column("c48")
        public Integer c48;

        @ExcelColumn(index = 49)
        @Column("c49")
        public Integer c49;

        @ExcelColumn(index = 50)
        @Column("c50")
        public Integer c50;

        @ExcelColumn(index = 51)
        @Column("c51")
        public Integer c51;

        @ExcelColumn(index = 52)
        @Column("c52")
        public Integer c52;

        @ExcelColumn(index = 53)
        @Column("c53")
        public Integer c53;

        @ExcelColumn(index = 54)
        @Column("c54")
        public Integer c54;

        @ExcelColumn(index = 55)
        @Column("c55")
        public Integer c55;

        @ExcelColumn(index = 56)
        @Column("c56")
        public Integer c56;

        @ExcelColumn(index = 57)
        @Column("c57")
        public Integer c57;

        @ExcelColumn(index = 58)
        @Column("c58")
        public Integer c58;

        @ExcelColumn(index = 59)
        @Column("c59")
        public Integer c59;

        @ExcelColumn(index = 60)
        @Column("c60")
        public Integer c60;

        @ExcelColumn(index = 61)
        @Column("c61")
        public Integer c61;

        @ExcelColumn(index = 62)
        @Column("c62")
        public Integer c62;

        @ExcelColumn(index = 63)
        @Column("c63")
        public Integer c63;

        @ExcelColumn(index = 64)
        @Column("c64")
        public Integer c64;

        @ExcelColumn(index = 65)
        @Column("c65")
        public Integer c65;

        @ExcelColumn(index = 66)
        @Column("c66")
        public Integer c66;

        @ExcelColumn(index = 67)
        @Column("c67")
        public Integer c67;

        @ExcelColumn(index = 68)
        @Column("c68")
        public Integer c68;

        @ExcelColumn(index = 69)
        @Column("c69")
        public Integer c69;

        public WideRow() {}
    }

    private Path wideFixture(int rows) {
        Object[][] data = new Object[rows + 1][];
        data[0] = new Object[WIDE_COLUMNS];
        for (int column = 0; column < WIDE_COLUMNS; column++) {
            data[0][column] = "c" + column;
        }
        for (int row = 1; row <= rows; row++) {
            data[row] = new Object[WIDE_COLUMNS];
            for (int column = 0; column < WIDE_COLUMNS; column++) {
                data[row][column] = row + column;
            }
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
