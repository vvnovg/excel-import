package io.github.excelimport.internal.write;

import io.github.excelimport.exception.ExcelImportException;
import io.github.excelimport.internal.map.ColumnBinding;
import java.lang.invoke.MethodHandle;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Types;
import java.util.ArrayList;
import java.util.List;

/**
 * Привязывает поля объекта к параметрам {@link PreparedStatement}.
 * Порядок соответствует {@code MappingModel.allDbColumns()}.
 */
public final class RowBinder<T> {

    private final List<MethodHandle> getters;

    public RowBinder(List<ColumnBinding> bindings) {
        List<MethodHandle> handles = new ArrayList<>(bindings.size());
        for (ColumnBinding binding : bindings) {
            handles.add(binding.getter());
        }
        this.getters = List.copyOf(handles);
    }

    public int columnCount() {
        return getters.size();
    }

    /**
     * @param startParameterIndex 1-based индекс первого параметра этой строки
     */
    public void bind(PreparedStatement statement, int startParameterIndex, T value)
            throws SQLException {
        int index = startParameterIndex;
        for (MethodHandle getter : getters) {
            Object fieldValue;
            try {
                fieldValue = getter.invoke(value);
            } catch (Throwable e) {
                throw new ExcelImportException("не удалось прочитать поле объекта строки", e);
            }
            if (fieldValue == null) {
                statement.setNull(index, Types.NULL);
            } else {
                // setObject справляется с java.time.* через драйвер pgjdbc
                statement.setObject(index, fieldValue);
            }
            index++;
        }
    }
}
