package io.github.excelimport.convert;

import io.github.excelimport.exception.ExcelImportException;

/**
 * Значение ячейки не приводится к типу поля. Перехватывается маппером и превращается
 * в {@code RowError} — наружу из импорта не выходит.
 */
public class ConversionException extends ExcelImportException {

    private static final long serialVersionUID = 1L;

    private final String code;

    public ConversionException(String code, String message) {
        super(message);
        this.code = code;
    }

    public ConversionException(String code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
    }

    /** Машиночитаемый код, попадает в {@code RowError.code()}. */
    public String code() {
        return code;
    }
}
