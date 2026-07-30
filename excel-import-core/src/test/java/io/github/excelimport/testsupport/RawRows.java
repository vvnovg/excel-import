package io.github.excelimport.testsupport;

import io.github.excelimport.convert.CellValue;
import io.github.excelimport.internal.read.ImmutableCellValue;
import io.github.excelimport.internal.read.RawRow;
import java.lang.reflect.Constructor;
import java.util.HashMap;
import java.util.Map;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.util.CellAddress;

/** Сборка {@link RawRow} из строковых значений без файла. Только для тестов. */
public final class RawRows {

    private RawRows() {}

    public static RawRow of(int rowIndex, String... values) {
        Map<Integer, CellValue> cells = new HashMap<>();
        for (int i = 0; i < values.length; i++) {
            if (values[i] == null) {
                continue;
            }
            cells.put(i, new ImmutableCellValue(
                    new CellAddress(rowIndex, i), CellType.STRING, false, values[i],
                    null, null, null, (byte) -1, false));
        }
        return newRawRow(rowIndex, cells);
    }

    public static RawRow ofCells(int rowIndex, Map<Integer, CellValue> cells) {
        return newRawRow(rowIndex, new HashMap<>(cells));
    }

    private static RawRow newRawRow(int rowIndex, Map<Integer, CellValue> cells) {
        try {
            Constructor<RawRow> ctor = RawRow.class.getDeclaredConstructor(int.class, Map.class);
            ctor.setAccessible(true);
            return ctor.newInstance(rowIndex, cells);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("не удалось создать RawRow", e);
        }
    }
}
