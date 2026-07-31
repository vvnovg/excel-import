package org.novgorodtsev.excelimport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class RowErrorTest {

    @Test
    void conversionErrorKeepsColumnAndRawValue() {
        RowError error = RowError.conversion(7, "Оклад", "не число", "NUMBER_FORMAT", "Не число");

        assertThat(error.rowNum()).isEqualTo(7);
        assertThat(error.columnHeader()).isEqualTo("Оклад");
        assertThat(error.rawValue()).isEqualTo("не число");
        assertThat(error.kind()).isEqualTo(ErrorKind.CONVERSION);
    }

    @Test
    void batchErrorHasNoColumn() {
        RowError error = RowError.batch(12, "DUPLICATE_IN_FILE", "Дубликат строки 5");

        assertThat(error.columnHeader()).isNull();
        assertThat(error.rawValue()).isNull();
        assertThat(error.kind()).isEqualTo(ErrorKind.BATCH);
    }

    @Test
    void rejectsNonPositiveRowNum() {
        assertThatThrownBy(() -> RowError.batch(0, "X", "msg"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("1-based");
    }

    @Test
    void rejectsBlankMessage() {
        assertThatThrownBy(() -> RowError.batch(1, "X", " "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("сообщение");
    }
}
