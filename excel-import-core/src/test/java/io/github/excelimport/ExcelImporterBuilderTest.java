package io.github.excelimport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import io.github.excelimport.annotation.Column;
import io.github.excelimport.annotation.ExcelColumn;
import io.github.excelimport.annotation.ExcelSheet;
import io.github.excelimport.annotation.TargetTable;
import io.github.excelimport.exception.MappingConfigurationException;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;

class ExcelImporterBuilderTest {

    @ExcelSheet(name = "S")
    @TargetTable(name = "t")
    public static class WithTable {
        @ExcelColumn(header = "A")
        @Column("a")
        public String a;

        public WithTable() {}
    }

    @ExcelSheet(name = "S")
    public static class WithoutTable {
        @ExcelColumn(header = "A")
        @Column("a")
        public String a;

        public WithoutTable() {}
    }

    private final DataSource dataSource = mock(DataSource.class);

    @Test
    void buildsWithAnnotationDrivenTable() {
        try (ExcelImporter<WithTable> importer = ExcelImporter.builder(WithTable.class)
                .dataSource(dataSource)
                .build()) {
            assertThat(importer).isNotNull();
        }
    }

    @Test
    void dataSourceIsRequired() {
        assertThatThrownBy(() -> ExcelImporter.builder(WithTable.class).build())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("dataSource");
    }

    @Test
    void missingTableAnywhereIsRejectedAtBuildTime() {
        assertThatThrownBy(() -> ExcelImporter.builder(WithoutTable.class)
                        .dataSource(dataSource)
                        .build())
                .isInstanceOf(MappingConfigurationException.class)
                .hasMessageContaining("@TargetTable");
    }

    @Test
    void configTableOverridesMissingAnnotation() {
        try (ExcelImporter<WithoutTable> importer = ExcelImporter.builder(WithoutTable.class)
                .dataSource(dataSource)
                .config(ImportConfig.builder().targetTable(TableRef.of("public.t")).build())
                .build()) {
            assertThat(importer).isNotNull();
        }
    }

    @Test
    void dryRunConfigIsAccepted() {
        try (ExcelImporter<WithTable> importer = ExcelImporter.builder(WithTable.class)
                .config(ImportConfig.builder().dryRun(true).build())
                .dataSource(dataSource)
                .build()) {
            assertThat(importer).isNotNull();
        }
    }

    @Test
    void mappingErrorsSurfaceAtBuildTimeNotAtImportTime() {
        class NotAnnotated {}

        assertThatThrownBy(() -> ExcelImporter.builder(NotAnnotated.class).dataSource(dataSource).build())
                .isInstanceOf(MappingConfigurationException.class);
    }
}
