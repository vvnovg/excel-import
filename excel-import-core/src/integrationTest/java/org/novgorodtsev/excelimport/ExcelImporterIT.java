package org.novgorodtsev.excelimport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.novgorodtsev.excelimport.annotation.Column;
import org.novgorodtsev.excelimport.annotation.ExcelColumn;
import org.novgorodtsev.excelimport.annotation.ExcelSheet;
import org.novgorodtsev.excelimport.annotation.TargetTable;
import org.novgorodtsev.excelimport.exception.FileStructureException;
import org.novgorodtsev.excelimport.exception.ImportAbortedException;
import org.novgorodtsev.excelimport.testsupport.PostgresSupport;
import org.novgorodtsev.excelimport.testsupport.XlsxFixtures;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import javax.sql.DataSource;
import org.apache.poi.ss.usermodel.Row;
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

    /** Тот же маппинг, но заголовок в третьей строке: до него титул и пустая строка. */
    @ExcelSheet(name = "Лист1", headerRow = 2)
    @TargetTable(name = "employee")
    public static class EmployeeWithTitle {
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

        public EmployeeWithTitle() {}
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

    /**
     * Второй проход обязан использовать ту же геометрию строк, что и первый: строки до
     * заголовка копируются как есть, заголовком считается строка {@code headerRow}, а
     * разметка исходов начинается с {@code firstDataRow}. Иначе первая же встреченная
     * строка (титул) принимается за заголовок, колонки статуса/причины съезжают влево и
     * затирают настоящие данные — молча, без исключения.
     */
    @Test
    void reportHonoursHeaderRowOffset() throws Exception {
        Path file = fixture(new Object[][] {
            {"Отчёт по сотрудникам"}, // титул: одна ячейка
            {null}, // строка есть в файле, но пустая
            {"Номер", "ФИО", "Стаж"},
            {1, "Иванов", 3},
            {2, "", 5}, // NotBlank — строка отклоняется
        });
        Path reportPath = tempDir.resolve("report-with-title.xlsx");

        try (ExcelImporter<EmployeeWithTitle> excelImporter =
                ExcelImporter.builder(EmployeeWithTitle.class)
                        .dataSource(dataSource)
                        .config(ImportConfig.builder().reportPath(reportPath).build())
                        .build()) {
            ImportReport report = excelImporter.importFile(file);

            assertThat(report.totalRows()).isEqualTo(2);
            assertThat(report.insertedRows()).isEqualTo(1);
            assertThat(report.rejectedRows()).isEqualTo(1);
        }

        try (Workbook workbook = new XSSFWorkbook(Files.newInputStream(reportPath))) {
            Sheet sheet = workbook.getSheetAt(0);

            // титул скопирован как есть и не размечен как строка данных
            Row title = sheet.getRow(0);
            assertThat(title.getCell(0).getStringCellValue()).isEqualTo("Отчёт по сотрудникам");
            assertThat(title.getCell(3)).isNull();
            assertThat(title.getCell(4)).isNull();

            // настоящий заголовок остался заголовком: все исходные колонки на месте
            Row header = sheet.getRow(2);
            assertThat(header.getCell(0).getStringCellValue()).isEqualTo("Номер");
            assertThat(header.getCell(1).getStringCellValue()).isEqualTo("ФИО");
            assertThat(header.getCell(2).getStringCellValue()).isEqualTo("Стаж");
            assertThat(header.getCell(3).getStringCellValue()).isEqualTo("Статус импорта");
            assertThat(header.getCell(4).getStringCellValue()).isEqualTo("Причина");

            // строки данных сохранили каждую исходную колонку и размечены верно
            Row inserted = sheet.getRow(3);
            assertThat(inserted.getCell(0).getNumericCellValue()).isEqualTo(1.0);
            assertThat(inserted.getCell(1).getStringCellValue()).isEqualTo("Иванов");
            assertThat(inserted.getCell(2).getNumericCellValue()).isEqualTo(3.0);
            assertThat(inserted.getCell(3).getStringCellValue()).isEqualTo("Загружено");

            Row rejected = sheet.getRow(4);
            assertThat(rejected.getCell(0).getNumericCellValue()).isEqualTo(2.0);
            assertThat(rejected.getCell(2).getNumericCellValue()).isEqualTo(5.0);
            assertThat(rejected.getCell(3).getStringCellValue()).isEqualTo("Ошибка");
            assertThat(rejected.getCell(4).getStringCellValue()).contains("ФИО");
        }
    }

    /**
     * {@code headerRow}/{@code firstDataRow} из {@link ImportConfig} — самостоятельные
     * настройки, а не довесок к {@code sheet}: они обязаны переопределять
     * {@code @ExcelSheet} и тогда, когда лист берётся из аннотации.
     */
    @Test
    void configHeaderRowOverridesAnnotationWithoutSheetOverride() {
        Path file = fixture(new Object[][] {
            {"Отчёт по сотрудникам"},
            {null},
            {"Номер", "ФИО", "Стаж"},
            {1, "Иванов", 3},
        });

        // Employee объявляет @ExcelSheet(headerRow = 0); sheet намеренно не переопределяем
        try (ExcelImporter<Employee> excelImporter =
                importer(ImportConfig.builder().headerRow(2).build()).build()) {
            ImportReport report = excelImporter.importFile(file);

            assertThat(report.status()).isEqualTo(ImportStatus.SUCCESS);
            assertThat(report.totalRows()).isEqualTo(1);
            assertThat(report.insertedRows()).isEqualTo(1);
            assertThat(PostgresSupport.countRows("employee")).isEqualTo(1);
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
