package io.github.excelimport.spring;

import io.github.excelimport.ExcelImporter;
import io.github.excelimport.ImportConfig;
import io.github.excelimport.ImportListener;
import io.github.excelimport.SqlErrorClassifier;
import io.github.excelimport.convert.CellConverter;
import io.github.excelimport.report.ReportRowCustomizer;
import io.github.excelimport.validate.BatchValidator;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;

/**
 * Создаёт типизированные {@link ExcelImporter} по классу модели, подставляя бины из
 * контекста: {@link BatchValidator}, {@link CellConverter}, {@link ReportRowCustomizer},
 * {@link SqlErrorClassifier}, {@link ImportListener}.
 */
public class ExcelImporterFactory {

    private final DataSource dataSource;
    private final ImportConfig defaultConfig;
    private final List<BatchValidator<?>> batchValidators;
    private final Map<Class<?>, CellConverter<?>> converters;
    private final ReportRowCustomizer reportRowCustomizer;
    private final SqlErrorClassifier sqlErrorClassifier;
    private final ImportListener listener;

    public ExcelImporterFactory(
            DataSource dataSource,
            ImportConfig defaultConfig,
            List<BatchValidator<?>> batchValidators,
            Map<Class<?>, CellConverter<?>> converters,
            ReportRowCustomizer reportRowCustomizer,
            SqlErrorClassifier sqlErrorClassifier,
            ImportListener listener) {
        this.dataSource = dataSource;
        this.defaultConfig = defaultConfig;
        this.batchValidators = List.copyOf(batchValidators);
        this.converters = Map.copyOf(converters);
        this.reportRowCustomizer = reportRowCustomizer;
        this.sqlErrorClassifier = sqlErrorClassifier;
        this.listener = listener;
    }

    public <T> ExcelImporter<T> create(Class<T> type) {
        return create(type, defaultConfig);
    }

    public <T> ExcelImporter<T> create(Class<T> type, ImportConfig config) {
        ExcelImporter.Builder<T> builder = ExcelImporter.builder(type)
                .dataSource(dataSource)
                .config(config);
        for (BatchValidator<T> validator : batchValidatorsFor(type)) {
            builder.batchValidator(validator);
        }
        converters.forEach(builder::converter);
        if (reportRowCustomizer != null) {
            builder.reportRowCustomizer(reportRowCustomizer);
        }
        if (sqlErrorClassifier != null) {
            builder.sqlErrorClassifier(sqlErrorClassifier);
        }
        if (listener != null) {
            builder.listener(listener);
        }
        return builder.build();
    }

    /**
     * Отбирает валидаторы, параметризованные указанным типом. Валидатор, у которого
     * параметр типа стёрт (лямбда без явного generic), считается подходящим для любого
     * типа — иначе он был бы бесполезен.
     */
    @SuppressWarnings("unchecked")
    public <T> List<BatchValidator<T>> batchValidatorsFor(Class<T> type) {
        List<BatchValidator<T>> matching = new ArrayList<>();
        for (BatchValidator<?> validator : batchValidators) {
            Class<?> parameter = resolveTypeParameter(validator.getClass());
            if (parameter == null || parameter.isAssignableFrom(type)) {
                matching.add((BatchValidator<T>) validator);
            }
        }
        return List.copyOf(matching);
    }

    private static Class<?> resolveTypeParameter(Class<?> validatorClass) {
        for (Type candidate : validatorClass.getGenericInterfaces()) {
            if (candidate instanceof ParameterizedType parameterized
                    && parameterized.getRawType() == BatchValidator.class) {
                Type argument = parameterized.getActualTypeArguments()[0];
                if (argument instanceof Class<?> raw) {
                    return raw;
                }
            }
        }
        Class<?> superclass = validatorClass.getSuperclass();
        return superclass == null || superclass == Object.class
                ? null
                : resolveTypeParameter(superclass);
    }
}
