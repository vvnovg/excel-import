package org.novgorodtsev.excelimport.internal.write;

import org.novgorodtsev.excelimport.RowRef;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.List;

/** Выполняет один multi-row INSERT в переданном соединении. Транзакцией не управляет. */
public final class InsertExecutor<T> {

    private final SqlBuilder sqlBuilder;
    private final RowBinder<T> binder;
    private final int queryTimeoutSeconds;

    public InsertExecutor(SqlBuilder sqlBuilder, RowBinder<T> binder, int queryTimeoutSeconds) {
        this.sqlBuilder = sqlBuilder;
        this.binder = binder;
        this.queryTimeoutSeconds = queryTimeoutSeconds;
    }

    /**
     * @return число вставленных строк (может быть меньше размера чанка при ON CONFLICT DO NOTHING)
     */
    public int execute(Connection connection, List<RowRef<T>> chunk) throws SQLException {
        String sql = sqlBuilder.insertSql(chunk.size());
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            if (queryTimeoutSeconds > 0) {
                statement.setQueryTimeout(queryTimeoutSeconds);
            }
            int parameterIndex = 1;
            for (RowRef<T> row : chunk) {
                binder.bind(statement, parameterIndex, row.value());
                parameterIndex += binder.columnCount();
            }
            return statement.executeUpdate();
        }
    }

    public SqlBuilder sqlBuilder() {
        return sqlBuilder;
    }
}
