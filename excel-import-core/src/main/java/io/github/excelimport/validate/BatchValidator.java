package io.github.excelimport.validate;

import io.github.excelimport.RowError;
import io.github.excelimport.RowRef;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;

/**
 * Межстрочная валидация собранного батча — единственный императивный хук пользователя.
 *
 * <p>Вызывается перед вставкой, в той же транзакции, поэтому видит данные, вставленные
 * предыдущими батчами этого же прогона, и может делать проверки по БД одним запросом.
 *
 * <p>Контракт: реализация НЕ должна вызывать {@code commit}, {@code rollback},
 * {@code setAutoCommit} или {@code close} на переданном соединении — попытка приведёт
 * к {@link IllegalStateException}. Строки, на которые возвращены ошибки, исключаются
 * из батча; остальные вставляются.
 *
 * <p>Реализация должна быть потокобезопасной, если один импортёр используется из
 * нескольких потоков.
 */
public interface BatchValidator<T> {

    /**
     * @param batch      строки батча вместе с 1-based номерами строк Excel
     * @param connection соединение текущей транзакции, только для чтения данных
     * @return ошибки, привязанные к номерам строк; пустой список, если всё в порядке
     */
    List<RowError> validate(List<RowRef<T>> batch, Connection connection) throws SQLException;
}
