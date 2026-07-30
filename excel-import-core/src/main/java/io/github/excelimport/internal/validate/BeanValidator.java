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
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.hibernate.validator.HibernateValidator;
import org.hibernate.validator.messageinterpolation.AbstractMessageInterpolator;
import org.hibernate.validator.messageinterpolation.ParameterMessageInterpolator;
import org.hibernate.validator.resourceloading.PlatformResourceBundleLocator;

/**
 * Обёртка над Jakarta Bean Validation. Сообщения интерполируются в заданной локали,
 * имена полей переводятся в заголовки Excel-колонок.
 *
 * <p>Интерполяция сообщений идёт без EL-движка (в classpath намеренно нет реализации
 * {@code jakarta.el}): плейсхолдеры вида {@code {параметр}} подставляются штатно, а
 * выражения {@code ${...}} не вычисляются и остаются в сообщении как есть. Если
 * потребителю нужен кастомный constraint с сообщением на {@code ${...}}, ему придётся
 * самостоятельно добавить реализацию {@code jakarta.el} в свой classpath и настроить
 * соответствующий {@code MessageInterpolator} — библиотека этого не делает.
 */
public final class BeanValidator implements AutoCloseable {

    /**
     * Базовое имя бандла сообщений библиотеки. Специально не {@code ValidationMessages} —
     * это имя по умолчанию для JSR-380, и приложение-потребитель почти наверняка использует
     * его для своих сообщений. {@code ResourceBundle} резолвит бандл целиком, а не
     * по ключам, поэтому у двух одноимённых бандлов на classpath один обязательно
     * "проигрывает" полностью — совпадение имён недопустимо.
     */
    private static final String LIBRARY_BUNDLE_NAME = "io.github.excelimport.ValidationMessages";

    /**
     * Порядок нарушений в {@code Set}, который возвращает Bean Validation, не определён —
     * сортируем сравнением полей по отдельности (не конкатенацией строк: она может дать
     * одинаковый ключ для разных пар header/code, например header="A"+code="BC" и
     * header="AB"+code="C" дают одну и ту же строку "ABC"). {@code columnHeader} может быть
     * {@code null} у ошибок уровня класса — такие сортируются первыми. {@code message} —
     * финальный тай-брейкер на случай совпадения и header, и code: без него компаратор не был
     * бы тотальным и порядок при полном совпадении первых двух полей остался бы
     * недетерминированным.
     */
    private static final Comparator<RowError> ERROR_ORDER = Comparator
            .comparing(RowError::columnHeader, Comparator.nullsFirst(Comparator.naturalOrder()))
            .thenComparing(RowError::code)
            .thenComparing(RowError::message);

    private final ValidatorFactory factory;
    private final Validator validator;
    private final Map<String, String> fieldToHeader;

    public BeanValidator(Locale locale, MappingModel<?> model) {
        this.factory = Validation.byProvider(HibernateValidator.class)
                .configure()
                .messageInterpolator(new ChainedParameterMessageInterpolator(locale))
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
        // порядок нарушений в Set не определён — сортируем для предсказуемости отчёта.
        // Сравниваем поля по отдельности (а не конкатенацией строк, которая может дать
        // одинаковый ключ для разных пар header/code), с message как финальным
        // тай-брейкером — компаратор тотален, т.е. не оставляет пар нарушений, порядок
        // между которыми не определён.
        errors.sort(ERROR_ORDER);
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

    /**
     * Интерполятор без EL-движка (см. Javadoc класса) с приоритетом бандлов сообщений:
     * сперва бандл потребителя {@value AbstractMessageInterpolator#USER_VALIDATION_MESSAGES}
     * (если он есть на classpath приложения), затем бандл библиотеки
     * {@link #LIBRARY_BUNDLE_NAME}, и только потом встроенный бандл Hibernate Validator
     * ({@value AbstractMessageInterpolator#DEFAULT_VALIDATION_MESSAGES}) — так свои сообщения
     * не остаются без перевода, но и не перекрывают сообщения потребителя.
     *
     * <p>Разрешение ключа бандла и подстановка {@code {параметр}} — две раздельные фазы:
     * свои локаторы бандлов передаются в конструктор {@link AbstractMessageInterpolator}
     * (это разрешает сам ключ, например {@code {jakarta.validation.constraints.NotBlank.message}},
     * по цепочке из двух локаторов; третий, дефолтный уровень Hibernate Validator добавляет
     * сама библиотека автоматически), а фактическая подстановка параметров без EL
     * делегируется штатному {@link ParameterMessageInterpolator} — библиотека не изобретает
     * собственную логику интерполяции, а лишь комбинирует два официальных класса Hibernate
     * Validator в нужном порядке.
     */
    private static final class ChainedParameterMessageInterpolator extends AbstractMessageInterpolator {

        private final Locale locale;
        private final ParameterMessageInterpolator parameterInterpolator;

        ChainedParameterMessageInterpolator(Locale locale) {
            super(new PlatformResourceBundleLocator(USER_VALIDATION_MESSAGES),
                    new PlatformResourceBundleLocator(LIBRARY_BUNDLE_NAME));
            this.locale = locale;
            this.parameterInterpolator = new ParameterMessageInterpolator();
        }

        @Override
        public String interpolate(String message, Context context) {
            return super.interpolate(message, context, locale);
        }

        @Override
        protected String interpolate(Context context, Locale locale, String resolvedMessage) {
            return parameterInterpolator.interpolate(resolvedMessage, context, locale);
        }
    }
}
