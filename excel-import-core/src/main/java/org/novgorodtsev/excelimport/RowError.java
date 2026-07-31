package org.novgorodtsev.excelimport;

import java.util.Objects;

/**
 * Ошибка, привязанная к строке файла.
 *
 * @param rowNum       1-based номер строки Excel
 * @param columnHeader заголовок колонки; null для ошибок уровня строки и батча
 * @param rawValue     исходный текст ячейки; null, если ошибка не привязана к ячейке
 * @param kind         категория ошибки
 * @param code         машиночитаемый код: имя constraint, SQLState, свой код конвертера
 * @param message      человекочитаемое сообщение, попадает в Excel-отчёт
 */
public record RowError(
        int rowNum,
        String columnHeader,
        String rawValue,
        ErrorKind kind,
        String code,
        String message) {

    public RowError {
        if (rowNum < 1) {
            throw new IllegalArgumentException("номер строки 1-based, получено: " + rowNum);
        }
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(code, "code");
        if (message == null || message.isBlank()) {
            throw new IllegalArgumentException("сообщение об ошибке не может быть пустым");
        }
    }

    public static RowError structure(int rowNum, String columnHeader, String code, String message) {
        return new RowError(rowNum, columnHeader, null, ErrorKind.STRUCTURE, code, message);
    }

    public static RowError conversion(
            int rowNum, String columnHeader, String rawValue, String code, String message) {
        return new RowError(rowNum, columnHeader, rawValue, ErrorKind.CONVERSION, code, message);
    }

    public static RowError constraint(
            int rowNum, String columnHeader, String rawValue, String code, String message) {
        return new RowError(rowNum, columnHeader, rawValue, ErrorKind.CONSTRAINT, code, message);
    }

    public static RowError batch(int rowNum, String code, String message) {
        return new RowError(rowNum, null, null, ErrorKind.BATCH, code, message);
    }

    public static RowError database(int rowNum, String code, String message) {
        return new RowError(rowNum, null, null, ErrorKind.DATABASE, code, message);
    }
}
