package org.novgorodtsev.excelimport.internal.map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.novgorodtsev.excelimport.HeaderMatchingPolicy;
import org.novgorodtsev.excelimport.NamingStrategy;
import org.novgorodtsev.excelimport.annotation.ExcelColumn;
import org.novgorodtsev.excelimport.annotation.ExcelSheet;
import org.novgorodtsev.excelimport.exception.FileStructureException;
import org.novgorodtsev.excelimport.internal.read.RawRow;
import org.novgorodtsev.excelimport.testsupport.RawRows;
import org.junit.jupiter.api.Test;

class HeaderResolverTest {

    @ExcelSheet(name = "S")
    static class Model {
        @ExcelColumn(header = "ФИО")
        String fullName;

        @ExcelColumn(header = "Оклад")
        Long salary;

        @ExcelColumn(header = "Отдел", required = false)
        String department;

        @ExcelColumn(index = 9)
        String byIndex;
    }

    private final MappingModel<Model> model =
            MappingModelFactory.create(Model.class, NamingStrategy.SNAKE_CASE);

    @Test
    void matchesHeadersByTextIgnoringCaseAndExtraSpaces() {
        RawRow header = RawRows.of(0, "  фио ", "лишняя", "ОКЛАД");

        ResolvedColumns resolved = HeaderResolver.resolve(model, header, HeaderMatchingPolicy.defaults());

        assertThat(resolved.indexOf("fullName")).hasValue(0);
        assertThat(resolved.indexOf("salary")).hasValue(2);
    }

    @Test
    void indexBoundColumnKeepsItsDeclaredIndex() {
        RawRow header = RawRows.of(0, "ФИО", "Оклад");

        ResolvedColumns resolved = HeaderResolver.resolve(model, header, HeaderMatchingPolicy.defaults());

        assertThat(resolved.indexOf("byIndex")).hasValue(9);
    }

    @Test
    void optionalColumnMayBeAbsent() {
        RawRow header = RawRows.of(0, "ФИО", "Оклад");

        ResolvedColumns resolved = HeaderResolver.resolve(model, header, HeaderMatchingPolicy.defaults());

        assertThat(resolved.indexOf("department")).isEmpty();
        assertThat(resolved.entries()).hasSize(3); // fullName, salary, byIndex
    }

    @Test
    void missingRequiredColumnFails() {
        RawRow header = RawRows.of(0, "ФИО");

        assertThatThrownBy(() -> HeaderResolver.resolve(model, header, HeaderMatchingPolicy.defaults()))
                .isInstanceOf(FileStructureException.class)
                .hasMessageContaining("Оклад");
    }

    @Test
    void duplicateHeaderFails() {
        RawRow header = RawRows.of(0, "ФИО", "Оклад", "оклад");

        assertThatThrownBy(() -> HeaderResolver.resolve(model, header, HeaderMatchingPolicy.defaults()))
                .isInstanceOf(FileStructureException.class)
                .hasMessageContaining("неоднозначн");
    }

    @Test
    void nonBreakingSpaceInFileHeaderIsNormalized() {
        RawRow header = RawRows.of(0, "Ф\u00A0И\u00A0О", "Оклад"); // \u00A0 = неразрывный пробел (NBSP), не обычный

        // после нормализации неразрывный пробел становится обычным, затем схлопывается
        assertThatThrownBy(() -> HeaderResolver.resolve(model, header, HeaderMatchingPolicy.defaults()))
                .isInstanceOf(FileStructureException.class)
                .hasMessageContaining("ФИО");
    }

    @Test
    void caseSensitiveModeDistinguishesHeaders() {
        RawRow header = RawRows.of(0, "фио", "Оклад");
        HeaderMatchingPolicy strict = new HeaderMatchingPolicy(true, true, false, true);

        assertThatThrownBy(() -> HeaderResolver.resolve(model, header, strict))
                .isInstanceOf(FileStructureException.class)
                .hasMessageContaining("ФИО");
    }
}
