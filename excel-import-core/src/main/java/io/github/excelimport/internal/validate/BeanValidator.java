package io.github.excelimport.internal.validate;

import io.github.excelimport.RowError;
import io.github.excelimport.internal.map.ColumnBinding;
import io.github.excelimport.internal.map.MappingModel;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import jakarta.validation.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.hibernate.validator.HibernateValidator;
import org.hibernate.validator.messageinterpolation.ParameterMessageInterpolator;

/**
 * Обёртка над Jakarta Bean Validation. Сообщения интерполируются в заданной локали,
 * имена полей переводятся в заголовки Excel-колонок.
 */
public final class BeanValidator implements AutoCloseable {

    private final ValidatorFactory factory;
    private final Validator validator;
    private final Map<String, String> fieldToHeader;

    public BeanValidator(Locale locale, MappingModel<?> model) {
        this.factory = Validation.byProvider(HibernateValidator.class)
                .configure()
                .messageInterpolator(new LocaleFixedInterpolator(locale))
                .buildValidatorFactory();
        this.validator = factory.getValidator();
        this.fieldToHeader = new HashMap<>();
        for (ColumnBinding binding : model.excelColumns()) {
            fieldToHeader.put(binding.fieldName(), binding.displayName());
        }
    }

    /**
     * @param rowNum 1-based номер строки Excel
     * @return все нарушения; пустой список, если объект валиден
     */
    public List<RowError> validate(Object bean, int rowNum) {
        Set<ConstraintViolation<Object>> violations = validator.validate(bean);
        if (violations.isEmpty()) {
            return List.of();
        }
        List<RowError> errors = new ArrayList<>(violations.size());
        for (ConstraintViolation<Object> violation : violations) {
            String field = leafFieldName(violation.getPropertyPath());
            String header = field == null ? null : fieldToHeader.get(field);
            String code = violation.getConstraintDescriptor().getAnnotation()
                    .annotationType()
                    .getSimpleName();
            String rawValue = violation.getInvalidValue() == null
                    ? null
                    : String.valueOf(violation.getInvalidValue());
            errors.add(RowError.constraint(rowNum, header, rawValue, code, violation.getMessage()));
        }
        // порядок нарушений в Set не определён — сортируем для предсказуемости отчёта
        errors.sort(java.util.Comparator.comparing(
                error -> (error.columnHeader() == null ? "" : error.columnHeader()) + error.code()));
        return List.copyOf(errors);
    }

    private static String leafFieldName(Path path) {
        String last = null;
        for (Path.Node node : path) {
            if (node.getKind() == jakarta.validation.ElementKind.PROPERTY) {
                last = node.getName();
            }
        }
        return last;
    }

    @Override
    public void close() {
        factory.close();
    }

    /** Интерполятор, всегда использующий заданную локаль вместо локали JVM. */
    private static final class LocaleFixedInterpolator extends ParameterMessageInterpolator {

        private final Locale locale;

        LocaleFixedInterpolator(Locale locale) {
            this.locale = locale;
        }

        @Override
        public String interpolate(String message, Context context) {
            return super.interpolate(message, context, locale);
        }
    }
}
