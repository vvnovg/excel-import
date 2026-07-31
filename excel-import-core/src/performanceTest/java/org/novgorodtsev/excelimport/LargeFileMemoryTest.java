package org.novgorodtsev.excelimport;

import static org.assertj.core.api.Assertions.assertThat;

import org.novgorodtsev.excelimport.annotation.Column;
import org.novgorodtsev.excelimport.annotation.ExcelColumn;
import org.novgorodtsev.excelimport.annotation.ExcelSheet;
import org.novgorodtsev.excelimport.annotation.TargetTable;
import org.novgorodtsev.excelimport.testsupport.LargeFixture;
import org.novgorodtsev.excelimport.testsupport.PostgresSupport;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Проверяет, что импорт 100 000 строк проходит при {@code -Xmx256m} (задано в задаче
 * Gradle {@code performanceTest}) и укладывается в ориентир 30 секунд.
 */
class LargeFileMemoryTest {

    private static final int ROWS = 100_000;

    @ExcelSheet(name = "Лист1")
    @TargetTable(name = "big")
    public static class BigRow {
        @ExcelColumn(header = "Ключ")
        @Column("key")
        public Long key;

        @ExcelColumn(header = "ФИО")
        @Column("name")
        public String name;

        public BigRow() {}
    }

    @TempDir
    Path tempDir;

    @BeforeEach
    void resetTable() {
        PostgresSupport.execute(
                "DROP TABLE IF EXISTS big",
                "CREATE TABLE big (key bigint PRIMARY KEY, name text)");
    }

    @Test
    void importsHundredThousandRowsWithinMemoryAndTimeBudget() {
        Path file = LargeFixture.generate(tempDir, ROWS);
        Path reportPath = tempDir.resolve("large-report.xlsx");

        Instant start = Instant.now();
        try (ExcelImporter<BigRow> importer = ExcelImporter.builder(BigRow.class)
                .dataSource(PostgresSupport.dataSource())
                .config(ImportConfig.builder()
                        .batchSize(1000)
                        .reportPath(reportPath)
                        .tempDir(tempDir)
                        .build())
                .build()) {
            ImportReport report = importer.importFile(file);

            assertThat(report.status()).isEqualTo(ImportStatus.SUCCESS);
            assertThat(report.insertedRows()).isEqualTo(ROWS);
            assertThat(report.batchesCommitted()).isEqualTo(ROWS / 1000);
        }
        Duration elapsed = Duration.between(start, Instant.now());

        assertThat(PostgresSupport.countRows("big")).isEqualTo(ROWS);
        assertThat(elapsed).isLessThan(Duration.ofSeconds(60));
        System.out.printf("импорт %d строк занял %d мс%n", ROWS, elapsed.toMillis());
    }
}
