package org.novgorodtsev.excelimport;

/**
 * Наблюдение за прогоном: прогресс-бар, метрики, чекпоинты в своей таблице.
 * Исключение из любого метода логируется и не влияет на импорт.
 */
public interface ImportListener {

    default void onImportStarted(ImportRunInfo info) {}

    /**
     * @param batchIndex    порядковый номер батча, начиная с 1
     * @param rowCount      сколько строк вставлено этим батчем
     * @param totalInserted сколько всего вставлено с начала прогона
     */
    default void onBatchCommitted(int batchIndex, int rowCount, long totalInserted) {}

    /** Батч пришлось делить пополам из-за ошибки БД. */
    default void onBatchSplit(int batchIndex, int depth, int rowCount) {}

    default void onRowRejected(RowError error) {}

    default void onImportFinished(ImportReport report) {}
}
