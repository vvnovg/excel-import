package org.novgorodtsev.excelimport.internal.write;

import org.novgorodtsev.excelimport.RowError;
import org.novgorodtsev.excelimport.RowRef;
import org.novgorodtsev.excelimport.SqlErrorClassifier;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Изолирует сбойные строки рекурсивным делением батча пополам. Каждая попытка —
 * отдельный вызов {@link ChunkAttempt}, который вызывающая сторона выполняет в своей
 * транзакции.
 */
public final class BatchSplitter<T> {

    private static final Logger log = LoggerFactory.getLogger(BatchSplitter.class);

    /** Одна попытка вставки чанка. Реализация отвечает за транзакцию. */
    public interface ChunkAttempt<T> {
        int attempt(List<RowRef<T>> chunk) throws SQLException;
    }

    /**
     * @param insertedCount  сколько строк реально вставлено
     * @param errors         ошибки по строкам, отсортированные по номеру строки
     * @param statementCount сколько запросов потребовалось — для наблюдаемости
     */
    public record SplitResult(int insertedCount, List<RowError> errors, int statementCount) {

        public SplitResult {
            errors = List.copyOf(errors);
        }
    }

    private final int maxSplitDepth;
    private final SqlErrorClassifier classifier;
    private final boolean includeDatabaseDetail;

    public BatchSplitter(
            int maxSplitDepth, SqlErrorClassifier classifier, boolean includeDatabaseDetail) {
        this.maxSplitDepth = maxSplitDepth;
        this.classifier = classifier;
        this.includeDatabaseDetail = includeDatabaseDetail;
    }

    /**
     * @throws SQLException если ошибка признана фатальной — импорт должен прерваться
     */
    public SplitResult insertWithBisection(List<RowRef<T>> batch, ChunkAttempt<T> attempt)
            throws SQLException {
        State state = new State();
        insert(batch, attempt, 0, state);
        state.errors.sort(Comparator.comparingInt(RowError::rowNum));
        return new SplitResult(state.inserted, state.errors, state.statements);
    }

    private void insert(List<RowRef<T>> chunk, ChunkAttempt<T> attempt, int depth, State state)
            throws SQLException {
        if (chunk.isEmpty()) {
            return;
        }
        try {
            state.statements++;
            state.inserted += attempt.attempt(chunk);
        } catch (SQLException e) {
            if (classifier.isFatal(e)) {
                throw e;
            }
            if (chunk.size() == 1) {
                RowRef<T> row = chunk.get(0);
                state.errors.add(RowError.database(
                        row.rowNum(),
                        DatabaseErrorMessages.codeOf(e),
                        DatabaseErrorMessages.describe(e, includeDatabaseDetail)));
                return;
            }
            if (depth >= maxSplitDepth) {
                String reason = "не удалось изолировать сбойную строку: достигнут предел деления батча ("
                        + maxSplitDepth + "); ошибка батча: "
                        + DatabaseErrorMessages.describe(e, includeDatabaseDetail);
                String code = DatabaseErrorMessages.codeOf(e);
                for (RowRef<T> row : chunk) {
                    state.errors.add(RowError.database(row.rowNum(), code, reason));
                }
                return;
            }
            log.warn(
                    "батч из {} строк отклонён ({}), делю пополам, глубина {}",
                    chunk.size(),
                    DatabaseErrorMessages.codeOf(e),
                    depth);
            int middle = chunk.size() / 2;
            insert(chunk.subList(0, middle), attempt, depth + 1, state);
            insert(chunk.subList(middle, chunk.size()), attempt, depth + 1, state);
        }
    }

    /** Изменяемое состояние обхода: держим отдельно, чтобы рекурсия оставалась читаемой. */
    private static final class State {
        private int inserted;
        private int statements;
        private final List<RowError> errors = new ArrayList<>();
    }
}
