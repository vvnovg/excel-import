package org.novgorodtsev.excelimport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;

class ConflictStrategyTest {

    @Test
    void noneProducesEmptySql() {
        assertThat(ConflictStrategy.none().toSql()).isEmpty();
    }

    @Test
    void doNothingQuotesConflictColumns() {
        assertThat(ConflictStrategy.doNothing("personnel_no").toSql())
                .isEqualTo("ON CONFLICT (\"personnel_no\") DO NOTHING");
    }

    @Test
    void doNothingSupportsCompositeKey() {
        assertThat(ConflictStrategy.doNothing("a", "b").toSql())
                .isEqualTo("ON CONFLICT (\"a\", \"b\") DO NOTHING");
    }

    @Test
    void doUpdateGeneratesExcludedAssignments() {
        assertThat(ConflictStrategy.doUpdate(List.of("personnel_no"), List.of("full_name", "salary"))
                        .toSql())
                .isEqualTo("ON CONFLICT (\"personnel_no\") DO UPDATE SET "
                        + "\"full_name\" = EXCLUDED.\"full_name\", \"salary\" = EXCLUDED.\"salary\"");
    }

    @Test
    void doNothingRequiresAtLeastOneColumn() {
        assertThatThrownBy(ConflictStrategy::doNothing)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("хотя бы одна колонка");
    }

    @Test
    void doUpdateRequiresNonEmptyUpdateColumns() {
        assertThatThrownBy(() -> ConflictStrategy.doUpdate(List.of("a"), List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("обновляемых колонок");
    }
}
