package org.novgorodtsev.excelimport;

import java.util.Objects;

/**
 * Смапленный объект вместе с 1-based номером строки Excel, из которой он получен.
 *
 * @param rowNum 1-based номер строки, как в интерфейсе Excel
 * @param value  смапленный и прошедший валидацию объект
 */
public record RowRef<T>(int rowNum, T value) {

    public RowRef {
        if (rowNum < 1) {
            throw new IllegalArgumentException("номер строки 1-based, получено: " + rowNum);
        }
        Objects.requireNonNull(value, "value");
    }
}
