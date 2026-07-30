package io.github.excelimport;

import java.sql.SQLException;

/**
 * Решает, можно ли продолжать импорт после ошибки БД. Фатальная ошибка прерывает
 * весь прогон; нефатальная запускает деление батча для поиска сбойных строк.
 *
 * <p>Потребитель может подменить реализацию, если его окружение возвращает
 * нестандартные {@code SQLState}.
 */
public interface SqlErrorClassifier {

    boolean isFatal(SQLException exception);
}
