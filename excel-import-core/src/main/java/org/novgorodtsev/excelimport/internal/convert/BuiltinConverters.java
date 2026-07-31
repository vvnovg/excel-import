package org.novgorodtsev.excelimport.internal.convert;

import org.novgorodtsev.excelimport.convert.CellConverter;
import org.novgorodtsev.excelimport.convert.CellValue;
import org.novgorodtsev.excelimport.convert.ConversionContext;
import org.novgorodtsev.excelimport.convert.ConversionException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/** Встроенные конвертеры. Все экземпляры без состояния и потокобезопасны. */
public final class BuiltinConverters {

    private BuiltinConverters() {}

    // Leaf converters with no dependencies (declared first)
    private static final CellConverter<String> STRING_CONVERTER = (value, ctx) -> ctx.text(value);

    private static final CellConverter<Long> LONG_CONVERTER = (value, ctx) -> {
        Double number = numericOrNull(value, ctx);
        if (number != null) {
            return toExactLong(number, ctx);
        }
        String text = ctx.text(value);
        if (text == null) {
            return null;
        }
        try {
            return Long.valueOf(cleanNumber(text));
        } catch (NumberFormatException e) {
            throw new ConversionException(
                    "NOT_INTEGER", "«" + text + "» не является целым числом", e);
        }
    };

    private static final CellConverter<BigDecimal> BIG_DECIMAL_CONVERTER = (value, ctx) -> {
        String text = ctx.text(value);
        if (text == null) {
            return null;
        }
        try {
            return new BigDecimal(cleanNumber(text).replace(',', '.'));
        } catch (NumberFormatException e) {
            throw new ConversionException(
                    "NOT_NUMBER", "«" + text + "» не является числом", e);
        }
    };

    private static final CellConverter<Boolean> BOOLEAN_CONVERTER = (value, ctx) -> {
        Boolean direct = value.asBoolean();
        if (direct != null) {
            return direct;
        }
        String text = ctx.text(value);
        if (text == null) {
            return null;
        }
        Boolean parsed = ctx.booleanWords().parse(text);
        if (parsed == null) {
            throw new ConversionException(
                    "NOT_BOOLEAN",
                    "«" + text + "» не распознано как логическое значение; допустимо: "
                            + ctx.booleanWords().trueWords() + " / "
                            + ctx.booleanWords().falseWords());
        }
        return parsed;
    };

    private static final CellConverter<LocalDateTime> LOCAL_DATE_TIME_CONVERTER =
            (value, ctx) -> {
                LocalDateTime serial = value.asLocalDateTime();
                if (serial != null) {
                    return serial;
                }
                String text = ctx.text(value);
                if (text == null) {
                    return null;
                }
                return parseTemporal(text, ctx, LocalDateTime::parse, "дату и время");
            };

    private static final CellConverter<LocalDate> LOCAL_DATE_CONVERTER = (value, ctx) -> {
        LocalDateTime serial = value.asLocalDateTime();
        if (serial != null) {
            return serial.toLocalDate();
        }
        String text = ctx.text(value);
        if (text == null) {
            return null;
        }
        return parseTemporal(text, ctx, LocalDate::parse, "дату");
    };

    private static final CellConverter<LocalTime> LOCAL_TIME_CONVERTER = (value, ctx) -> {
        LocalDateTime serial = value.asLocalDateTime();
        if (serial != null) {
            return serial.toLocalTime();
        }
        String text = ctx.text(value);
        if (text == null) {
            return null;
        }
        return parseTemporal(text, ctx, LocalTime::parse, "время");
    };

    private static final CellConverter<UUID> UUID_CONVERTER = (value, ctx) -> {
        String text = ctx.text(value);
        if (text == null) {
            return null;
        }
        try {
            return UUID.fromString(text);
        } catch (IllegalArgumentException e) {
            throw new ConversionException("NOT_UUID", "«" + text + "» не является UUID", e);
        }
    };

    // Converters that delegate to other converters (declared after their dependencies)
    private static final CellConverter<Integer> INTEGER_CONVERTER = (value, ctx) -> {
        Long parsed = LONG_CONVERTER.convert(value, ctx);
        if (parsed == null) {
            return null;
        }
        if (parsed < Integer.MIN_VALUE || parsed > Integer.MAX_VALUE) {
            throw new ConversionException(
                    "OUT_OF_RANGE", parsed + " не укладывается в 32-битное целое");
        }
        return parsed.intValue();
    };

    private static final CellConverter<Double> DOUBLE_CONVERTER = (value, ctx) -> {
        BigDecimal parsed = BIG_DECIMAL_CONVERTER.convert(value, ctx);
        return parsed == null ? null : parsed.doubleValue();
    };

    private static final CellConverter<Float> FLOAT_CONVERTER = (value, ctx) -> {
        BigDecimal parsed = BIG_DECIMAL_CONVERTER.convert(value, ctx);
        return parsed == null ? null : parsed.floatValue();
    };

    private static final CellConverter<Short> SHORT_CONVERTER = (value, ctx) -> {
        Integer parsed = INTEGER_CONVERTER.convert(value, ctx);
        if (parsed == null) {
            return null;
        }
        if (parsed < Short.MIN_VALUE || parsed > Short.MAX_VALUE) {
            throw new ConversionException(
                    "OUT_OF_RANGE", parsed + " не укладывается в 16-битное целое");
        }
        return parsed.shortValue();
    };

    private static final CellConverter<OffsetDateTime> OFFSET_DATE_TIME_CONVERTER =
            (value, ctx) -> {
                LocalDateTime local = LOCAL_DATE_TIME_CONVERTER.convert(value, ctx);
                return local == null ? null : local.atOffset(ZoneOffset.UTC);
            };

    public static CellConverter<String> stringConverter() {
        return STRING_CONVERTER;
    }

    public static CellConverter<Long> longConverter() {
        return LONG_CONVERTER;
    }

    public static CellConverter<Integer> integerConverter() {
        return INTEGER_CONVERTER;
    }

    public static CellConverter<Short> shortConverter() {
        return SHORT_CONVERTER;
    }

    public static CellConverter<BigDecimal> bigDecimalConverter() {
        return BIG_DECIMAL_CONVERTER;
    }

    public static CellConverter<Double> doubleConverter() {
        return DOUBLE_CONVERTER;
    }

    public static CellConverter<Float> floatConverter() {
        return FLOAT_CONVERTER;
    }

    public static CellConverter<Boolean> booleanConverter() {
        return BOOLEAN_CONVERTER;
    }

    public static CellConverter<LocalDate> localDateConverter() {
        return LOCAL_DATE_CONVERTER;
    }

    public static CellConverter<LocalDateTime> localDateTimeConverter() {
        return LOCAL_DATE_TIME_CONVERTER;
    }

    public static CellConverter<LocalTime> localTimeConverter() {
        return LOCAL_TIME_CONVERTER;
    }

    public static CellConverter<OffsetDateTime> offsetDateTimeConverter() {
        return OFFSET_DATE_TIME_CONVERTER;
    }

    public static CellConverter<UUID> uuidConverter() {
        return UUID_CONVERTER;
    }

    public static <E extends Enum<E>> CellConverter<E> enumConverter(Class<E> enumType) {
        return (value, ctx) -> {
            String text = ctx.text(value);
            if (text == null) {
                return null;
            }
            for (E constant : enumType.getEnumConstants()) {
                if (constant.name().equalsIgnoreCase(text) || constant.toString().equalsIgnoreCase(text)) {
                    return constant;
                }
            }
            throw new ConversionException(
                    "NOT_IN_ENUM",
                    "«" + text + "» не входит в допустимые значения: "
                            + java.util.Arrays.toString(enumType.getEnumConstants()));
        };
    }

    private static Double numericOrNull(CellValue value, ConversionContext ctx) {
        if (value.type() == org.apache.poi.ss.usermodel.CellType.ERROR) {
            ctx.text(value); // бросит CELL_ERROR
        }
        return value.asNumeric();
    }

    private static Long toExactLong(double number, ConversionContext ctx) {
        if (number != Math.rint(number)) {
            throw new ConversionException(
                    "NOT_INTEGER",
                    "в колонке «" + ctx.columnDisplayName() + "» ожидалось целое, получено " + number);
        }
        return (long) number;
    }

    private static String cleanNumber(String text) {
        // убираем разделители разрядов: обычный пробел, неразрывный, узкий неразрывный, апостроф
        return text.replace(" ", "")
                .replace(" ", "")
                .replace("'", "")
                .replace(" ", "");
    }

    private interface TemporalParser<V> {
        V parse(CharSequence text, DateTimeFormatter formatter);
    }

    private static <V> V parseTemporal(
            String text, ConversionContext ctx, TemporalParser<V> parser, String what) {
        List<String> formats = ctx.formats();
        if (formats.isEmpty()) {
            try {
                return parser.parse(text, DateTimeFormatter.ISO_LOCAL_DATE_TIME);
            } catch (DateTimeParseException ignored) {
                try {
                    return parser.parse(text, DateTimeFormatter.ISO_LOCAL_DATE);
                } catch (DateTimeParseException e) {
                    throw new ConversionException(
                            "NOT_A_DATE",
                            "«" + text + "» не распознано как " + what
                                    + "; укажите формат в @ExcelColumn(formats = ...)",
                            e);
                }
            }
        }
        for (String pattern : formats) {
            try {
                return parser.parse(text, DateTimeFormatter.ofPattern(pattern, localeOf(ctx)));
            } catch (DateTimeParseException | IllegalArgumentException ignored) {
                // пробуем следующий формат
            }
        }
        throw new ConversionException(
                "NOT_A_DATE",
                "«" + text + "» не соответствует ни одному из форматов " + formats);
    }

    private static Locale localeOf(ConversionContext ctx) {
        return ctx.locale() == null ? Locale.ROOT : ctx.locale();
    }
}
