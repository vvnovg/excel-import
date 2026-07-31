package org.novgorodtsev.excelimport.internal.map;

import org.novgorodtsev.excelimport.RowError;
import org.novgorodtsev.excelimport.convert.BooleanWords;
import org.novgorodtsev.excelimport.convert.CellConverter;
import org.novgorodtsev.excelimport.convert.CellValue;
import org.novgorodtsev.excelimport.convert.ConversionContext;
import org.novgorodtsev.excelimport.convert.ConversionException;
import org.novgorodtsev.excelimport.internal.convert.ConverterRegistry;
import org.novgorodtsev.excelimport.internal.read.RawRow;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Превращает {@link RawRow} в объект модели. Создаётся один раз на прогон импорта
 * после разбора заголовка и переиспользуется для всех строк.
 */
public final class RowMapper<T> {

    /** Заранее подготовленный план заполнения одного поля. */
    private record FieldPlan(
            ColumnBinding binding, int columnIndex, CellConverter<?> converter, ConversionContext context) {}

    private final MappingModel<T> model;
    private final List<FieldPlan> plans;

    public RowMapper(
            MappingModel<T> model,
            ResolvedColumns resolved,
            ConverterRegistry converters,
            Locale locale,
            BooleanWords booleanWords) {
        this.model = model;
        List<FieldPlan> prepared = new ArrayList<>(resolved.entries().size());
        for (ResolvedColumns.Entry entry : resolved.entries()) {
            ColumnBinding binding = entry.binding();
            ConversionContext context = new ConversionContext(
                    binding.displayName(),
                    binding.fieldType(),
                    binding.formats(),
                    binding.trim(),
                    binding.emptyAsNull(),
                    locale,
                    booleanWords);
            prepared.add(new FieldPlan(binding, entry.columnIndex(), converters.resolve(binding), context));
        }
        this.plans = List.copyOf(prepared);
    }

    public MappingResult<T> map(RawRow row) {
        T instance = model.newInstance();
        List<RowError> errors = new ArrayList<>(0);

        for (FieldPlan plan : plans) {
            CellValue cell = row.cell(plan.columnIndex());
            try {
                Object converted = plan.converter().convert(cell, plan.context());
                if (converted != null || !plan.binding().fieldType().isPrimitive()) {
                    plan.binding().setter().invoke(instance, converted);
                }
            } catch (ConversionException e) {
                errors.add(RowError.conversion(
                        row.excelRowNumber(),
                        plan.binding().displayName(),
                        cell.asString(),
                        e.code(),
                        e.getMessage()));
            } catch (Throwable e) {
                // Пробросить JVM-level ошибки вместо превращения их в ошибки строк
                if (e instanceof Error) {
                    throw (Error) e;
                }
                errors.add(RowError.conversion(
                        row.excelRowNumber(),
                        plan.binding().displayName(),
                        cell.asString(),
                        "SETTER_FAILED",
                        "не удалось записать значение в поле " + plan.binding().fieldName() + ": "
                                + e.getMessage()));
            }
        }
        return new MappingResult<>(instance, errors);
    }
}
