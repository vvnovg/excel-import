package io.github.excelimport.internal.write;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.excelimport.ErrorKind;
import io.github.excelimport.RowRef;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class BatchSplitterTest {

    private static List<RowRef<String>> batch(int size) {
        List<RowRef<String>> rows = new ArrayList<>(size);
        for (int i = 1; i <= size; i++) {
            rows.add(new RowRef<>(i, "row" + i));
        }
        return rows;
    }

    /** Имитация БД: перечисленные номера строк всегда валят запрос. */
    private static BatchSplitter.ChunkAttempt<String> failingRows(
            Set<Integer> badRowNums, List<Integer> statementSizes) {
        return chunk -> {
            statementSizes.add(chunk.size());
            for (RowRef<String> row : chunk) {
                if (badRowNums.contains(row.rowNum())) {
                    throw new SQLException("duplicate key value violates unique constraint \"uq_x\"", "23505");
                }
            }
            return chunk.size();
        };
    }

    private BatchSplitter<String> splitter(int maxDepth) {
        return new BatchSplitter<>(maxDepth, new DefaultSqlErrorClassifier(), true);
    }

    @Test
    void cleanBatchInsertsInOneStatement() throws SQLException {
        List<Integer> statements = new ArrayList<>();

        BatchSplitter.SplitResult result =
                splitter(16).insertWithBisection(batch(8), failingRows(Set.of(), statements));

        assertThat(result.insertedCount()).isEqualTo(8);
        assertThat(result.errors()).isEmpty();
        assertThat(statements).containsExactly(8);
    }

    @Test
    void isolatesSingleBadRowInTheMiddle() throws SQLException {
        List<Integer> statements = new ArrayList<>();

        BatchSplitter.SplitResult result =
                splitter(16).insertWithBisection(batch(8), failingRows(Set.of(5), statements));

        assertThat(result.insertedCount()).isEqualTo(7);
        assertThat(result.errors()).singleElement().satisfies(error -> {
            assertThat(error.rowNum()).isEqualTo(5);
            assertThat(error.kind()).isEqualTo(ErrorKind.DATABASE);
            assertThat(error.code()).isEqualTo("23505");
            assertThat(error.message()).contains("uq_x");
        });
    }

    @Test
    void isolatesFirstAndLastRow() throws SQLException {
        BatchSplitter.SplitResult result = splitter(16)
                .insertWithBisection(batch(8), failingRows(Set.of(1, 8), new ArrayList<>()));

        assertThat(result.insertedCount()).isEqualTo(6);
        assertThat(result.errors()).extracting("rowNum").containsExactlyInAnyOrder(1, 8);
    }

    @Test
    void allRowsBadProducesErrorPerRow() throws SQLException {
        BatchSplitter.SplitResult result = splitter(16)
                .insertWithBisection(batch(4), failingRows(Set.of(1, 2, 3, 4), new ArrayList<>()));

        assertThat(result.insertedCount()).isZero();
        assertThat(result.errors()).hasSize(4);
    }

    @Test
    void singleRowBatchFailsWithoutSplitting() throws SQLException {
        List<Integer> statements = new ArrayList<>();

        BatchSplitter.SplitResult result =
                splitter(16).insertWithBisection(batch(1), failingRows(Set.of(1), statements));

        assertThat(result.errors()).hasSize(1);
        assertThat(statements).containsExactly(1);
    }

    @Test
    void depthLimitMarksWholeSubBatchWithSharedReason() throws SQLException {
        // maxDepth = 0 запрещает деление вообще
        BatchSplitter.SplitResult result =
                splitter(0).insertWithBisection(batch(4), failingRows(Set.of(3), new ArrayList<>()));

        assertThat(result.insertedCount()).isZero();
        assertThat(result.errors()).hasSize(4);
        assertThat(result.errors()).allSatisfy(error ->
                assertThat(error.message()).contains("не удалось изолировать"));
    }

    @Test
    void fatalErrorPropagatesInsteadOfSplitting() {
        BatchSplitter.ChunkAttempt<String> connectionLost = chunk -> {
            throw new SQLException("connection lost", "08006");
        };

        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                        splitter(16).insertWithBisection(batch(8), connectionLost))
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("connection lost");
    }

    @Test
    void statementCountIsReportedForObservability() throws SQLException {
        BatchSplitter.SplitResult result = splitter(16)
                .insertWithBisection(batch(8), failingRows(Set.of(5), new ArrayList<>()));

        // 1 неудачный на 8 + деления: 4+4, 4 ок, 4 → 2+2, 2 ок, 2 → 1+1
        assertThat(result.statementCount()).isGreaterThan(1);
    }

    @Test
    void errorsAreOrderedByRowNum() throws SQLException {
        BatchSplitter.SplitResult result = splitter(16)
                .insertWithBisection(batch(16), failingRows(Set.of(2, 9, 14), new ArrayList<>()));

        assertThat(result.errors()).extracting("rowNum").containsExactly(2, 9, 14);
    }
}
