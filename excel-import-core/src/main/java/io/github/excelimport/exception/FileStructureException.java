package io.github.excelimport.exception;

/**
 * Файл не соответствует ожидаемой структуре: нет листа, нет обязательной колонки, дубли
 * заголовков, повреждённый zip.
 */
public class FileStructureException extends ExcelImportException {

    private static final long serialVersionUID = 1L;

    public FileStructureException(String message) {
        super(message);
    }

    public FileStructureException(String message, Throwable cause) {
        super(message, cause);
    }
}
