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
                .satisfies(error -> assertThat(error.code()).isEqualTo("NotNull"));
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
}
