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
import org.apache.poi.ss.usermodel.HorizontalAlignment;
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
    void reasonAlignmentIsAppliedToReasonCells() throws Exception {
        ReportStyle style = ReportStyle.builder()
                .reasonAlignment(HorizontalAlignment.RIGHT)
                .build();

        Path target = write(style, null);

        try (Workbook workbook = new XSSFWorkbook(Files.newInputStream(target))) {
            Sheet sheet = workbook.getSheetAt(0);
            assertThat(sheet.getRow(1).getCell(3).getCellStyle().getAlignment())
                    .isEqualTo(HorizontalAlignment.RIGHT);
            assertThat(sheet.getRow(2).getCell(3).getCellStyle().getAlignment())
                    .isEqualTo(HorizontalAlignment.RIGHT);
            // колонка статуса остаётся с выравниванием по умолчанию
            assertThat(sheet.getRow(1).getCell(2).getCellStyle().getAlignment())
                    .isNotEqualTo(HorizontalAlignment.RIGHT);
        }
    }

    @Test
    void sheetSplitKeepsBoundaryRowAndRepeatsHeader() throws Exception {
        Object[][] rows = new Object[7][];
        rows[0] = new Object[] {"ФИО", "Оклад"};
        for (int i = 1; i <= 6; i++) {
            rows[i] = new Object[] {"Сотрудник " + i, i};
        }
        Path source = XlsxFixtures.simpleSheet(tempDir, rows);
        Path target = tempDir.resolve("split-report.xlsx");

        // порог 4 вместо 1 048 576: первый лист вмещает заголовок + 2 строки данных,
        // затем идёт тот же код разбиения, что и при реальном пределе
        try (RowOutcomeStore store = new SpillableRowOutcomeStore(tempDir, 1000)) {
            for (int rowNum = 2; rowNum <= 7; rowNum++) {
                store.put(rowNum, rowNum % 2 == 0
                        ? RowOutcome.inserted()
                        : RowOutcome.rejected("ошибка " + rowNum));
            }
            store.seal();
            new ReportWriter(new PoiStreamingSheetReader(), ReportStyle.defaults(), null, 4)
                    .write(source, SheetSelector.first(), ReadOptions.defaults(), store,
                            report(target), target);
        }

        try (Workbook workbook = new XSSFWorkbook(Files.newInputStream(target))) {
            assertThat(workbook.getNumberOfSheets()).isEqualTo(4); // три листа отчёта + сводка

            // граничная строка осталась на первом листе полноценной строкой данных
            Sheet first = workbook.getSheet("Отчёт");
            assertThat(first.getLastRowNum()).isEqualTo(2); // заголовок + 2 строки данных
            Row boundary = first.getRow(2);
            assertThat(boundary.getCell(2).getStringCellValue()).isEqualTo("Ошибка");
            assertThat(boundary.getCell(3).getStringCellValue()).isEqualTo("ошибка 3");
            assertThat(((XSSFCellStyle) boundary.getCell(0).getCellStyle())
                    .getFillForegroundColorColor()).isNotNull();

            // второй лист начинается с настоящего заголовка, строка данных идёт следом
            Sheet second = workbook.getSheet("Отчёт 2");
            Row header = second.getRow(0);
            assertThat(header.getCell(0).getStringCellValue()).isEqualTo("ФИО");
            assertThat(header.getCell(1).getStringCellValue()).isEqualTo("Оклад");
            assertThat(header.getCell(2).getStringCellValue()).isEqualTo("Статус импорта");
            assertThat(header.getCell(3).getStringCellValue()).isEqualTo("Причина");

            assertThat(second.getRow(1).getCell(0).getStringCellValue()).isEqualTo("Сотрудник 3");
            assertThat(second.getRow(1).getCell(2).getStringCellValue()).isEqualTo("Загружено");
            assertThat(((XSSFCellStyle) second.getRow(1).getCell(0).getCellStyle())
                    .getFillForegroundColorColor()).isNotNull();
            assertThat(second.getLastRowNum()).isEqualTo(2); // заголовок + 2 строки данных
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
