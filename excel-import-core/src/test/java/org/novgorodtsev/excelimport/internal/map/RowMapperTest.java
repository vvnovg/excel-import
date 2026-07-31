package org.novgorodtsev.excelimport.internal.map;

import static org.assertj.core.api.Assertions.assertThat;

import org.novgorodtsev.excelimport.ErrorKind;
import org.novgorodtsev.excelimport.HeaderMatchingPolicy;
import org.novgorodtsev.excelimport.NamingStrategy;
import org.novgorodtsev.excelimport.annotation.ExcelColumn;
import org.novgorodtsev.excelimport.annotation.ExcelSheet;
import org.novgorodtsev.excelimport.convert.BooleanWords;
import org.novgorodtsev.excelimport.internal.convert.ConverterRegistry;
import org.novgorodtsev.excelimport.internal.read.RawRow;
import org.novgorodtsev.excelimport.testsupport.RawRows;
import java.math.BigDecimal;
import java.util.Locale;
import org.junit.jupiter.api.Test;

class RowMapperTest {

    @ExcelSheet(name = "S")
    static class Employee {
        @ExcelColumn(header = "ФИО")
        String fullName;

        @ExcelColumn(header = "Оклад")
        BigDecimal salary;

        @ExcelColumn(header = "Стаж")
        Integer years;
    }

    private final MappingModel<Employee> model =
            MappingModelFactory.create(Employee.class, NamingStrategy.SNAKE_CASE);

    private RowMapper<Employee> mapper() {
        RawRow header = RawRows.of(0, "ФИО", "Оклад", "Стаж");
        ResolvedColumns resolved = HeaderResolver.resolve(model, header, HeaderMatchingPolicy.defaults());
        return new RowMapper<>(
                model, resolved, new ConverterRegistry(), Locale.forLanguageTag("ru"), BooleanWords.defaults());
    }

    @Test
    void mapsAllFields() {
        MappingResult<Employee> result = mapper().map(RawRows.of(4, "Иванов", "1234,50", "7"));

        assertThat(result.isValid()).isTrue();
        assertThat(result.value().fullName).isEqualTo("Иванов");
        assertThat(result.value().salary).isEqualByComparingTo("1234.50");
        assertThat(result.value().years).isEqualTo(7);
    }

    @Test
    void missingCellsLeaveFieldsNull() {
        MappingResult<Employee> result = mapper().map(RawRows.of(4, "Иванов", null, null));

        assertThat(result.isValid()).isTrue();
        assertThat(result.value().salary).isNull();
        assertThat(result.value().years).isNull();
    }

    @Test
    void collectsAllConversionErrorsNotJustTheFirst() {
        MappingResult<Employee> result = mapper().map(RawRows.of(4, "Иванов", "abc", "xyz"));

        assertThat(result.isValid()).isFalse();
        assertThat(result.errors()).hasSize(2);
        assertThat(result.errors()).allSatisfy(error -> {
            assertThat(error.kind()).isEqualTo(ErrorKind.CONVERSION);
            assertThat(error.rowNum()).isEqualTo(5); // 1-based
        });
        assertThat(result.errors()).extracting("columnHeader").containsExactly("Оклад", "Стаж");
    }

    @Test
    void errorCarriesRawCellText() {
        MappingResult<Employee> result = mapper().map(RawRows.of(4, "Иванов", "abc", "1"));

        assertThat(result.errors()).singleElement()
                .satisfies(error -> assertThat(error.rawValue()).isEqualTo("abc"));
    }

    @Test
    void valueIsStillReturnedWhenSomeColumnsFailed() {
        // объект нужен отчёту и потенциальному BatchValidator, но строка считается невалидной
        MappingResult<Employee> result = mapper().map(RawRows.of(4, "Иванов", "abc", "7"));

        assertThat(result.value().fullName).isEqualTo("Иванов");
        assertThat(result.value().years).isEqualTo(7);
        assertThat(result.isValid()).isFalse();
    }

    @Test
    void mapperIsReusableAcrossRows() {
        RowMapper<Employee> mapper = mapper();

        Employee first = mapper.map(RawRows.of(1, "А", "1", "1")).value();
        Employee second = mapper.map(RawRows.of(2, "Б", "2", "2")).value();

        assertThat(first).isNotSameAs(second);
        assertThat(first.fullName).isEqualTo("А");
        assertThat(second.fullName).isEqualTo("Б");
    }
}
