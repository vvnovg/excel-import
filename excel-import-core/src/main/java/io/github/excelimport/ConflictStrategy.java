package io.github.excelimport;

import java.util.List;
import java.util.stream.Collectors;

/** Секция {@code ON CONFLICT} для генерируемого INSERT. */
public final class ConflictStrategy {

    private static final ConflictStrategy NONE = new ConflictStrategy(List.of(), null);

    private final List<String> conflictColumns;
    private final List<String> updateColumns;

    private ConflictStrategy(List<String> conflictColumns, List<String> updateColumns) {
        this.conflictColumns = List.copyOf(conflictColumns);
        this.updateColumns = updateColumns == null ? null : List.copyOf(updateColumns);
    }

    /** Без {@code ON CONFLICT}: конфликт приводит к ошибке БД. */
    public static ConflictStrategy none() {
        return NONE;
    }

    /** {@code ON CONFLICT (...) DO NOTHING} — конфликтующие строки молча пропускаются. */
    public static ConflictStrategy doNothing(String... conflictColumns) {
        requireColumns(List.of(conflictColumns));
        return new ConflictStrategy(List.of(conflictColumns), null);
    }

    /** {@code ON CONFLICT (...) DO UPDATE SET col = EXCLUDED.col, ...} */
    public static ConflictStrategy doUpdate(List<String> conflictColumns, List<String> updateColumns) {
        requireColumns(conflictColumns);
        if (updateColumns == null || updateColumns.isEmpty()) {
            throw new IllegalArgumentException("для DO UPDATE нужен непустой список обновляемых колонок");
        }
        return new ConflictStrategy(conflictColumns, updateColumns);
    }

    private static void requireColumns(List<String> columns) {
        if (columns == null || columns.isEmpty()) {
            throw new IllegalArgumentException("для ON CONFLICT нужна хотя бы одна колонка");
        }
    }

    /** true, если конфликтующие строки не приводят к ошибке БД. */
    public boolean swallowsConflicts() {
        return !conflictColumns.isEmpty();
    }

    /** SQL-фрагмент; пустая строка для {@link #none()}. */
    public String toSql() {
        if (conflictColumns.isEmpty()) {
            return "";
        }
        String target = conflictColumns.stream()
                .map(TableRef::quote)
                .collect(Collectors.joining(", "));
        if (updateColumns == null) {
            return "ON CONFLICT (" + target + ") DO NOTHING";
        }
        String assignments = updateColumns.stream()
                .map(column -> TableRef.quote(column) + " = EXCLUDED." + TableRef.quote(column))
                .collect(Collectors.joining(", "));
        return "ON CONFLICT (" + target + ") DO UPDATE SET " + assignments;
    }
}
