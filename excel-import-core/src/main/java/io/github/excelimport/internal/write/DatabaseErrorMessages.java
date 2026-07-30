package io.github.excelimport.internal.write;

import java.sql.SQLException;

/**
 * Человекочитаемое описание ошибки БД для отчёта. Достаёт constraint и detail из
 * {@code PSQLException} через рефлексию, чтобы ядро не зависело от pgjdbc в compile-time.
 */
public final class DatabaseErrorMessages {

    private DatabaseErrorMessages() {}

    /**
     * @param includeDetail включать ли поле {@code detail} — оно может содержать значения строки
     */
    public static String describe(SQLException exception, boolean includeDetail) {
        SQLException root = rootSqlException(exception);
        StringBuilder message = new StringBuilder();
        String constraint = serverField(root, "getConstraint");
        if (constraint != null) {
            message.append("нарушено ограничение ").append(constraint).append(": ");
        }
        String primary = serverField(root, "getMessage");
        message.append(primary != null ? primary : root.getMessage());
        if (includeDetail) {
            String detail = serverField(root, "getDetail");
            if (detail != null && !detail.isBlank()) {
                message.append(" (").append(detail).append(')');
            }
        }
        String state = root.getSQLState();
        if (state != null) {
            message.append(" [SQLState ").append(state).append(']');
        }
        return message.toString();
    }

    /** Код ошибки для {@code RowError.code()}. */
    public static String codeOf(SQLException exception) {
        String state = rootSqlException(exception).getSQLState();
        return state != null ? state : "SQL_ERROR";
    }

    private static SQLException rootSqlException(SQLException exception) {
        SQLException result = exception;
        for (Throwable current = exception.getCause(); current != null; current = current.getCause()) {
            if (current instanceof SQLException sqlException && sqlException.getSQLState() != null) {
                result = sqlException;
                break;
            }
        }
        if (result.getSQLState() == null && exception.getNextException() != null) {
            return exception.getNextException();
        }
        return result;
    }

    private static String serverField(SQLException exception, String accessor) {
        try {
            Object serverErrorMessage = exception.getClass()
                    .getMethod("getServerErrorMessage")
                    .invoke(exception);
            if (serverErrorMessage == null) {
                return null;
            }
            Object value = serverErrorMessage.getClass().getMethod(accessor).invoke(serverErrorMessage);
            return value == null ? null : value.toString();
        } catch (ReflectiveOperationException | RuntimeException e) {
            return null; // не pgjdbc или другая версия — довольствуемся getMessage()
        }
    }
}
