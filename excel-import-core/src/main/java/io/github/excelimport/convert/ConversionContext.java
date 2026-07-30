package io.github.excelimport.convert;

import java.util.List;
import java.util.Locale;
import java.util.Objects;
import org.apache.poi.ss.usermodel.CellType;

/**
 * Контекст одной конвертации: настройки колонки и книги.
 *
 * @param columnDisplayName имя колонки для сообщений об ошибках
 * @param targetType        тип поля
 * @param formats           форматы разбора в порядке приоритета
 * @param trim              обрезать пробелы
 * @param emptyAsNull       пустую строку считать null
 * @param locale            локаль разбора чисел и дат
 * @param booleanWords      словарь логических значений
 */
public record ConversionContext(
        String columnDisplayName,
        Class<?> targetType,
        List<String> formats,
        boolean trim,
        boolean emptyAsNull,
        Locale locale,
        BooleanWords booleanWords) {

    public ConversionContext {
        Objects.requireNonNull(columnDisplayName, "columnDisplayName");
        Objects.requireNonNull(targetType, "targetType");
        formats = List.copyOf(formats);
        Objects.requireNonNull(locale, "locale");
        Objects.requireNonNull(booleanWords, "booleanWords");
    }

    /**
     * Общая предобработка для всех конвертеров: ячейка с ошибкой Excel всегда даёт
     * {@link ConversionException}, затем применяются trim и emptyAsNull.
     *
     * @return текст ячейки или null, если значения нет
     */
    public String text(CellValue value) {
        if (value.type() == CellType.ERROR) {
            throw new ConversionException(
                    "CELL_ERROR", "ячейка содержит ошибку Excel: " + value.asString());
        }
        String raw = value.asString();
        if (raw == null) {
            return null;
        }
        String result = trim ? raw.trim() : raw;
        return emptyAsNull && result.isEmpty() ? null : result;
    }
}
