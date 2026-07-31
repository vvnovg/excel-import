package org.novgorodtsev.excelimport.outcome;

import org.novgorodtsev.excelimport.RowOutcome;

/**
 * Хранилище исходов строк между двумя проходами импорта. Реализация по умолчанию
 * держит статусы в памяти, а сообщения об ошибках выгружает на диск после порога.
 *
 * <p>Жизненный цикл: запись через {@link #put} на первом проходе → {@link #seal()} →
 * чтение через {@link #get} на втором проходе → {@link #close()}.
 *
 * <p>Реализации не обязаны поддерживать произвольный порядок чтения: контракт по
 * умолчанию — обращения по возрастанию {@code rowNum}, как идёт второй проход.
 */
public interface RowOutcomeStore extends AutoCloseable {

    /** @param rowNum 1-based номер строки Excel */
    void put(int rowNum, RowOutcome outcome);

    /** @return исход строки; {@code RowOutcome.notProcessed()} для неизвестной строки */
    RowOutcome get(int rowNum);

    /** Наибольший номер строки, для которой был вызван {@link #put}; 0, если ничего не было. */
    int maxRowNum();

    /** Переводит хранилище в режим чтения. После вызова {@link #put} запрещён. */
    void seal();

    @Override
    void close();
}
