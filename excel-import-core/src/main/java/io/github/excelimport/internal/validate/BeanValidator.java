package io.github.excelimport.internal.validate;

import io.github.excelimport.RowError;
import io.github.excelimport.internal.map.ColumnBinding;
import io.github.excelimport.internal.map.MappingModel;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.MessageInterpolator;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import jakarta.validation.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.ResourceBundle;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;
import org.hibernate.validator.HibernateValidator;
import org.hibernate.validator.messageinterpolation.AbstractMessageInterpolator;
import org.hibernate.validator.messageinterpolation.ParameterMessageInterpolator;
import org.hibernate.validator.resourceloading.PlatformResourceBundleLocator;
import org.hibernate.validator.spi.resourceloading.ResourceBundleLocator;

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
     * Обёртка над нарушением, добавляющая финальный тай-брейкер поверх видимых полей
     * {@link RowError}: сигнатуру атрибутов constraint'а (аннотации), отсортированную по
     * имени атрибута. Нужна, потому что header+code+message не всегда тотальны: два
     * повторных constraint'а одного типа на одном поле (например,
     * {@code @Min.List({@Min(value = 0, message = "..."), @Min(value = -5, message = "...")})}
     * с ОДИНАКОВЫМ, не зависящим от {@code {value}} текстом {@code message()}) дают
     * одинаковые header, code И message — а поскольку это одно и то же поле одного и того же
     * бина, {@code rawValue} тоже совпадёт — но РАЗНЫЕ атрибуты самой аннотации (здесь —
     * {@code value=0} против {@code value=-5}). {@code RowError} не хранит атрибуты
     * constraint'а (и не должен — это внутренняя деталь сортировки, а не часть публичного
     * отчёта об ошибке), поэтому пара (RowError, сигнатура атрибутов) существует только
     * внутри {@link #validate(Object, int)} и никогда не покидает этот класс.
     *
     * <p>Тай-брейкер строится ИЗ САМИХ атрибутов constraint'а, а не из порядка появления
     * нарушения в {@code Set<ConstraintViolation>}, который возвращает Bean Validation, —
     * последний не определён спецификацией, так что использовать его (даже просто
     * "запомнить, в каком порядке я их увидел") не даёт никакой гарантии воспроизводимости
     * между запусками. Сигнатура атрибутов, наоборот, зависит только от объявления
     * constraint'а в коде — она одна и та же при каждом запуске.
     *
     * <p>Компаратор всё ещё формально может вернуть 0 для двух РАЗНЫХ экземпляров
     * {@code ConstraintViolation}, если constraint объявлен дважды с абсолютно одинаковыми
     * атрибутами (вырожденный случай). Но тогда результирующие {@code RowError} тоже
     * совпадают по всем видимым полям (тот же header, code, message, rawValue — поле-то одно
     * и то же), то есть неразличимы для потребителя отчёта, и порядок между ними объективно
     * не наблюдаем.
     *
     * <p>Видимость package-private (не {@code private}) намеренная: {@code BeanValidatorTest}
     * тестирует {@link #VIOLATION_ORDER} напрямую на вручную сконструированных
     * {@code Violation} — единственный способ реально понаблюдать эффект финального
     * тай-брейкера, поскольку два {@code RowError} с одинаковыми header/code/message,
     * порождённые из одного и того же поля, неизбежно совпадают и по {@code rawValue} тоже
     * (см. выше) и потому неразличимы через сравнение самих {@code RowError}.
     */
    record Violation(RowError error, String attributeFingerprint) {}

    /**
     * Порядок нарушений в {@code Set}, который возвращает Bean Validation, не определён —
     * сортируем сравнением полей по отдельности (не конкатенацией строк: она может дать
     * одинаковый ключ для разных пар header/code, например header="A"+code="BC" и
     * header="AB"+code="C" дают одну и ту же строку "ABC"). {@code columnHeader} может быть
     * {@code null} у ошибок уровня класса — такие сортируются первыми. {@code message} —
     * следующий тай-брейкер на случай совпадения и header, и code. Финальный тай-брейкер —
     * {@code attributeFingerprint} (см. {@link Violation}) — делает компаратор тотальным даже
     * при совпадении header, code И message.
     */
    static final Comparator<Violation> VIOLATION_ORDER = Comparator
            .comparing((Violation v) -> v.error().columnHeader(), Comparator.nullsFirst(Comparator.naturalOrder()))
            .thenComparing(v -> v.error().code())
            .thenComparing(v -> v.error().message())
            .thenComparing(Violation::attributeFingerprint);

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
        List<Violation> collected = new ArrayList<>(violations.size());
        for (ConstraintViolation<Object> violation : violations) {
            String field = leafFieldName(violation.getPropertyPath());
            String header = field == null ? null : fieldToHeader.get(field);
            String code = violation.getConstraintDescriptor().getAnnotation()
                    .annotationType()
                    .getSimpleName();
            String rawValue = violation.getInvalidValue() == null
                    ? null
                    : String.valueOf(violation.getInvalidValue());
            RowError error = RowError.constraint(rowNum, header, rawValue, code, violation.getMessage());
            collected.add(new Violation(error, attributeFingerprint(violation)));
        }
        // порядок нарушений в Set не определён — сортируем для предсказуемости отчёта.
        // Сравниваем поля по отдельности (а не конкатенацией строк, которая может дать
        // одинаковый ключ для разных пар header/code), с message и, наконец, сигнатурой
        // атрибутов constraint'а (см. Violation) как тай-брейкерами — компаратор тотален,
        // т.е. не оставляет пар нарушений, порядок между которыми не определён.
        collected.sort(VIOLATION_ORDER);
        return collected.stream().map(Violation::error).toList();
    }

    /**
     * @return атрибуты аннотации constraint'а (кроме их видимых-через-RowError следствий),
     *     отсортированные по имени и сериализованные в детерминированную строку — см.
     *     {@link Violation}.
     */
    private static String attributeFingerprint(ConstraintViolation<?> violation) {
        return new TreeMap<>(violation.getConstraintDescriptor().getAttributes())
                .entrySet()
                .stream()
                .map(entry -> entry.getKey() + "=" + describeAttributeValue(entry.getValue()))
                .collect(Collectors.joining(","));
    }

    private static String describeAttributeValue(Object value) {
        // groups()/payload() — это Class<?>[]; Object[]#toString() не детерминирован между
        // запусками (использует identityHashCode), Arrays.deepToString — детерминирован.
        return value instanceof Object[] array ? Arrays.deepToString(array) : String.valueOf(value);
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
     * <p>Разрешение ключа бандла (например, {@code {jakarta.validation.constraints.Min.message}}
     * целиком) и подстановка отдельных {@code {параметр}} внутри уже резолвленного текста —
     * две принципиально разные операции, и это единственная причина, по которой класс не
     * просто расширяет {@link AbstractMessageInterpolator} с готовыми локаторами (как выглядело
     * бы естественно), а реализует {@link MessageInterpolator} напрямую с собственным,
     * НЕрекурсивным резолвом ключа.
     *
     * <p><b>Почему нельзя использовать {@code AbstractMessageInterpolator} "как есть", даже
     * с правильными локаторами.</b> Его приватный {@code resolveMessage} (проверено
     * декомпиляцией hibernate-validator-8.0.5.Final) резолвит ключ бандла не один раз, а
     * итеративно: получив из бандла текст {@code "значение должно быть не меньше {value}"},
     * он на СЛЕДУЮЩЕЙ итерации того же цикла снова токенизирует результат и пытается
     * резолвить уже {@code "{value}"} как ключ бандла — против ровно того же {@code
     * userResourceBundleLocator}, что и настоящий ключ сообщения. Эта рекурсия — штатная
     * JSR-380-фича (позволяет одному ключу бандла ссылаться на другой), но она не отличает
     * "ссылку на другой ключ" от "плейсхолдера параметра", у которых один и тот же синтаксис
     * {@code {...}}. Из-за этого, если потребитель определит в СВОЁМ корневом
     * {@code ValidationMessages(_ru).properties} голый ключ {@code value} (совпадающий по
     * имени с JSR-380-плейсхолдером {@code @Min}/{@code @Max}), эта рекурсия молча
     * подставит его текст ВМЕСТО настоящего предела — причём это происходит уже в самом
     * ПЕРВОМ (корректно сконфигурированном) проходе резолва ключа, до того, как вообще
     * вызывается любой хук подстановки параметров. Так что оборачивание в
     * {@code ParameterMessageInterpolator} с правильными локаторами (даже если бы у него был
     * такой конструктор — см. ниже) не помогло бы: тот же самый унаследованный
     * {@code resolveMessage} эту рекурсию всё равно бы выполнил.
     *
     * <p>Поэтому резолв ключа сообщения здесь реализован самостоятельно, БЕЗ рекурсии:
     * {@link #resolveMessageKey(String, Locale)} трактует как "ключ бандла" только сообщение,
     * целиком состоящее из одного {@code {...}}-терма (ровно так выглядят все стандартные
     * JSR-380 message templates), резолвит его РОВНО ОДИН РАЗ по цепочке из трёх бандлов и
     * никогда не резолвит бандл повторно против уже подставленного текста. Оставшиеся
     * {@code {параметр}}-термы в результате (например, {@code {value}}) заведомо не могут
     * быть спутаны со следующим резолвом ключа — они идут напрямую в {@link TermInterpolator}.
     *
     * <p><b>Почему подстановка параметров не идёт через публичный API
     * {@code ParameterMessageInterpolator}.</b> {@code ParameterMessageInterpolator}
     * (проверено декомпиляцией) не предоставляет конструктор, принимающий
     * {@code ResourceBundleLocator} — только {@code (Set<Locale>, Locale, boolean)}
     * и {@code (Set<Locale>, Locale, LocaleResolver, boolean)}; его публичный
     * {@code interpolate(String, Context[, Locale])} унаследован от
     * {@code AbstractMessageInterpolator} и не переопределён, так что вызов ЭТОГО метода на
     * готовом {@code ParameterMessageInterpolator} заново запустил бы тот же самый
     * бандл-резолвящий {@code resolveMessage}, но уже на локаторах по умолчанию (простое,
     * не-namespaced имя {@code ValidationMessages}) — тот же класс уязвимости, только по
     * другому пути. {@link TermInterpolator} вместо этого вызывает защищённый
     * {@code interpolate(Context, Locale, String)} — саму логику подстановки параметра —
     * НАПРЯМУЮ через {@code super} из настоящего подкласса, вообще не проходя ни через один
     * публичный метод {@code AbstractMessageInterpolator}. Каким конструктором создан
     * {@code TermInterpolator}, значения не имеет: этот защищённый метод (см. декомпилированный
     * байткод) не читает ни одного поля, заданного через конструктор — он лишь проверяет
     * {@code InterpolationTerm.isElExpression(term)} и делегирует {@code ParameterTermResolver}.
     *
     * <p><b>Экранирование и {@code ${...}} в {@link #substituteParameters(String, Context,
     * Locale)}.</b> Стандартный {@code AbstractMessageInterpolator} трактует {@code \{}, {@code
     * \}}, {@code \\} и {@code \$} как литеральные экранированные символы (снимает экранирование
     * один раз, в самом конце), а {@code {...}}, которому непосредственно предшествует
     * неэкранированный {@code $}, — как EL-выражение, а не как {@code {параметр}}-терм. Обе
     * особенности значимы: сообщение из кастомного constraint'а вида {@code "диапазон
     * \{0-10\}"} должно отрендериться как {@code "диапазон {0-10}"} (буквальные скобки, без
     * обратных слэшей), а {@code "${value}"} на {@code @Min}/{@code @Max} (чей JSR-380-атрибут
     * называется буквально {@code value}) не должен подставлять числовой предел вместо
     * невычисляемого EL-выражения — иначе нарушается задокументированный в Javadoc класса
     * контракт "EL не вычисляется и остаётся как есть". {@link #substituteParameters(String,
     * Context, Locale)} поэтому сканирует сообщение посимвольно (а не одним regex-проходом):
     * экранирующая обратная косая черта распознаётся до попытки распознать терм, поэтому
     * {@code \{} и {@code \}} никогда не открывают/закрывают терм; {@code {...}} с
     * предшествующим неэкранированным {@code $} копируется в результат буквально, включая сам
     * {@code $}, целиком нетронутым; обычный {@code {параметр}}-терм подставляется как раньше,
     * через {@link TermInterpolator}. Снятие экранирования происходит непосредственно при
     * копировании литерального символа в результат — то есть только для символов исходного
     * шаблона сообщения, а не для текста, который вернула подстановка параметра (тот
     * копируется в результат как есть, без повторного снятия экранирования).
     */
    private static final class ChainedParameterMessageInterpolator implements MessageInterpolator {

        private static final ResourceBundleLocator USER_BUNDLE_LOCATOR =
                new PlatformResourceBundleLocator(AbstractMessageInterpolator.USER_VALIDATION_MESSAGES);
        private static final ResourceBundleLocator LIBRARY_BUNDLE_LOCATOR =
                new PlatformResourceBundleLocator(LIBRARY_BUNDLE_NAME);
        private static final ResourceBundleLocator HV_BUILTIN_BUNDLE_LOCATOR =
                new PlatformResourceBundleLocator(AbstractMessageInterpolator.DEFAULT_VALIDATION_MESSAGES);

        /** Порядок резолва бандлов: потребитель → библиотека → встроенный бандл Hibernate Validator. */
        private static final List<ResourceBundleLocator> BUNDLE_LOCATORS =
                List.of(USER_BUNDLE_LOCATOR, LIBRARY_BUNDLE_LOCATOR, HV_BUILTIN_BUNDLE_LOCATOR);

        private static final TermInterpolator TERM_INTERPOLATOR = new TermInterpolator();

        private final Locale locale;

        ChainedParameterMessageInterpolator(Locale locale) {
            this.locale = locale;
        }

        @Override
        public String interpolate(String messageTemplate, Context context) {
            return interpolate(messageTemplate, context, locale);
        }

        @Override
        public String interpolate(String messageTemplate, Context context, Locale locale) {
            String resolved = resolveMessageKey(messageTemplate, locale);
            return substituteParameters(resolved, context, locale);
        }

        /**
         * Резолвит {@code messageTemplate} РОВНО ОДИН РАЗ, если оно целиком является одним
         * {@code {ключ}}-термом (как все message templates стандартных constraint'ов), по
         * цепочке потребитель → библиотека → встроенный бандл Hibernate Validator. Никогда не
         * резолвит бандл повторно против результата — см. Javadoc класса.
         */
        private static String resolveMessageKey(String messageTemplate, Locale locale) {
            String key = soleBundleKey(messageTemplate);
            if (key == null) {
                return messageTemplate;
            }
            for (ResourceBundleLocator locator : BUNDLE_LOCATORS) {
                ResourceBundle bundle = locator.getResourceBundle(locale);
                if (bundle != null && bundle.containsKey(key)) {
                    return bundle.getString(key);
                }
            }
            return messageTemplate;
        }

        /**
         * @return содержимое единственного {@code {...}}, если {@code message} целиком —
         *     один такой терм без ничего вокруг и без вложенных скобок внутри; иначе
         *     {@code null} (это не ссылка на ключ бандла, а буквальный текст сообщения,
         *     возможно, с параметрами внутри).
         */
        private static String soleBundleKey(String message) {
            if (message.length() < 3 || message.charAt(0) != '{' || message.charAt(message.length() - 1) != '}') {
                return null;
            }
            String inner = message.substring(1, message.length() - 1);
            return inner.indexOf('{') < 0 && inner.indexOf('}') < 0 ? inner : null;
        }

        /**
         * Посимвольно сканирует {@code message}, заменяя каждый {@code {параметр}}-терм через
         * {@link TermInterpolator}, оставляя {@code ${...}}-EL-выражения нетронутыми и снимая
         * экранирование {@code \{}, {@code \}}, {@code \\}, {@code \$} — см. Javadoc класса.
         */
        private static String substituteParameters(String message, Context context, Locale locale) {
            if (message.indexOf('{') < 0 && message.indexOf('\\') < 0) {
                return message;
            }
            int length = message.length();
            StringBuilder result = new StringBuilder(length);
            int i = 0;
            while (i < length) {
                char c = message.charAt(i);
                if (c == '\\' && i + 1 < length && isEscapable(message.charAt(i + 1))) {
                    result.append(message.charAt(i + 1));
                    i += 2;
                    continue;
                }
                if (c == '$' && i + 1 < length && message.charAt(i + 1) == '{') {
                    int termEnd = findTermEnd(message, i + 1);
                    if (termEnd >= 0) {
                        // EL-выражение: не вычисляем (в classpath намеренно нет jakarta.el —
                        // см. Javadoc класса), не подставляем параметр — копируем "${...}" как есть.
                        result.append(message, i, termEnd);
                        i = termEnd;
                        continue;
                    }
                }
                if (c == '{') {
                    int termEnd = findTermEnd(message, i);
                    if (termEnd >= 0) {
                        String term = message.substring(i, termEnd);
                        result.append(TERM_INTERPOLATOR.interpolateTerm(context, locale, term));
                        i = termEnd;
                        continue;
                    }
                }
                result.append(c);
                i++;
            }
            return result.toString();
        }

        private static boolean isEscapable(char c) {
            return c == '{' || c == '}' || c == '\\' || c == '$';
        }

        /**
         * @param message текст сообщения
         * @param openBraceIndex индекс символа {@code '{'}, с которого начинается терм
         * @return индекс СЛЕДУЮЩЕГО символа после закрывающей {@code '}'} терма, если между
         *     {@code openBraceIndex} и ближайшей {@code '}'} нет ни одной вложенной {@code '{'}
         *     (тот же терм, что раньше матчился регулярным выражением {@code \{[^{}]*}});
         *     иначе {@code -1} — это не терм, символ {@code '{'} нужно скопировать буквально.
         */
        private static int findTermEnd(String message, int openBraceIndex) {
            int closeIndex = message.indexOf('}', openBraceIndex + 1);
            if (closeIndex < 0) {
                return -1;
            }
            for (int j = openBraceIndex + 1; j < closeIndex; j++) {
                if (message.charAt(j) == '{') {
                    return -1;
                }
            }
            return closeIndex + 1;
        }

        /**
         * Existing solely to expose {@link ParameterMessageInterpolator}'s protected
         * {@code interpolate(Context, Locale, String)} — the actual {@code {param}}
         * substitution logic — to a class outside its package, by calling it through
         * {@code super} from a real subclass rather than through any public entry point.
         * Calling it this way means {@code AbstractMessageInterpolator}'s public
         * {@code interpolate(String, Context[, Locale])} — the bundle-key-resolution
         * entry points — are never invoked on this instance at all.
         */
        private static final class TermInterpolator extends ParameterMessageInterpolator {
            String interpolateTerm(Context context, Locale locale, String term) {
                return super.interpolate(context, locale, term);
            }
        }
    }
}
