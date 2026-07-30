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
}
