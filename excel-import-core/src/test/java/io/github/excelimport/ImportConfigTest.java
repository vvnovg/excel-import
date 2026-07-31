package io.github.excelimport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class ImportConfigTest {

    @Test
    void defaultsMatchSpec() {
        ImportConfig config = ImportConfig.builder().build();

        assertThat(config.batchSize()).isEqualTo(1000);
        assertThat(config.maxErrors()).isEqualTo(Integer.MAX_VALUE);
        assertThat(config.maxErrorsInMemory()).isEqualTo(1000);
        assertThat(config.maxSplitDepth()).isEqualTo(16);
        assertThat(config.maxOutcomeMessagesInMemory()).isEqualTo(50_000);
        assertThat(config.skipBlankRows()).isTrue();
        assertThat(config.expandMergedCells()).isTrue();
        assertThat(config.includeDatabaseDetailInReport()).isTrue();
        assertThat(config.dryRun()).isFalse();
        assertThat(config.reportPath()).isNull();
        assertThat(config.conflictStrategy().toSql()).isEmpty();
        assertThat(config.queryTimeoutSeconds()).isZero();
    }

    @Test
    void builderOverridesValues() {
        ImportConfig config = ImportConfig.builder()
                .batchSize(5000)
                .sheet(SheetSelector.byName("Данные"))
                .headerRow(3)
                .maxErrors(10)
                .reportPath(Path.of("/tmp/report.xlsx"))
                .dryRun(true)
                .build();

        assertThat(config.batchSize()).isEqualTo(5000);
        assertThat(config.sheet()).isNotNull();
        assertThat(config.headerRow()).isEqualTo(3);
        assertThat(config.firstDataRow()).isEqualTo(4); // headerRow + 1 по умолчанию
        assertThat(config.dryRun()).isTrue();
    }

    /**
     * Явный {@code headerRow(0)} должен отличаться от «не задано»: иначе конфигурация
     * не может переопределить {@code @ExcelSheet(headerRow = N)} обратно на нулевую строку.
     */
    @Test
    void explicitZeroHeaderRowIsDistinguishedFromUnset() {
        ImportConfig nothingSet = ImportConfig.builder().build();
        assertThat(nothingSet.headerRowExplicit()).isFalse();
        assertThat(nothingSet.firstDataRowExplicit()).isFalse();

        // флаги раздельные: задание одного параметра не объявляет заданным другой,
        // иначе headerRow из @ExcelSheet молча подменялся бы нулём по умолчанию
        ImportConfig onlyHeaderRow = ImportConfig.builder().headerRow(0).build();
        assertThat(onlyHeaderRow.headerRowExplicit()).isTrue();
        assertThat(onlyHeaderRow.firstDataRowExplicit()).isFalse();

        ImportConfig onlyFirstDataRow = ImportConfig.builder().firstDataRow(1).build();
        assertThat(onlyFirstDataRow.headerRowExplicit()).isFalse();
        assertThat(onlyFirstDataRow.firstDataRowExplicit()).isTrue();

        // значения, видимые снаружи, при этом не меняются
        ImportConfig unset = ImportConfig.builder().build();
        assertThat(unset.headerRow()).isZero();
        assertThat(unset.firstDataRow()).isEqualTo(1);
    }

    @Test
    void rejectsNonPositiveBatchSize() {
        assertThatThrownBy(() -> ImportConfig.builder().batchSize(0).build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("batchSize");

        assertThatThrownBy(() -> ImportConfig.builder().batchSize(-5).build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("batchSize");
    }

    @Test
    void rejectsNegativeHeaderRow() {
        assertThatThrownBy(() -> ImportConfig.builder().headerRow(-1).build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("headerRow");
    }

    @Test
    void rejectsFirstDataRowNotAfterHeaderRow() {
        assertThatThrownBy(() -> ImportConfig.builder().headerRow(5).firstDataRow(5).build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("firstDataRow");
    }

    @Test
    void rejectsFirstDataRowNegativeOtherThanSentinel() {
        assertThatThrownBy(() -> ImportConfig.builder().firstDataRow(-2).build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("firstDataRow");
    }

    @Test
    void rejectsFirstDataRowLessThanHeaderRow() {
        assertThatThrownBy(() -> ImportConfig.builder().headerRow(5).firstDataRow(2).build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("firstDataRow");
    }

    @Test
    void rejectsNegativeSplitDepth() {
        assertThatThrownBy(() -> ImportConfig.builder().maxSplitDepth(-1).build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("maxSplitDepth");
    }

    @Test
    void rejectsNegativeMaxErrors() {
        assertThatThrownBy(() -> ImportConfig.builder().maxErrors(-1).build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("maxErrors");
    }

    @Test
    void rejectsNegativeMaxErrorsInMemory() {
        assertThatThrownBy(() -> ImportConfig.builder().maxErrorsInMemory(-1).build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("maxErrorsInMemory");
    }

    @Test
    void rejectsNegativeMaxOutcomeMessagesInMemory() {
        assertThatThrownBy(() -> ImportConfig.builder().maxOutcomeMessagesInMemory(-1).build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("maxOutcomeMessagesInMemory");
    }

    @Test
    void rejectsNegativeQueryTimeoutSeconds() {
        assertThatThrownBy(() -> ImportConfig.builder().queryTimeoutSeconds(-1).build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("queryTimeoutSeconds");
    }

    @Test
    void configIsImmutableAfterBuild() {
        ImportConfig.Builder builder = ImportConfig.builder().batchSize(100);
        ImportConfig first = builder.build();
        builder.batchSize(200);

        assertThat(first.batchSize()).isEqualTo(100);
    }
}
