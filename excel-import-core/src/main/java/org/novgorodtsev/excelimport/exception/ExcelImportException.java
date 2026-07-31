package org.novgorodtsev.excelimport.exception;

/** Базовое непроверяемое исключение библиотеки. */
public class ExcelImportException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public ExcelImportException(String message) {
        super(message);
    }

    public ExcelImportException(String message, Throwable cause) {
        super(message, cause);
    }
}
