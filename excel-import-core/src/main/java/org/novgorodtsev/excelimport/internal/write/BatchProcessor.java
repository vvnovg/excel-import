package org.novgorodtsev.excelimport.internal.write;

import org.novgorodtsev.excelimport.RowError;
import org.novgorodtsev.excelimport.RowRef;
import org.novgorodtsev.excelimport.validate.BatchValidator;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Savepoint;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Обрабатывает один батч: берёт соединение, открывает транзакцию, прогоняет
 * пользовательские валидаторы, вставляет остаток, коммитит. При ошибке БД делегирует
 * поиск сбойных строк {@link BatchSplitter}.
 */
public final class BatchProcessor<T> {

    private static final Logger log = LoggerFactory.getLogger(BatchProcessor.class);

    /** Итог обработки батча. */
    public record BatchOutcome(int insertedCount, List<RowError> errors, int statementCount) {

        public BatchOutcome {
            errors = List.copyOf(errors);
        }
    }

    private final DataSource dataSource;
    private final InsertExecutor<T> executor;
    private final BatchSplitter<T> splitter;
    private final List<BatchValidator<T>> validators;
    private final boolean dryRun;
    private final int chunkSize;

    public BatchProcessor(
            DataSource dataSource,
            InsertExecutor<T> executor,
            BatchSplitter<T> splitter,
            List<BatchValidator<T>> validators,
            boolean dryRun,
            int requestedBatchSize) {
        this.dataSource = dataSource;
        this.executor = executor;
        this.splitter = splitter;
        this.validators = List.copyOf(validators);
        this.dryRun = dryRun;
        this.chunkSize = executor.sqlBuilder().chunkSize(requestedBatchSize);
        if (chunkSize < requestedBatchSize) {
            log.warn(
                    "batchSize {} превышает предел {} bind-параметров при {} колонках; "
                            + "батч будет дробиться на чанки по {} строк внутри одной транзакции",
                    requestedBatchSize,
                    SqlBuilder.MAX_BIND_PARAMETERS,
                    executor.sqlBuilder().columnCount(),
                    chunkSize);
        }
    }

    /**
     * @throws SQLException при фатальной ошибке БД — импорт должен прерваться
     */
    public BatchOutcome process(List<RowRef<T>> batch) throws SQLException {
        if (batch.isEmpty()) {
            return new BatchOutcome(0, List.of(), 0);
        }
        try (Connection connection = dataSource.getConnection()) {
            boolean originalAutoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try {
                List<RowError> validationErrors = new ArrayList<>();
                List<RowRef<T>> accepted = runValidators(batch, connection, validationErrors);

                if (dryRun) {
                    connection.rollback();
                    return new BatchOutcome(accepted.size(), validationErrors, 0);
                }

                BatchSplitter.SplitResult result = splitter.insertWithBisection(
                        accepted, chunk -> attemptWithSavepoint(connection, chunk));
                connection.commit();

                List<RowError> allErrors = new ArrayList<>(validationErrors);
                allErrors.addAll(result.errors());
                return new BatchOutcome(result.insertedCount(), allErrors, result.statementCount());
            } catch (SQLException | RuntimeException e) {
                safeRollback(connection);
                throw e;
            } finally {
                restoreAutoCommit(connection, originalAutoCommit);
            }
        }
    }

    private List<RowRef<T>> runValidators(
            List<RowRef<T>> batch, Connection connection, List<RowError> collectedErrors)
            throws SQLException {
        if (validators.isEmpty()) {
            return batch;
        }
        Connection guarded = GuardedConnection.wrap(connection);
        Set<Integer> rejectedRows = new HashSet<>();
        for (BatchValidator<T> validator : validators) {
            List<RowError> errors = validator.validate(batch, guarded);
            if (errors == null || errors.isEmpty()) {
                continue;
            }
            collectedErrors.addAll(errors);
            errors.forEach(error -> rejectedRows.add(error.rowNum()));
        }
        if (rejectedRows.isEmpty()) {
            return batch;
        }
        List<RowRef<T>> accepted = new ArrayList<>(batch.size() - rejectedRows.size());
        for (RowRef<T> row : batch) {
            if (!rejectedRows.contains(row.rowNum())) {
                accepted.add(row);
            }
        }
        return accepted;
    }

    /**
     * Каждая попытка бисекции работает как своя мини-транзакция (контракт
     * {@link BatchSplitter.ChunkAttempt}): после сбойного оператора PostgreSQL помечает
     * всю транзакцию как aborted, поэтому перед попыткой ставится savepoint, а при
     * ошибке делается откат к нему — иначе все последующие попытки упали бы той же
     * ошибкой, и изоляция сбойных строк не сработала бы.
     */
    private int attemptWithSavepoint(Connection connection, List<RowRef<T>> chunk)
            throws SQLException {
        Savepoint savepoint = connection.setSavepoint();
        try {
            return insertInChunks(connection, chunk);
        } catch (SQLException | RuntimeException e) {
            connection.rollback(savepoint);
            throw e;
        }
    }

    /**
     * Дробит чанк, если он не влезает в предел bind-параметров. Все под-чанки идут
     * в той же транзакции, поэтому для вызывающей стороны это одна попытка.
     */
    private int insertInChunks(Connection connection, List<RowRef<T>> rows) throws SQLException {
        if (rows.size() <= chunkSize) {
            return executor.execute(connection, rows);
        }
        int inserted = 0;
        for (int start = 0; start < rows.size(); start += chunkSize) {
            int end = Math.min(start + chunkSize, rows.size());
            inserted += executor.execute(connection, rows.subList(start, end));
        }
        return inserted;
    }

    private static void safeRollback(Connection connection) {
        try {
            connection.rollback();
        } catch (SQLException e) {
            log.warn("не удалось откатить транзакцию батча: {}", e.getMessage());
        }
    }

    private static void restoreAutoCommit(Connection connection, boolean original) {
        try {
            connection.setAutoCommit(original);
        } catch (SQLException e) {
            log.debug("не удалось вернуть autoCommit: {}", e.getMessage());
        }
    }
}
