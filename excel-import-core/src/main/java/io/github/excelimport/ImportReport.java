package io.github.excelimport;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

/** Итог прогона импорта. */
public record ImportReport(
        UUID runId,
        String sourceName,
        long totalRows,
        long insertedRows,
        long rejectedRows,
        long batchesCommitted,
        Duration duration,
        Path reportPath,
        List<RowError> errors,
        boolean errorLimitReached,
        ImportStatus status) {

    public ImportReport {
        errors = List.copyOf(errors);
    }
}
