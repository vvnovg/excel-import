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
