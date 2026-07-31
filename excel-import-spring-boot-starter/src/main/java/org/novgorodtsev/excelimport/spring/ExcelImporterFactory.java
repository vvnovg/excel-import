package org.novgorodtsev.excelimport.spring;

import org.novgorodtsev.excelimport.ExcelImporter;
import org.novgorodtsev.excelimport.ImportConfig;
import org.novgorodtsev.excelimport.ImportListener;
import org.novgorodtsev.excelimport.SqlErrorClassifier;
import org.novgorodtsev.excelimport.convert.CellConverter;
import org.novgorodtsev.excelimport.report.ReportRowCustomizer;
import org.novgorodtsev.excelimport.validate.BatchValidator;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Создаёт типизированные {@link ExcelImporter} по классу модели, подставляя бины из
 * контекста: {@link BatchValidator}, {@link CellConverter}, {@link ReportRowCustomizer},
 * {@link SqlErrorClassifier}, {@link ImportListener}.
 */
public class ExcelImporterFactory {

    private static final Logger log = LoggerFactory.getLogger(ExcelImporterFactory.class);

    private final DataSource dataSource;
    private final ImportConfig defaultConfig;
    private final List<ResolvedValidator> batchValidators;
    private final Map<Class<?>, CellConverter<?>> converters;
    private final ReportRowCustomizer reportRowCustomizer;
    private final SqlErrorClassifier sqlErrorClassifier;
    private final ImportListener listener;

    private record ResolvedValidator(BatchValidator<?> validator, Class<?> rowType) {}

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
        this.batchValidators = resolveValidators(batchValidators);
        this.converters = Map.copyOf(converters);
        this.reportRowCustomizer = reportRowCustomizer;
        this.sqlErrorClassifier = sqlErrorClassifier;
        this.listener = listener;
    }

    /**
     * Разрешает параметр типа каждого валидатора один раз при создании фабрики.
     * Валидатор, у которого параметр типа стёрт (лямбда или переиспользуемый generic-класс —
     * оба дают {@code null}), в список не попадает: JVM подставила бы его во все импортёры
     * молча, а implicit checkcast бросал бы {@code ClassCastException} на несовместимой
     * строке где-то в середине импорта. Вместо этого — один {@code WARN} на такой бин.
     */
    private static List<ResolvedValidator> resolveValidators(List<BatchValidator<?>> validators) {
        List<ResolvedValidator> resolved = new ArrayList<>();
        for (BatchValidator<?> validator : validators) {
            Class<?> rowType = resolveTypeParameter(validator.getClass());
            if (rowType == null) {
                log.warn(
                        "Бин BatchValidator класса {} не подключён ни к одному импортёру: "
                                + "не удалось определить параметр типа (лямбда или переиспользуемый "
                                + "generic-класс стирают его во время выполнения). Чтобы исправить: "
                                + "объявите именованный класс, реализующий BatchValidator<КонкретнаяСтрока>, "
                                + "либо зарегистрируйте валидатор явно через "
                                + "ExcelImporter.builder(...).batchValidator(...).",
                        validator.getClass().getName());
                continue;
            }
            resolved.add(new ResolvedValidator(validator, rowType));
        }
        return List.copyOf(resolved);
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
     * Отбирает валидаторы, параметризованные указанным типом (включая случай, когда
     * параметр — супертип {@code type}). Валидатор, у которого параметр типа стёрт во время
     * выполнения (лямбда или переиспользуемый generic-класс), сюда никогда не попадает —
     * он отсеян и залогирован ещё в конструкторе, см. {@link #resolveValidators}.
     */
    @SuppressWarnings("unchecked")
    public <T> List<BatchValidator<T>> batchValidatorsFor(Class<T> type) {
        List<BatchValidator<T>> matching = new ArrayList<>();
        for (ResolvedValidator resolved : batchValidators) {
            if (resolved.rowType().isAssignableFrom(type)) {
                matching.add((BatchValidator<T>) resolved.validator());
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
