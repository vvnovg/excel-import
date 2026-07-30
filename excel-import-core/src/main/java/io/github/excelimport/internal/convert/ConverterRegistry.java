package io.github.excelimport.internal.convert;

import io.github.excelimport.convert.CellConverter;
import io.github.excelimport.exception.MappingConfigurationException;
import io.github.excelimport.internal.map.ColumnBinding;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Подбирает конвертер для привязки: явный из {@code @ExcelColumn(converter = ...)},
 * иначе зарегистрированный для типа поля, иначе встроенный.
 */
public final class ConverterRegistry {

    private final Map<Class<?>, CellConverter<?>> byType = new HashMap<>();
    private final Map<Class<?>, CellConverter<?>> customInstances = new ConcurrentHashMap<>();

    public ConverterRegistry() {
        byType.put(String.class, BuiltinConverters.stringConverter());
        byType.put(Long.class, BuiltinConverters.longConverter());
        byType.put(long.class, BuiltinConverters.longConverter());
        byType.put(Integer.class, BuiltinConverters.integerConverter());
        byType.put(int.class, BuiltinConverters.integerConverter());
        byType.put(Short.class, BuiltinConverters.shortConverter());
        byType.put(short.class, BuiltinConverters.shortConverter());
        byType.put(BigDecimal.class, BuiltinConverters.bigDecimalConverter());
        byType.put(Double.class, BuiltinConverters.doubleConverter());
        byType.put(double.class, BuiltinConverters.doubleConverter());
        byType.put(Float.class, BuiltinConverters.floatConverter());
        byType.put(float.class, BuiltinConverters.floatConverter());
        byType.put(Boolean.class, BuiltinConverters.booleanConverter());
        byType.put(boolean.class, BuiltinConverters.booleanConverter());
        byType.put(LocalDate.class, BuiltinConverters.localDateConverter());
        byType.put(LocalDateTime.class, BuiltinConverters.localDateTimeConverter());
        byType.put(LocalTime.class, BuiltinConverters.localTimeConverter());
        byType.put(OffsetDateTime.class, BuiltinConverters.offsetDateTimeConverter());
        byType.put(UUID.class, BuiltinConverters.uuidConverter());
    }

    /** Регистрирует конвертер для типа поля, перекрывая встроенный. */
    public ConverterRegistry register(Class<?> targetType, CellConverter<?> converter) {
        byType.put(targetType, converter);
        return this;
    }

    public CellConverter<?> resolve(ColumnBinding binding) {
        Class<? extends CellConverter<?>> explicit = binding.converterType();
        if (explicit != null && explicit != CellConverter.None.class) {
            return customInstances.computeIfAbsent(explicit, ConverterRegistry::instantiate);
        }
        Class<?> fieldType = binding.fieldType();
        CellConverter<?> registered = byType.get(fieldType);
        if (registered != null) {
            return registered;
        }
        if (fieldType.isEnum()) {
            return customInstances.computeIfAbsent(
                    fieldType, type -> enumConverterFor(type));
        }
        throw new MappingConfigurationException(
                "нет конвертера для типа " + fieldType.getName() + " (поле " + binding.fieldName()
                        + "); укажите converter в @ExcelColumn или зарегистрируйте свой");
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static CellConverter<?> enumConverterFor(Class<?> enumType) {
        return BuiltinConverters.enumConverter((Class) enumType);
    }

    private static CellConverter<?> instantiate(Class<?> converterType) {
        try {
            return (CellConverter<?>) converterType.getDeclaredConstructor().newInstance();
        } catch (ReflectiveOperationException e) {
            throw new MappingConfigurationException(
                    "у конвертера " + converterType.getName()
                            + " должен быть публичный конструктор без аргументов",
                    e);
        }
    }
}
