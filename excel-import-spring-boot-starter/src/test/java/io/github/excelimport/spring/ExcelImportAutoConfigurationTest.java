package io.github.excelimport.spring;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.excelimport.ImportConfig;
import io.github.excelimport.RowError;
import io.github.excelimport.RowRef;
import io.github.excelimport.annotation.Column;
import io.github.excelimport.annotation.ExcelColumn;
import io.github.excelimport.annotation.ExcelSheet;
import io.github.excelimport.annotation.TargetTable;
import io.github.excelimport.validate.BatchValidator;
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
    void defaultsMatchCoreDefaults() {
        runner.run(context -> {
            ImportConfig config = context.getBean(ExcelImportProperties.class).toImportConfig();

            assertThat(config.batchSize()).isEqualTo(1000);
            assertThat(config.reportPath()).isNull();
            assertThat(config.conflictStrategy().toSql()).isEmpty();
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
