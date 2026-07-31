package org.novgorodtsev.excelimport;

import java.time.Instant;
import java.util.UUID;

/**
 * Сведения о начинающемся прогоне.
 *
 * @param runId       уникальный идентификатор прогона
 * @param sourceName  имя исходного файла
 * @param targetTable таблица-приёмник
 * @param batchSize   размер батча из конфигурации
 * @param startedAt   момент старта
 */
public record ImportRunInfo(
        UUID runId, String sourceName, TableRef targetTable, int batchSize, Instant startedAt) {}
