package org.novgorodtsev.excelimport.exception;

/** Не удалось сформировать Excel-отчёт. Импорт при этом уже выполнен. */
public class ReportGenerationException extends ExcelImportException {

    private static final long serialVersionUID = 1L;

    public ReportGenerationException(String message) {
        super(message);
    }

    public ReportGenerationException(String message, Throwable cause) {
        super(message, cause);
    }
}
