package io.github.excelimport.internal.validate;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.excelimport.ErrorKind;
import io.github.excelimport.NamingStrategy;
import io.github.excelimport.annotation.ExcelColumn;
import io.github.excelimport.annotation.ExcelSheet;
import io.github.excelimport.internal.map.MappingModel;
import io.github.excelimport.internal.map.MappingModelFactory;
import jakarta.validation.Constraint;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import jakarta.validation.Payload;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class BeanValidatorTest {

    @Target(ElementType.TYPE)
    @Retention(RetentionPolicy.RUNTIME)
    @Constraint(validatedBy = HireBeforeFireValidator.class)
    @interface HireBeforeFire {
        String message() default "Дата увольнения раньше даты приёма";

        Class<?>[] groups() default {};

        Class<? extends Payload>[] payload() default {};
    }

    public static class HireBeforeFireValidator implements ConstraintValidator<HireBeforeFire, Employee> {
        @Override
        public boolean isValid(Employee value, ConstraintValidatorContext context) {
            if (value.hiredAt == null || value.firedAt == null) {
                return true;
            }
            return !value.firedAt.isBefore(value.hiredAt);
        }
    }

    @ExcelSheet(name = "S")
    @HireBeforeFire
    static class Employee {
        @ExcelColumn(header = "ФИО")
        @NotBlank
        @Size(max = 5)
        String fullName;

        @ExcelColumn(header = "Стаж")
        @NotNull
        @Min(0)
        Integer years;

        @ExcelColumn(header = "Дата приёма")
        LocalDate hiredAt;

        @ExcelColumn(header = "Дата увольнения")
        LocalDate firedAt;
    }

    private final MappingModel<Employee> model =
            MappingModelFactory.create(Employee.class, NamingStrategy.SNAKE_CASE);
    private final BeanValidator validator = new BeanValidator(Locale.forLanguageTag("ru"), model);

    @AfterEach
    void tearDown() {
        validator.close();
    }

    @Test
    void validBeanProducesNoErrors() {
        Employee bean = new Employee();
        bean.fullName = "Иван";
        bean.years = 3;

        assertThat(validator.validate(bean, 7)).isEmpty();
    }

    @Test
    void fieldViolationIsMappedToExcelColumnHeader() {
        Employee bean = new Employee();
        bean.fullName = "";
        bean.years = 3;

        List<io.github.excelimport.RowError> errors = validator.validate(bean, 7);

        assertThat(errors).singleElement().satisfies(error -> {
            assertThat(error.kind()).isEqualTo(ErrorKind.CONSTRAINT);
            assertThat(error.columnHeader()).isEqualTo("ФИО");
            assertThat(error.rowNum()).isEqualTo(7);
            assertThat(error.code()).isEqualTo("NotBlank");
        });
    }

    @Test
    void allViolationsAreCollected() {
        Employee bean = new Employee();
        bean.fullName = "СлишкомДлинноеИмя";
        bean.years = -1;

        assertThat(validator.validate(bean, 7)).hasSize(2);
    }

    @Test
    void nullFieldTriggersNotNull() {
        Employee bean = new Employee();
        bean.fullName = "Иван";
        bean.years = null;

        assertThat(validator.validate(bean, 7)).singleElement()
                .satisfies(error -> {
                    assertThat(error.code()).isEqualTo("NotNull");
                    // years is genuinely null (there is no invalid value to report, as
                    // opposed to an offending value that happens to stringify to "null")
                    assertThat(error.rawValue()).isNull();
                });
    }

    @Test
    void rawValueCarriesOffendingValueAsText() {
        Employee bean = new Employee();
        bean.fullName = "Иван";
        bean.years = -3;

        assertThat(validator.validate(bean, 7)).singleElement()
                .satisfies(error -> {
                    assertThat(error.code()).isEqualTo("Min");
                    assertThat(error.rawValue()).isEqualTo("-3");
                });
    }

    @Test
    void classLevelViolationHasNoColumnHeader() {
        Employee bean = new Employee();
        bean.fullName = "Иван";
        bean.years = 3;
        bean.hiredAt = LocalDate.of(2026, 7, 1);
        bean.firedAt = LocalDate.of(2026, 6, 1);

        assertThat(validator.validate(bean, 7)).singleElement().satisfies(error -> {
            assertThat(error.columnHeader()).isNull();
            assertThat(error.code()).isEqualTo("HireBeforeFire");
            assertThat(error.message()).contains("раньше даты приёма");
        });
    }

    @Test
    void validatorIsReusableAndThreadSafeForSequentialCalls() {
        Employee first = new Employee();
        first.fullName = "Иван";
        first.years = 1;
        Employee second = new Employee();
        second.years = 1;

        assertThat(validator.validate(first, 1)).isEmpty();
        assertThat(validator.validate(second, 2)).hasSize(1);
    }

    // --- Finding 1: bundle precedence -----------------------------------------------

    /**
     * {@code Min} is a key the library's bundle defines but the test-classpath consumer
     * fixture ({@code src/test/resources/ValidationMessages_ru.properties}) does not
     * override — proves the library's bundle is genuinely consulted (as the fallback
     * tier) rather than every key silently disappearing once a consumer bundle exists.
     */
    @Test
    void libraryBundleResolvesKeyTheConsumerDoesNotOverride() {
        Employee bean = new Employee();
        bean.fullName = "Иван";
        bean.years = -1;

        assertThat(validator.validate(bean, 7)).singleElement()
                .satisfies(error -> {
                    assertThat(error.code()).isEqualTo("Min");
                    assertThat(error.message()).isEqualTo("значение должно быть не меньше 0");
                });
    }

    /**
     * {@code NotBlank} is overridden by the consumer-style fixture on the test classpath
     * ({@code src/test/resources/ValidationMessages_ru.properties}). If the library's
     * bundle took precedence (or the two bundles collided the way a single flat
     * {@code ValidationMessages} name would), this would see the library's text instead.
     */
    @Test
    void consumerBundleTakesPrecedenceOverLibraryBundleForSameKey() {
        Employee bean = new Employee();
        bean.fullName = "";
        bean.years = 3;

        assertThat(validator.validate(bean, 7)).singleElement()
                .satisfies(error -> {
                    assertThat(error.code()).isEqualTo("NotBlank");
                    assertThat(error.message())
                            .isEqualTo("потребительское сообщение: значение не должно быть пустым");
                });
    }

    /**
     * {@code src/test/resources/ValidationMessages_ru.properties} defines a bare key
     * {@code value} — named after the JSR-380 {@code {value}} placeholder used by
     * {@code @Min}'s message, not after any real JSR-380 message key. If the interpolator's
     * placeholder-substitution hook re-enters full bundle-key resolution a second time (on a
     * delegate whose locators default to the plain, unnamespaced root {@code ValidationMessages}
     * bundle — exactly the fixture above), that second lookup finds {@code value} as a "message
     * key" and substitutes its bogus text for the real numeric limit. The correctly-fixed
     * interpolator performs only parameter substitution for the term and never re-resolves the
     * bundle at all, so this must still render the real limit.
     */
    @Test
    void placeholderTermIsNotReResolvedAgainstConsumerBundle() {
        Employee bean = new Employee();
        bean.fullName = "Иван";
        bean.years = -1;

        assertThat(validator.validate(bean, 7)).singleElement()
                .satisfies(error -> {
                    assertThat(error.code()).isEqualTo("Min");
                    assertThat(error.message()).isEqualTo("значение должно быть не меньше 0");
                });
    }

    // --- Finding 2: total, collision-proof sort order --------------------------------

    @Target(ElementType.FIELD)
    @Retention(RetentionPolicy.RUNTIME)
    @Constraint(validatedBy = AlwaysInvalidBc.class)
    @interface BC {
        String message() default "bc";

        Class<?>[] groups() default {};

        Class<? extends Payload>[] payload() default {};
    }

    public static class AlwaysInvalidBc implements ConstraintValidator<BC, Object> {
        @Override
        public boolean isValid(Object value, ConstraintValidatorContext context) {
            return false;
        }
    }

    @Target(ElementType.FIELD)
    @Retention(RetentionPolicy.RUNTIME)
    @Constraint(validatedBy = AlwaysInvalidC.class)
    @interface C {
        String message() default "c";

        Class<?>[] groups() default {};

        Class<? extends Payload>[] payload() default {};
    }

    public static class AlwaysInvalidC implements ConstraintValidator<C, Object> {
        @Override
        public boolean isValid(Object value, ConstraintValidatorContext context) {
            return false;
        }
    }

    @Target(ElementType.TYPE)
    @Retention(RetentionPolicy.RUNTIME)
    @Constraint(validatedBy = AlwaysInvalidAbc.class)
    @interface ABC {
        String message() default "abc";

        Class<?>[] groups() default {};

        Class<? extends Payload>[] payload() default {};
    }

    public static class AlwaysInvalidAbc implements ConstraintValidator<ABC, Object> {
        @Override
        public boolean isValid(Object value, ConstraintValidatorContext context) {
            return false;
        }
    }

    @ExcelSheet(name = "Collision")
    @ABC
    static class HeaderCodeCollisionBean {
        @ExcelColumn(header = "A")
        @BC
        String first = "x";

        @ExcelColumn(header = "AB")
        @C
        String second = "y";
    }

    /**
     * (header=null, code="ABC"), (header="A", code="BC") and (header="AB", code="C") all
     * concatenate to the same "ABC" — the old
     * {@code Comparator.comparing(header + code)} could not tell these three violations
     * apart and fell back to the undefined iteration order of the underlying {@code Set}
     * (verified: with the old comparator restored, this test fails reproducibly in this
     * environment — {@code null}+"ABC" does not sort first). The fixed comparator orders
     * by header (nulls first), then code, so the order below is pinned regardless of
     * iteration order.
     */
    @Test
    void sortOrderIsDeterministicEvenWhenConcatenatedHeaderAndCodeCollide() {
        MappingModel<HeaderCodeCollisionBean> collisionModel =
                MappingModelFactory.create(HeaderCodeCollisionBean.class, NamingStrategy.SNAKE_CASE);
        try (BeanValidator collisionValidator = new BeanValidator(Locale.forLanguageTag("ru"), collisionModel)) {
            List<io.github.excelimport.RowError> errors =
                    collisionValidator.validate(new HeaderCodeCollisionBean(), 3);

            assertThat(errors).hasSize(3);
            assertThat(errors.get(0).columnHeader()).isNull();
            assertThat(errors.get(0).code()).isEqualTo("ABC");
            assertThat(errors.get(1).columnHeader()).isEqualTo("A");
            assertThat(errors.get(1).code()).isEqualTo("BC");
            assertThat(errors.get(2).columnHeader()).isEqualTo("AB");
            assertThat(errors.get(2).code()).isEqualTo("C");
        }
    }

    @ExcelSheet(name = "DuplicateMin")
    static class DuplicateSameMessageBean {
        @ExcelColumn(header = "Число")
        @Min.List({
            @Min(value = 0, message = "недопустимое значение"),
            @Min(value = -5, message = "недопустимое значение")
        })
        int number = -10;
    }

    /**
     * Two {@code @Min} constraints on the SAME field, both violated by the same value, with a
     * literal (not {@code {value}}-based) {@code message()} that is identical for both. This
     * makes header (same field), code (both "Min"), message (identical literal text) — AND
     * rawValue (same field's actual value, "-10" both times) — all collide between the two
     * resulting {@code RowError}s. That last point matters: it means the two RowErrors are
     * indistinguishable through any field visible on {@code RowError} — they are `.equals()`
     * to each other — so this test can only confirm the pipeline produces exactly the two
     * expected (content-identical-looking) rows without throwing; it cannot observe which
     * physical violation ended up first, since swapping two equal elements doesn't change
     * list equality. The actual ordering claim is pinned separately, directly against the
     * comparator, in {@link #violationOrderIsTotalForRealisticHeaderCodeMessageCollision()}.
     */
    @Test
    void duplicateConstraintsCollidingOnHeaderCodeMessageAndRawValueAreAllCollected() {
        MappingModel<DuplicateSameMessageBean> duplicateModel =
                MappingModelFactory.create(DuplicateSameMessageBean.class, NamingStrategy.SNAKE_CASE);
        try (BeanValidator duplicateValidator = new BeanValidator(Locale.forLanguageTag("ru"), duplicateModel)) {
            List<io.github.excelimport.RowError> errors =
                    duplicateValidator.validate(new DuplicateSameMessageBean(), 9);

            assertThat(errors).hasSize(2);
            assertThat(errors).allSatisfy(error -> {
                assertThat(error.columnHeader()).isEqualTo("Число");
                assertThat(error.code()).isEqualTo("Min");
                assertThat(error.message()).isEqualTo("недопустимое значение");
                assertThat(error.rawValue()).isEqualTo("-10");
            });
        }
    }

    /**
     * Direct comparator test (per the task's escape hatch: "if you cannot construct that case
     * with real constraints, [...] test the comparator directly"). The real-constraints case
     * above proves the pipeline doesn't crash and collects both violations, but — as explained
     * there — it cannot observe ordering, because the two resulting {@code RowError}s are
     * `.equals()`. This test instead wraps the SAME {@code RowError} instance in two {@code
     * Violation}s with different {@code attributeFingerprint}s (standing in for two constraints
     * whose own attributes — e.g. {@code @Min}'s {@code value=-5} vs {@code value=0} — differ
     * even though header/code/message coincide) and asserts: (1) the comparator never returns 0
     * for the two distinct fingerprints — it is total; (2) the relative order is a fixed
     * function of the fingerprints themselves, not of argument/call order or object identity —
     * i.e. reproducible, unlike a tiebreaker derived from {@code Set} iteration order would be.
     */
    @Test
    void violationOrderIsTotalForRealisticHeaderCodeMessageCollision() {
        io.github.excelimport.RowError shared =
                io.github.excelimport.RowError.constraint(9, "Число", "-10", "Min", "недопустимое значение");
        BeanValidator.Violation lowerValue =
                new BeanValidator.Violation(shared, "groups=[],message=недопустимое значение,payload=[],value=-5");
        BeanValidator.Violation higherValue =
                new BeanValidator.Violation(shared, "groups=[],message=недопустимое значение,payload=[],value=0");

        assertThat(BeanValidator.VIOLATION_ORDER.compare(lowerValue, higherValue)).isNegative();
        assertThat(BeanValidator.VIOLATION_ORDER.compare(higherValue, lowerValue)).isPositive();
        assertThat(BeanValidator.VIOLATION_ORDER.compare(lowerValue, lowerValue)).isZero();
    }

    // --- Regression 1: escaped braces must not be dropped ----------------------------

    @ExcelSheet(name = "Escape")
    static class EscapedBracesBean {
        @ExcelColumn(header = "Код")
        @Pattern(regexp = "[0-9]+", message = "диапазон \\{0-10\\}")
        String code = "abc";
    }

    /**
     * {@code "диапазон \{0-10\}"} is a literal constraint message meaning
     * {@code "диапазон {0-10}"} — the backslashes are stock Hibernate Validator escape syntax
     * for a literal brace, not a real {@code {parameter}} term (there is no constraint
     * attribute named {@code 0-10}). Before the fix, {@code PARAMETER_TERM} matched
     * {@code \{0-10\}} as a term anyway (it never checked for a preceding backslash), found no
     * such attribute, left it unchanged — WITH the backslashes still attached, so the consumer
     * would see stray {@code \} characters in the rendered message instead of literal braces.
     */
    @Test
    void escapedBracesRenderLiterallyWithoutStrayBackslashes() {
        MappingModel<EscapedBracesBean> escapeModel =
                MappingModelFactory.create(EscapedBracesBean.class, NamingStrategy.SNAKE_CASE);
        try (BeanValidator escapeValidator = new BeanValidator(Locale.forLanguageTag("ru"), escapeModel)) {
            List<io.github.excelimport.RowError> errors =
                    escapeValidator.validate(new EscapedBracesBean(), 1);

            assertThat(errors).singleElement()
                    .satisfies(error -> assertThat(error.message()).isEqualTo("диапазон {0-10}"));
        }
    }

    // --- Regression 2: ${...} must not be mistaken for a parameter --------------------

    @ExcelSheet(name = "ElVsParam")
    static class ElExpressionBean {
        @ExcelColumn(header = "Возраст")
        @Min.List({
            @Min(value = 3, message = "лимит: ${value}"),
            @Min(value = 7, message = "лимит: {value}")
        })
        int age = 0;
    }

    /**
     * Two sibling {@code @Min} constraints on the same field, both violated by {@code age = 0}:
     * one with an EL-looking message ({@code ${value}}, limit 3), one with a plain parameter
     * message ({@code {value}}, limit 7). The class's documented contract is that {@code
     * ${...}} is never evaluated and stays exactly as written, while {@code {параметр}} is
     * substituted. Using two DIFFERENT limits (3 vs 7) makes the two outcomes distinguishable:
     * if the bug were still present, the leading {@code $} would stay in the literal text and
     * {@code {value}} would be matched as a real parameter term, substituting {@code @Min}'s
     * own {@code value} attribute — rendering {@code "лимит: $3"} instead of the untouched
     * {@code "лимит: ${value}"}. The sibling message proves plain substitution still works
     * (must render {@code "лимит: 7"}), so this test cannot pass merely because "nothing got
     * substituted" — it proves the EL/parameter distinction specifically.
     */
    @Test
    void elExpressionIsLeftLiteralWhilePlainParameterStillSubstitutes() {
        MappingModel<ElExpressionBean> elModel =
                MappingModelFactory.create(ElExpressionBean.class, NamingStrategy.SNAKE_CASE);
        try (BeanValidator elValidator = new BeanValidator(Locale.forLanguageTag("ru"), elModel)) {
            List<io.github.excelimport.RowError> errors = elValidator.validate(new ElExpressionBean(), 1);

            assertThat(errors).hasSize(2);
            assertThat(errors).extracting(io.github.excelimport.RowError::message)
                    .containsExactlyInAnyOrder("лимит: ${value}", "лимит: 7");
        }
    }
}
