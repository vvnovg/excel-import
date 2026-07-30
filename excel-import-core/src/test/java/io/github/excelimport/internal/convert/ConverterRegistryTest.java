package io.github.excelimport.internal.convert;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.excelimport.NamingStrategy;
import io.github.excelimport.annotation.ExcelColumn;
import io.github.excelimport.annotation.ExcelSheet;
import io.github.excelimport.convert.CellConverter;
import io.github.excelimport.convert.CellValue;
import io.github.excelimport.convert.ConversionContext;
import io.github.excelimport.exception.MappingConfigurationException;
import io.github.excelimport.internal.map.ColumnBinding;
import io.github.excelimport.internal.map.MappingModel;
import io.github.excelimport.internal.map.MappingModelFactory;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

class ConverterRegistryTest {

    static class InnConverter implements CellConverter<String> {
        @Override
        public String convert(CellValue value, ConversionContext ctx) {
            String raw = ctx.text(value);
            return raw == null ? null : raw.replaceAll("\\D", "");
        }
    }

    @ExcelSheet(name = "S")
    static class Row {
        @ExcelColumn(header = "Дата")
        LocalDate date;

        @ExcelColumn(header = "ИНН", converter = InnConverter.class)
        String inn;

        @ExcelColumn(header = "Что-то")
        Thread unsupported;
    }

    private ColumnBinding binding(String fieldName) {
        MappingModel<Row> model = MappingModelFactory.create(Row.class, NamingStrategy.SNAKE_CASE);
        return model.excelColumns().stream()
                .filter(b -> fieldName.equals(b.fieldName()))
                .findFirst()
                .orElseThrow();
    }

    @Test
    void resolvesBuiltinConverterByFieldType() {
        assertThat(new ConverterRegistry().resolve(binding("date"))).isNotNull();
    }

    @Test
    void resolvesExplicitCustomConverter() {
        CellConverter<?> converter = new ConverterRegistry().resolve(binding("inn"));

        assertThat(converter).isInstanceOf(InnConverter.class);
    }

    @Test
    void customConverterInstanceIsCachedPerClass() {
        ConverterRegistry registry = new ConverterRegistry();

        assertThat(registry.resolve(binding("inn"))).isSameAs(registry.resolve(binding("inn")));
    }

    @Test
    void unsupportedFieldTypeIsRejectedAtBuildTime() {
        assertThatThrownBy(() -> new ConverterRegistry().resolve(binding("unsupported")))
                .isInstanceOf(MappingConfigurationException.class)
                .hasMessageContaining("Thread");
    }
}
