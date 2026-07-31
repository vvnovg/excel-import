package org.novgorodtsev.excelimport.report;

import org.novgorodtsev.excelimport.ImportReport;
import org.novgorodtsev.excelimport.RowOutcome;
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
