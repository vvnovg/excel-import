package io.github.excelimport.internal.write;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.excelimport.ConflictStrategy;
import io.github.excelimport.TableRef;
import java.util.List;
import org.junit.jupiter.api.Test;

class SqlBuilderTest {

    private static final List<String> COLUMNS = List.of("personnel_no", "full_name", "salary");

    private SqlBuilder builder(ConflictStrategy conflict) {
        return new SqlBuilder(TableRef.of("hr.employee"), COLUMNS, conflict);
    }

    @Test
    void singleRowInsertHasOneTuple() {
        assertThat(builder(ConflictStrategy.none()).insertSql(1))
                .isEqualTo("INSERT INTO \"hr\".\"employee\" "
                        + "(\"personnel_no\", \"full_name\", \"salary\") VALUES (?, ?, ?)");
    }

    @Test
    void multiRowInsertRepeatsTuples() {
        assertThat(builder(ConflictStrategy.none()).insertSql(3))
                .isEqualTo("INSERT INTO \"hr\".\"employee\" "
                        + "(\"personnel_no\", \"full_name\", \"salary\") "
                        + "VALUES (?, ?, ?), (?, ?, ?), (?, ?, ?)");
    }

    @Test
    void conflictClauseIsAppended() {
        assertThat(builder(ConflictStrategy.doNothing("personnel_no")).insertSql(1))
                .endsWith("VALUES (?, ?, ?) ON CONFLICT (\"personnel_no\") DO NOTHING");
    }

    @Test
    void maxRowsPerStatementRespectsParameterLimit() {
        // 65535 / 3 = 21845
        assertThat(builder(ConflictStrategy.none()).maxRowsPerStatement()).isEqualTo(21845);
    }

    @Test
    void chunkSizeIsCappedByParameterLimit() {
        SqlBuilder sql = builder(ConflictStrategy.none());

        assertThat(sql.chunkSize(1000)).isEqualTo(1000);
        assertThat(sql.chunkSize(100_000)).isEqualTo(21845);
    }

    @Test
    void wideTableAllowsFewerRowsPerStatement() {
        List<String> wide = new java.util.ArrayList<>();
        for (int i = 0; i < 700; i++) {
            wide.add("c" + i);
        }
        SqlBuilder sql = new SqlBuilder(TableRef.of("t"), wide, ConflictStrategy.none());

        assertThat(sql.maxRowsPerStatement()).isEqualTo(93); // 65535 / 700
        assertThat(sql.chunkSize(1000)).isEqualTo(93);
    }

    @Test
    void sqlIsCachedPerRowCount() {
        SqlBuilder sql = builder(ConflictStrategy.none());

        assertThat(sql.insertSql(5)).isSameAs(sql.insertSql(5));
    }

    @Test
    void rejectsZeroRowCount() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> builder(ConflictStrategy.none()).insertSql(0))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
