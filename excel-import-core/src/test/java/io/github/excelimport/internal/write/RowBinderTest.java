package io.github.excelimport.internal.write;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import io.github.excelimport.NamingStrategy;
import io.github.excelimport.annotation.ExcelColumn;
import io.github.excelimport.annotation.ExcelSheet;
import io.github.excelimport.internal.map.MappingModel;
import io.github.excelimport.internal.map.MappingModelFactory;
import java.math.BigDecimal;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Types;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

class RowBinderTest {

    @ExcelSheet(name = "S")
    static class Employee {
        @ExcelColumn(header = "Номер")
        Long personnelNo;

        @ExcelColumn(header = "ФИО")
        String fullName;

        @ExcelColumn(header = "Оклад")
        BigDecimal salary;

        @ExcelColumn(header = "Дата")
        LocalDate hiredAt;
    }

    private final MappingModel<Employee> model =
            MappingModelFactory.create(Employee.class, NamingStrategy.SNAKE_CASE);

    @Test
    void bindsAllColumnsInDeclaredOrder() throws SQLException {
        PreparedStatement stmt = mock(PreparedStatement.class);
        Employee value = new Employee();
        value.personnelNo = 42L;
        value.fullName = "Иванов";
        value.salary = new BigDecimal("1000.50");
        value.hiredAt = LocalDate.of(2026, 7, 29);

        new RowBinder<Employee>(model.allBindings()).bind(stmt, 1, value);

        InOrder order = inOrder(stmt);
        order.verify(stmt).setObject(1, 42L);
        order.verify(stmt).setObject(2, "Иванов");
        order.verify(stmt).setObject(3, new BigDecimal("1000.50"));
        order.verify(stmt).setObject(4, LocalDate.of(2026, 7, 29));
    }

    @Test
    void nullBecomesSetNull() throws SQLException {
        PreparedStatement stmt = mock(PreparedStatement.class);

        new RowBinder<Employee>(model.allBindings()).bind(stmt, 1, new Employee());

        verify(stmt, org.mockito.Mockito.times(4)).setNull(anyInt(), org.mockito.Mockito.eq(Types.NULL));
    }

    @Test
    void parameterIndexOffsetShiftsAllColumns() throws SQLException {
        PreparedStatement stmt = mock(PreparedStatement.class);
        Employee value = new Employee();
        value.personnelNo = 1L;

        new RowBinder<Employee>(model.allBindings()).bind(stmt, 5, value);

        verify(stmt).setObject(5, 1L);
    }

    @Test
    void columnCountMatchesBindingCount() {
        assertThat(new RowBinder<Employee>(model.allBindings()).columnCount()).isEqualTo(4);
    }
}
