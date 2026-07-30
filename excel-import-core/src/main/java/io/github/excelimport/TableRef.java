package io.github.excelimport;

import java.util.Objects;

/**
 * Ссылка на таблицу-приёмник. Идентификаторы всегда квотируются двойными кавычками,
 * поэтому имена регистрозависимы и не могут содержать кавычку.
 *
 * @param schema имя схемы; null означает search_path
 * @param name   имя таблицы
 */
public record TableRef(String schema, String name) {

    public TableRef {
        if (schema != null) {
            validateIdentifier(schema);
        }
        Objects.requireNonNull(name, "name");
        validateIdentifier(name);
    }

    /** Разбирает {@code "schema.table"} или {@code "table"}. */
    public static TableRef of(String qualified) {
        Objects.requireNonNull(qualified, "qualified");
        String[] parts = qualified.split("\\.", -1);
        return switch (parts.length) {
            case 1 -> new TableRef(null, parts[0]);
            case 2 -> new TableRef(parts[0], parts[1]);
            default -> throw new IllegalArgumentException(
                    "ожидался формат схема.таблица или таблица, получено: " + qualified);
        };
    }

    public String qualifiedName() {
        return schema == null ? quote(name) : quote(schema) + "." + quote(name);
    }

    /** Квотирует идентификатор для подстановки в SQL. */
    public static String quote(String identifier) {
        validateIdentifier(identifier);
        return '"' + identifier + '"';
    }

    private static void validateIdentifier(String identifier) {
        if (identifier.isBlank()) {
            throw new IllegalArgumentException("недопустимый идентификатор: пустая строка");
        }
        if (identifier.indexOf('"') >= 0 || identifier.indexOf('\0') >= 0) {
            throw new IllegalArgumentException("недопустимый идентификатор: " + identifier);
        }
    }
}
