package org.novgorodtsev.excelimport.internal.read;

import org.novgorodtsev.excelimport.convert.CellValue;
import java.util.Map;
import org.apache.poi.ss.util.CellAddress;

/** Одна прочитанная строка листа. Индексы колонок 0-based, разреженные. */
public final class RawRow {

    private final int rowIndex;
    private final Map<Integer, CellValue> cells;

    RawRow(int rowIndex, Map<Integer, CellValue> cells) {
        this.rowIndex = rowIndex;
        this.cells = cells;
    }

    /** 0-based индекс строки, как в POI. */
    public int rowIndex() {
        return rowIndex;
    }

    /** 1-based номер строки, как в интерфейсе Excel. */
    public int excelRowNumber() {
        return rowIndex + 1;
    }

    /** Значение ячейки. Для отсутствующей ячейки возвращает пустое значение, никогда null. */
    public CellValue cell(int columnIndex) {
        CellValue value = cells.get(columnIndex);
        return value != null ? value : CellValue.blank(new CellAddress(rowIndex, columnIndex));
    }

    /** Наибольший заполненный индекс колонки, или -1 для пустой строки. */
    public int lastColumnIndex() {
        return cells.keySet().stream().mapToInt(Integer::intValue).max().orElse(-1);
    }

    /** true, если ни одна ячейка не содержит непустого значения. */
    public boolean isBlank() {
        return cells.values().stream()
                .allMatch(value -> value.isBlank()
                        || (value.asString() != null && value.asString().isBlank()));
    }
}
