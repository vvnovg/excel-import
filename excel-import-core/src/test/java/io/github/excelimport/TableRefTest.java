package io.github.excelimport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class TableRefTest {

    @Test
    void parsesSchemaQualifiedName() {
        TableRef ref = TableRef.of("hr.employee");

        assertThat(ref.schema()).isEqualTo("hr");
        assertThat(ref.name()).isEqualTo("employee");
        assertThat(ref.qualifiedName()).isEqualTo("\"hr\".\"employee\"");
    }

    @Test
    void bareNameHasNoSchema() {
        TableRef ref = TableRef.of("employee");

        assertThat(ref.schema()).isNull();
        assertThat(ref.qualifiedName()).isEqualTo("\"employee\"");
    }

    @Test
    void rejectsIdentifierWithQuote() {
        assertThatThrownBy(() -> TableRef.of("emp\"loyee"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("недопустимый идентификатор");
    }

    @Test
    void rejectsThreePartName() {
        assertThatThrownBy(() -> TableRef.of("db.hr.employee"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("схема.таблица");
    }
}
