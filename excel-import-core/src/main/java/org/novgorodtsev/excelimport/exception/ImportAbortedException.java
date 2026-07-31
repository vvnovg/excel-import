package org.novgorodtsev.excelimport.exception;

import org.novgorodtsev.excelimport.ImportReport;

/** Импорт прерван: превышен лимит ошибок или произошёл фатальный сбой БД. */
public class ImportAbortedException extends ExcelImportException {

    private static final long serialVersionUID = 1L;

    private final transient ImportReport partialReport;

    public ImportAbortedException(String message, ImportReport partialReport) {
        super(message);
        this.partialReport = partialReport;
    }

    public ImportAbortedException(String message, Throwable cause, ImportReport partialReport) {
        super(message, cause);
        this.partialReport = partialReport;
    }

    /** Отчёт о том, что успело выполниться до прерывания. */
    public ImportReport partialReport() {
        return partialReport;
    }
}
