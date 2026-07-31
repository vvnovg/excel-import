package org.novgorodtsev.excelimport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class SheetSelectorTest {

    @Test
    void byNameCarriesName() {
        SheetSelector selector = SheetSelector.byName("Сотрудники");

        assertThat(selector.name()).contains("Сотрудники");
        assertThat(selector.index()).isEmpty();
    }

    @Test
    void byIndexCarriesIndex() {
        SheetSelector selector = SheetSelector.byIndex(2);

        assertThat(selector.index()).hasValue(2);
        assertThat(selector.name()).isEmpty();
    }

    @Test
    void firstIsIndexZero() {
        assertThat(SheetSelector.first().index()).hasValue(0);
    }

    @Test
    void rejectsBlankName() {
        assertThatThrownBy(() -> SheetSelector.byName("  "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("имя листа");
    }

    @Test
    void rejectsNegativeIndex() {
        assertThatThrownBy(() -> SheetSelector.byIndex(-1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("индекс листа");
    }
}
