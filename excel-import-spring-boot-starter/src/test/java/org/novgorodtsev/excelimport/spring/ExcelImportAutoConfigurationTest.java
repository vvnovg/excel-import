package org.novgorodtsev.excelimport.spring;

import static org.assertj.core.api.Assertions.assertThat;

import org.novgorodtsev.excelimport.ImportConfig;
import org.novgorodtsev.excelimport.RowError;
import org.novgorodtsev.excelimport.RowRef;
import org.novgorodtsev.excelimport.annotation.Column;
import org.novgorodtsev.excelimport.annotation.ExcelColumn;
import org.novgorodtsev.excelimport.annotation.ExcelSheet;
import org.novgorodtsev.excelimport.annotation.TargetTable;
import org.novgorodtsev.excelimport.validate.BatchValidator;
import java.sql.Connection;
import java.util.List;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

class ExcelImportAutoConfigurationTest {

    @ExcelSheet(name = "S")
    @TargetTable(name = "t")
    public static class Row {
        @ExcelColumn(header = "A")
        @Column("a")
        public String a;

        public Row() {}
    }

    /** Тип, не связанный с {@link Row} — используется, чтобы убедиться, что валидатор
     * с разрешённым параметром типа НЕ подключается к чужим импортёрам. */
    @ExcelSheet(name = "S2")
    @TargetTable(name = "t2")
    public static class OtherRow {
        @ExcelColumn(header = "B")
        @Column("b")
        public String b;

        public OtherRow() {}
    }

    /** Именованный класс с конкретным (не переиспользуемым) параметром типа — параметр
     * типа сохраняется в рефлексии, в отличие от лямбды. */
    static class NamedRowValidator implements BatchValidator<Row> {
        @Override
        public List<RowError> validate(List<RowRef<Row>> batch, Connection connection) {
            return List.of();
        }
    }

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    DataSourceAutoConfiguration.class, ExcelImportAutoConfiguration.class))
            .withPropertyValues(
                    "spring.datasource.url=jdbc:h2:mem:test",
                    "spring.datasource.driver-class-name=org.h2.Driver");

    @Test
    void factoryBeanIsRegistered() {
        runner.run(context -> assertThat(context).hasSingleBean(ExcelImporterFactory.class));
    }

    @Test
    void propertiesAreBound() {
        runner.withPropertyValues(
                        "excel-import.batch-size=5000",
                        "excel-import.max-errors=100",
                        "excel-import.locale=ru-RU",
                        "excel-import.report.enabled=true",
                        "excel-import.report.directory=/tmp/reports",
                        "excel-import.conflict.strategy=do-nothing",
                        "excel-import.conflict.columns=personnel_no")
                .run(context -> {
                    ExcelImportProperties properties = context.getBean(ExcelImportProperties.class);

                    assertThat(properties.getBatchSize()).isEqualTo(5000);
                    assertThat(properties.getMaxErrors()).isEqualTo(100);
                    assertThat(properties.getReport().isEnabled()).isTrue();
                    assertThat(properties.getConflict().getColumns()).containsExactly("personnel_no");

                    ImportConfig config = properties.toImportConfig();
                    assertThat(config.batchSize()).isEqualTo(5000);
                    assertThat(config.conflictStrategy().toSql())
                            .isEqualTo("ON CONFLICT (\"personnel_no\") DO NOTHING");
                });
    }

    @Test
    void toImportConfigWithReportPathKeepsConfiguredPropertiesAndReportPath() {
        runner.withPropertyValues("excel-import.batch-size=5000").run(context -> {
            ExcelImportProperties properties = context.getBean(ExcelImportProperties.class);
            java.nio.file.Path reportPath = java.nio.file.Path.of("/tmp/reports/employees-report.xlsx");

            ImportConfig config = properties.toImportConfig(reportPath);

            assertThat(config.batchSize()).isEqualTo(5000);
            assertThat(config.reportPath()).isEqualTo(reportPath);
        });
    }

    @Test
    void toImportConfigWithNullReportPathBehavesLikeNoArgOverload() {
        runner.withPropertyValues("excel-import.batch-size=5000").run(context -> {
            ExcelImportProperties properties = context.getBean(ExcelImportProperties.class);

            ImportConfig withNull = properties.toImportConfig(null);
            ImportConfig noArg = properties.toImportConfig();

            assertThat(withNull.batchSize()).isEqualTo(noArg.batchSize());
            assertThat(withNull.reportPath()).isEqualTo(noArg.reportPath()).isNull();
        });
    }

    @Test
    void defaultsMatchCoreDefaults() {
        runner.run(context -> {
            ImportConfig config = context.getBean(ExcelImportProperties.class).toImportConfig();
            ImportConfig coreDefaults = ImportConfig.builder().build();

            assertThat(config.batchSize()).isEqualTo(coreDefaults.batchSize());
            assertThat(config.reportPath()).isEqualTo(coreDefaults.reportPath());
            assertThat(config.conflictStrategy().toSql()).isEqualTo(coreDefaults.conflictStrategy().toSql());
            assertThat(config.maxErrors()).isEqualTo(coreDefaults.maxErrors());
            assertThat(config.maxErrorsInMemory()).isEqualTo(coreDefaults.maxErrorsInMemory());
            assertThat(config.maxSplitDepth()).isEqualTo(coreDefaults.maxSplitDepth());
            assertThat(config.maxOutcomeMessagesInMemory()).isEqualTo(coreDefaults.maxOutcomeMessagesInMemory());
            assertThat(config.skipBlankRows()).isEqualTo(coreDefaults.skipBlankRows());
            assertThat(config.expandMergedCells()).isEqualTo(coreDefaults.expandMergedCells());
            assertThat(config.includeDatabaseDetailInReport())
                    .isEqualTo(coreDefaults.includeDatabaseDetailInReport());
            assertThat(config.dryRun()).isEqualTo(coreDefaults.dryRun());
            assertThat(config.queryTimeoutSeconds()).isEqualTo(coreDefaults.queryTimeoutSeconds());
            assertThat(config.locale()).isEqualTo(coreDefaults.locale());
            assertThat(config.tempDir()).isEqualTo(coreDefaults.tempDir());
        });
    }

    @Configuration
    static class WithValidator {
        @Bean
        BatchValidator<Row> validator() {
            return new BatchValidator<>() {
                @Override
                public List<RowError> validate(List<RowRef<Row>> batch, Connection connection) {
                    return List.of();
                }
            };
        }
    }

    @Test
    void batchValidatorBeansArePickedUp() {
        runner.withUserConfiguration(WithValidator.class).run(context -> {
            ExcelImporterFactory factory = context.getBean(ExcelImporterFactory.class);

            assertThat(factory.batchValidatorsFor(Row.class)).hasSize(1);
        });
    }

    @Configuration
    static class WithLambdaValidator {
        @Bean
        BatchValidator<Row> validator() {
            // Лямбда: реализует BatchValidator<Row>, но getGenericInterfaces() на её классе
            // возвращает сырой BatchValidator без параметра — параметр типа стёрт.
            return (batch, connection) -> List.of();
        }
    }

    @Test
    void lambdaValidatorIsNotAttachedToAnyImporter() {
        runner.withUserConfiguration(WithLambdaValidator.class).run(context -> {
            ExcelImporterFactory factory = context.getBean(ExcelImporterFactory.class);

            assertThat(factory.batchValidatorsFor(Row.class)).isEmpty();
            assertThat(factory.batchValidatorsFor(OtherRow.class)).isEmpty();
        });
    }

    @Configuration
    static class WithNamedValidator {
        @Bean
        BatchValidator<Row> validator() {
            return new NamedRowValidator();
        }
    }

    @Test
    void namedClassValidatorIsAttachedOnlyToItsRowType() {
        runner.withUserConfiguration(WithNamedValidator.class).run(context -> {
            ExcelImporterFactory factory = context.getBean(ExcelImporterFactory.class);

            assertThat(factory.batchValidatorsFor(Row.class)).hasSize(1);
            assertThat(factory.batchValidatorsFor(OtherRow.class)).isEmpty();
        });
    }

    @Test
    void factoryCreatesWorkingImporter() {
        runner.run(context -> {
            ExcelImporterFactory factory = context.getBean(ExcelImporterFactory.class);

            try (var importer = factory.create(Row.class)) {
                assertThat(importer).isNotNull();
            }
        });
    }

    @Test
    void userSuppliedFactoryWins() {
        runner.withUserConfiguration(CustomFactory.class).run(context -> {
            assertThat(context).hasSingleBean(ExcelImporterFactory.class);
            assertThat(context.getBean(ExcelImporterFactory.class))
                    .isSameAs(context.getBean(CustomFactory.class).marker);
        });
    }

    @Configuration
    static class CustomFactory {
        final ExcelImporterFactory marker;

        CustomFactory(DataSource dataSource) {
            this.marker = new ExcelImporterFactory(
                    dataSource, ImportConfig.builder().build(), List.of(), java.util.Map.of(),
                    null, null, null);
        }

        @Bean
        ExcelImporterFactory excelImporterFactory() {
            return marker;
        }
    }
}
