package org.novgorodtsev.excelimport.internal.write;

import org.novgorodtsev.excelimport.ConflictStrategy;
import org.novgorodtsev.excelimport.TableRef;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Генерирует multi-row INSERT. Учитывает предел PostgreSQL в 65535 bind-параметров
 * на один запрос: при большом batchSize батч дробится на чанки.
 */
public final class SqlBuilder {

    /** Жёсткий предел числа bind-параметров в одном запросе PostgreSQL. */
    public static final int MAX_BIND_PARAMETERS = 65_535;

    private final String prefix;
    private final String tuple;
    private final String suffix;
    private final int columnCount;
    private final Map<Integer, String> cache = new ConcurrentHashMap<>();

    public SqlBuilder(TableRef table, List<String> columns, ConflictStrategy conflict) {
        if (columns.isEmpty()) {
            throw new IllegalArgumentException("список колонок пуст");
        }
        this.columnCount = columns.size();
        this.prefix = "INSERT INTO " + table.qualifiedName() + " ("
                + columns.stream().map(TableRef::quote).collect(Collectors.joining(", "))
                + ") VALUES ";
        this.tuple = "(" + "?, ".repeat(columnCount - 1) + "?)";
        String conflictSql = conflict.toSql();
        this.suffix = conflictSql.isEmpty() ? "" : " " + conflictSql;
    }

    public int columnCount() {
        return columnCount;
    }

    /** Сколько строк максимум влезает в один запрос, не превысив предел параметров. */
    public int maxRowsPerStatement() {
        return Math.max(1, MAX_BIND_PARAMETERS / columnCount);
    }

    /** Фактический размер чанка: запрошенный batchSize, урезанный пределом параметров. */
    public int chunkSize(int requestedBatchSize) {
        return Math.min(requestedBatchSize, maxRowsPerStatement());
    }

    /** SQL для указанного числа строк. Кэшируется, потому что вариантов всего два-три. */
    public String insertSql(int rowCount) {
        if (rowCount < 1) {
            throw new IllegalArgumentException("rowCount должен быть >= 1, получено: " + rowCount);
        }
        return cache.computeIfAbsent(rowCount, this::buildSql);
    }

    private String buildSql(int rowCount) {
        StringBuilder sql = new StringBuilder(prefix.length() + rowCount * (tuple.length() + 2)
                + suffix.length());
        sql.append(prefix);
        for (int i = 0; i < rowCount; i++) {
            if (i > 0) {
                sql.append(", ");
            }
            sql.append(tuple);
        }
        sql.append(suffix);
        return sql.toString();
    }
}
