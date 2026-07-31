package org.novgorodtsev.excelimport.internal.map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.novgorodtsev.excelimport.NamingStrategy;
import org.novgorodtsev.excelimport.annotation.Column;
import org.novgorodtsev.excelimport.annotation.ExcelColumn;
import org.novgorodtsev.excelimport.annotation.ExcelSheet;
import org.novgorodtsev.excelimport.annotation.TargetTable;
import org.novgorodtsev.excelimport.exception.MappingConfigurationException;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class MappingModelFactoryTest {

    @ExcelSheet(name = "Сотрудники", headerRow = 2)
    @TargetTable(schema = "hr", name = "employee")
    static class Employee {
        @ExcelColumn(header = "Табельный номер")
        @Column("personnel_no")
        Long personnelNo;

        @ExcelColumn(header = "ФИО")
        String fullName; // без @Column — имя выводится стратегией

        @ExcelColumn(letter = "D")
        @Column("salary")
        BigDecimal salary;

        @Column("import_run_id")
        String importRunId; // только БД, из файла не читается

        String ignored; // без аннотаций — игнорируется полностью
    }

    @Test
    void readsSheetAndTable() {
        MappingModel<Employee> model = MappingModelFactory.create(Employee.class, NamingStrategy.SNAKE_CASE);

        assertThat(model.sheet().name()).contains("Сотрудники");
        assertThat(model.headerRowIndex()).isEqualTo(2);
        assertThat(model.firstDataRowIndex()).isEqualTo(3);
        assertThat(model.table().qualifiedName()).isEqualTo("\"hr\".\"employee\"");
    }

    @Test
    void derivesDbColumnFromFieldNameWhenColumnAnnotationAbsent() {
        MappingModel<Employee> model = MappingModelFactory.create(Employee.class, NamingStrategy.SNAKE_CASE);

        assertThat(model.excelColumns())
                .filteredOn(binding -> "fullName".equals(binding.fieldName()))
                .singleElement()
                .satisfies(binding -> assertThat(binding.dbColumn()).isEqualTo("full_name"));
    }

    @Test
    void resolvesColumnLetterToIndex() {
        MappingModel<Employee> model = MappingModelFactory.create(Employee.class, NamingStrategy.SNAKE_CASE);

        assertThat(model.excelColumns())
                .filteredOn(binding -> "salary".equals(binding.fieldName()))
                .singleElement()
                .satisfies(binding -> assertThat(binding.columnIndex()).isEqualTo(3));
    }

    @Test
    void separatesDbOnlyColumnsFromExcelColumns() {
        MappingModel<Employee> model = MappingModelFactory.create(Employee.class, NamingStrategy.SNAKE_CASE);

        assertThat(model.excelColumns()).hasSize(3);
        assertThat(model.dbOnlyColumns()).singleElement()
                .satisfies(binding -> assertThat(binding.dbColumn()).isEqualTo("import_run_id"));
        assertThat(model.allDbColumns())
                .containsExactly("personnel_no", "full_name", "salary", "import_run_id");
    }

    @Test
    void setterWritesValueThroughMethodHandle() throws Throwable {
        MappingModel<Employee> model = MappingModelFactory.create(Employee.class, NamingStrategy.SNAKE_CASE);
        Employee instance = model.newInstance();

        ColumnBinding binding = model.excelColumns().stream()
                .filter(b -> "fullName".equals(b.fieldName()))
                .findFirst()
                .orElseThrow();
        binding.setter().invoke(instance, "Иванов");

        assertThat(instance.fullName).isEqualTo("Иванов");
    }

    static class NoSheet {
        @ExcelColumn(header = "A")
        String a;
    }

    @Test
    void classWithoutExcelSheetIsRejected() {
        assertThatThrownBy(() -> MappingModelFactory.create(NoSheet.class, NamingStrategy.SNAKE_CASE))
                .isInstanceOf(MappingConfigurationException.class)
                .hasMessageContaining("@ExcelSheet");
    }

    @ExcelSheet(name = "S")
    static class DuplicateDbColumn {
        @ExcelColumn(header = "A")
        @Column("same")
        String a;

        @ExcelColumn(header = "B")
        @Column("same")
        String b;
    }

    @Test
    void duplicateDbColumnIsRejected() {
        assertThatThrownBy(() ->
                        MappingModelFactory.create(DuplicateDbColumn.class, NamingStrategy.SNAKE_CASE))
                .isInstanceOf(MappingConfigurationException.class)
                .hasMessageContaining("same");
    }

    @ExcelSheet(name = "S")
    static class BothHeaderAndIndex {
        @ExcelColumn(header = "A", index = 0)
        String a;
    }

    @Test
    void columnWithTwoIdentificationModesIsRejected() {
        assertThatThrownBy(() ->
                        MappingModelFactory.create(BothHeaderAndIndex.class, NamingStrategy.SNAKE_CASE))
                .isInstanceOf(MappingConfigurationException.class)
                .hasMessageContaining("ровно один");
    }

    @ExcelSheet(name = "S", index = 1)
    static class SheetNameAndIndex {
        @ExcelColumn(header = "A")
        String a;
    }

    @Test
    void sheetWithBothNameAndIndexIsRejected() {
        assertThatThrownBy(() ->
                        MappingModelFactory.create(SheetNameAndIndex.class, NamingStrategy.SNAKE_CASE))
                .isInstanceOf(MappingConfigurationException.class)
                .hasMessageContaining("ровно один");
    }

    @ExcelSheet(name = "S")
    @TargetTable(name = "employee")
    static class LookupKeyOnly {
        @ExcelColumn(header = "ФИО")
        @Column("full_name")
        String fullName;

        @ExcelColumn(header = "Email руководителя", insertable = false)
        String managerEmail; // ключ поиска: есть в файле, но не в таблице-приёмнике

        @Column("manager_id")
        String managerId; // сюда BatchValidator кладёт разрешённый ключ
    }

    @Test
    void nonInsertableColumnIsReadButKeptOutOfTheInsert() {
        MappingModel<LookupKeyOnly> model =
                MappingModelFactory.create(LookupKeyOnly.class, NamingStrategy.SNAKE_CASE);

        assertThat(model.excelColumns())
                .extracting(ColumnBinding::fieldName)
                .containsExactly("fullName", "managerEmail");
        assertThat(model.allDbColumns()).containsExactly("full_name", "manager_id");
        assertThat(model.allBindings())
                .extracting(ColumnBinding::fieldName)
                .containsExactly("fullName", "managerId");
    }

    @ExcelSheet(name = "S")
    static class NonInsertableWithColumn {
        @ExcelColumn(header = "A", insertable = false)
        @Column("a")
        String a;
    }

    @Test
    void nonInsertableColumnWithColumnAnnotationIsRejected() {
        assertThatThrownBy(() ->
                        MappingModelFactory.create(NonInsertableWithColumn.class, NamingStrategy.SNAKE_CASE))
                .isInstanceOf(MappingConfigurationException.class)
                .hasMessageContaining("insertable = false");
    }

    @ExcelSheet(name = "S")
    static class AllNonInsertable {
        @ExcelColumn(header = "A", insertable = false)
        String a;
    }

    @Test
    void classWithoutAnyInsertableColumnIsRejected() {
        assertThatThrownBy(() ->
                        MappingModelFactory.create(AllNonInsertable.class, NamingStrategy.SNAKE_CASE))
                .isInstanceOf(MappingConfigurationException.class)
                .hasMessageContaining("вставляемой колонки");
    }

    @ExcelSheet(name = "S")
    static class NoExcelColumns {
        @Column("x")
        String x;
    }

    @Test
    void classWithoutExcelColumnsIsRejected() {
        assertThatThrownBy(() ->
                        MappingModelFactory.create(NoExcelColumns.class, NamingStrategy.SNAKE_CASE))
                .isInstanceOf(MappingConfigurationException.class)
                .hasMessageContaining("ни одного @ExcelColumn");
    }
}
