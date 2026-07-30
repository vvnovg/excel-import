package io.github.excelimport.internal.write;

import io.github.excelimport.SqlErrorClassifier;
import java.sql.SQLException;
import java.util.Set;

/**
 * Классификация по {@code SQLState} PostgreSQL. Консервативна: неизвестное состояние
 * считается фатальным, чтобы не делить батч вслепую при системной проблеме.
 */
public final class DefaultSqlErrorClassifier implements SqlErrorClassifier {

    /** Классы состояний, при которых деление батча бессмысленно. */
    private static final Set<String> FATAL_CLASSES = Set.of(
            "08", // connection exception
            "53", // insufficient resources
            "57", // operator intervention
            "58", // system error
            "F0", // configuration file error
            "XX"); // internal error

    /** Полные коды, фатальные точечно. */
    private static final Set<String> FATAL_CODES = Set.of(
            "42P01", // undefined_table
            "42703", // undefined_column
            "42P07", // duplicate_table
            "42501", // insufficient_privilege
            "42601", // syntax_error
            "3D000", // invalid_catalog_name
            "28P01", // invalid_password
            "28000"); // invalid_authorization_specification

    /** Классы, ожидаемые на «плохих строках»: деление батча их изолирует. */
    private static final Set<String> RECOVERABLE_CLASSES = Set.of(
            "23", // integrity constraint violation
            "22"); // data exception

    @Override
    public boolean isFatal(SQLException exception) {
        for (Throwable current = exception; current != null; current = current.getCause()) {
            if (current instanceof SQLException sqlException) {
                Boolean verdict = classify(sqlException.getSQLState());
                if (verdict != null && verdict) {
                    return true;
                }
                if (verdict != null) {
                    return false;
                }
            }
        }
        return true; // не смогли определить — считаем фатальным
    }

    /** true — фатально, false — восстановимо, null — неизвестно, смотрим дальше по цепочке. */
    private static Boolean classify(String sqlState) {
        if (sqlState == null || sqlState.length() < 2) {
            return null;
        }
        if (FATAL_CODES.contains(sqlState)) {
            return Boolean.TRUE;
        }
        String errorClass = sqlState.substring(0, 2);
        if (FATAL_CLASSES.contains(errorClass)) {
            return Boolean.TRUE;
        }
        if (RECOVERABLE_CLASSES.contains(errorClass)) {
            return Boolean.FALSE;
        }
        return null;
    }
}
