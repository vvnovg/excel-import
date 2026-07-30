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
        // close() SXSSFWorkbook удаляет временные файлы flush-окна (POI >= 5.4),
        // отдельный dispose() не нужен и deprecated
        try (SXSSFWorkbook workbook = new SXSSFWorkbook(FLUSH_WINDOW)) {
            workbook.setCompressTempFiles(true);
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
