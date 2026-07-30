package io.github.excelimport.spring;

import io.github.excelimport.ImportConfig;
import io.github.excelimport.ImportListener;
import io.github.excelimport.SqlErrorClassifier;
import io.github.excelimport.convert.CellConverter;
import io.github.excelimport.report.ReportRowCustomizer;
import io.github.excelimport.validate.BatchValidator;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Автоконфигурация: регистрирует {@link ExcelImporterFactory}, если есть DataSource. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnClass(DataSource.class)
@EnableConfigurationProperties(ExcelImportProperties.class)
public class ExcelImportAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public ExcelImporterFactory excelImporterFactory(
            DataSource dataSource,
            ExcelImportProperties properties,
            ObjectProvider<BatchValidator<?>> batchValidators,
            ObjectProvider<ReportRowCustomizer> reportRowCustomizer,
            ObjectProvider<SqlErrorClassifier> sqlErrorClassifier,
            ObjectProvider<ImportListener> listener) {
        ImportConfig config = properties.toImportConfig();
        List<BatchValidator<?>> validators = batchValidators.orderedStream().toList();
        Map<Class<?>, CellConverter<?>> converters = Map.of();
        return new ExcelImporterFactory(
                dataSource,
                config,
                validators,
                converters,
                reportRowCustomizer.getIfAvailable(),
                sqlErrorClassifier.getIfAvailable(),
                listener.getIfAvailable());
    }
}
