package org.novgorodtsev.excelimport.exception;

/**
 * Некорректная конфигурация маппинга: отсутствует {@code @ExcelSheet}, дубли {@code @Column},
 * нет конвертера для типа. Бросается при сборке {@code ExcelImporter}, не во время импорта.
 */
public class MappingConfigurationException extends ExcelImportException {

    private static final long serialVersionUID = 1L;

    public MappingConfigurationException(String message) {
        super(message);
    }

    public MappingConfigurationException(String message, Throwable cause) {
        super(message, cause);
    }
}
