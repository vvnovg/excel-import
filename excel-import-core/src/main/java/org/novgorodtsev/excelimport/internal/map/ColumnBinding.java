package org.novgorodtsev.excelimport.internal.map;

import org.novgorodtsev.excelimport.convert.CellConverter;
import java.lang.invoke.MethodHandle;
import java.util.List;
import java.util.Objects;

/**
 * Привязка одного поля класса к колонке Excel и/или колонке БД.
 * Для полей «только БД» {@code headerName} и {@code columnIndex} равны null.
 * Для полей «только Excel» ({@code @ExcelColumn(insertable = false)}) {@code dbColumn} равен null.
 */
public record ColumnBinding(
        String fieldName,
        String headerName,
        Integer columnIndex,
        boolean required,
        String dbColumn,
        Class<?> fieldType,
        MethodHandle setter,
        MethodHandle getter,
        List<String> formats,
        boolean trim,
        boolean emptyAsNull,
        Class<? extends CellConverter<?>> converterType) {

    public ColumnBinding {
        Objects.requireNonNull(fieldName, "fieldName");
        Objects.requireNonNull(fieldType, "fieldType");
        Objects.requireNonNull(setter, "setter");
        Objects.requireNonNull(getter, "getter");
        formats = List.copyOf(formats);
    }

    /** true, если поле читается из файла. */
    public boolean boundToExcel() {
        return headerName != null || columnIndex != null;
    }

    /** true, если поле участвует во вставке. */
    public boolean insertable() {
        return dbColumn != null;
    }

    /** Отображаемое имя колонки для сообщений об ошибках. */
    public String displayName() {
        if (headerName != null) {
            return headerName;
        }
        if (columnIndex != null) {
            return org.apache.poi.ss.util.CellReference.convertNumToColString(columnIndex);
        }
        return fieldName;
    }
}
