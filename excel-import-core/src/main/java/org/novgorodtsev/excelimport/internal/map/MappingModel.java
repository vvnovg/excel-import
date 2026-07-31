package org.novgorodtsev.excelimport.internal.map;

import org.novgorodtsev.excelimport.SheetSelector;
import org.novgorodtsev.excelimport.TableRef;
import org.novgorodtsev.excelimport.exception.MappingConfigurationException;
import java.lang.invoke.MethodHandle;
import java.util.ArrayList;
import java.util.List;

/** Разобранная и провалидированная схема маппинга класса. Иммутабельна и переиспользуема. */
public final class MappingModel<T> {

    private final Class<T> type;
    private final SheetSelector sheet;
    private final int headerRowIndex;
    private final int firstDataRowIndex;
    private final TableRef table;
    private final List<ColumnBinding> excelColumns;
    private final List<ColumnBinding> dbOnlyColumns;
    private final MethodHandle constructor;

    MappingModel(
            Class<T> type,
            SheetSelector sheet,
            int headerRowIndex,
            int firstDataRowIndex,
            TableRef table,
            List<ColumnBinding> excelColumns,
            List<ColumnBinding> dbOnlyColumns,
            MethodHandle constructor) {
        this.type = type;
        this.sheet = sheet;
        this.headerRowIndex = headerRowIndex;
        this.firstDataRowIndex = firstDataRowIndex;
        this.table = table;
        this.excelColumns = List.copyOf(excelColumns);
        this.dbOnlyColumns = List.copyOf(dbOnlyColumns);
        this.constructor = constructor;
    }

    public Class<T> type() {
        return type;
    }

    public SheetSelector sheet() {
        return sheet;
    }

    public int headerRowIndex() {
        return headerRowIndex;
    }

    public int firstDataRowIndex() {
        return firstDataRowIndex;
    }

    public TableRef table() {
        return table;
    }

    /** Возвращает копию модели с другой таблицей — для переопределения из ImportConfig. */
    public MappingModel<T> withTable(TableRef override) {
        return new MappingModel<>(
                type, sheet, headerRowIndex, firstDataRowIndex, override,
                excelColumns, dbOnlyColumns, constructor);
    }

    /** Возвращает копию модели с другим листом и расположением заголовка. */
    public MappingModel<T> withSheet(SheetSelector override, int headerRow, int firstDataRow) {
        return new MappingModel<>(
                type, override, headerRow, firstDataRow, table,
                excelColumns, dbOnlyColumns, constructor);
    }

    public List<ColumnBinding> excelColumns() {
        return excelColumns;
    }

    public List<ColumnBinding> dbOnlyColumns() {
        return dbOnlyColumns;
    }

    /**
     * Имена колонок БД в порядке вставки: сначала excel-привязанные, потом db-only.
     * Поля {@code @ExcelColumn(insertable = false)} читаются из файла, но во вставку не идут
     * и в этот список не попадают.
     */
    public List<String> allDbColumns() {
        List<String> names = new ArrayList<>(excelColumns.size() + dbOnlyColumns.size());
        allBindings().forEach(binding -> names.add(binding.dbColumn()));
        return List.copyOf(names);
    }

    /** Все вставляемые привязки в том же порядке, что {@link #allDbColumns()}. */
    public List<ColumnBinding> allBindings() {
        List<ColumnBinding> all = new ArrayList<>(excelColumns.size() + dbOnlyColumns.size());
        excelColumns.stream().filter(ColumnBinding::insertable).forEach(all::add);
        all.addAll(dbOnlyColumns);
        return List.copyOf(all);
    }

    public T newInstance() {
        try {
            return (T) constructor.invoke();
        } catch (Throwable e) {
            throw new MappingConfigurationException(
                    "не удалось создать экземпляр " + type.getName(), e);
        }
    }
}
