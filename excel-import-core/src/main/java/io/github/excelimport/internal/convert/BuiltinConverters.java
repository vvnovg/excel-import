package io.github.excelimport.internal.convert;

import io.github.excelimport.convert.CellConverter;
import io.github.excelimport.convert.CellValue;
import io.github.excelimport.convert.ConversionContext;
import io.github.excelimport.convert.ConversionException;
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

    public static CellConverter<String> stringConverter() {
        return (value, ctx) -> ctx.text(value);
    }

    public static CellConverter<Long> longConverter() {
        return (value, ctx) -> {
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
    }

    public static CellConverter<Integer> integerConverter() {
        return (value, ctx) -> {
            Long parsed = longConverter().convert(value, ctx);
            if (parsed == null) {
                return null;
            }
            if (parsed < Integer.MIN_VALUE || parsed > Integer.MAX_VALUE) {
                throw new ConversionException(
                        "OUT_OF_RANGE", parsed + " не укладывается в 32-битное целое");
            }
            return parsed.intValue();
        };
    }

    public static CellConverter<Short> shortConverter() {
        return (value, ctx) -> {
            Integer parsed = integerConverter().convert(value, ctx);
            if (parsed == null) {
                return null;
            }
            if (parsed < Short.MIN_VALUE || parsed > Short.MAX_VALUE) {
                throw new ConversionException(
                        "OUT_OF_RANGE", parsed + " не укладывается в 16-битное целое");
            }
            return parsed.shortValue();
        };
    }

    public static CellConverter<BigDecimal> bigDecimalConverter() {
        return (value, ctx) -> {
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
    }

    public static CellConverter<Double> doubleConverter() {
        return (value, ctx) -> {
            BigDecimal parsed = bigDecimalConverter().convert(value, ctx);
            return parsed == null ? null : parsed.doubleValue();
        };
    }

    public static CellConverter<Float> floatConverter() {
        return (value, ctx) -> {
            BigDecimal parsed = bigDecimalConverter().convert(value, ctx);
            return parsed == null ? null : parsed.floatValue();
        };
    }

    public static CellConverter<Boolean> booleanConverter() {
        return (value, ctx) -> {
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
    }

    public static CellConverter<LocalDate> localDateConverter() {
        return (value, ctx) -> {
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
    }

    public static CellConverter<LocalDateTime> localDateTimeConverter() {
        return (value, ctx) -> {
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
    }

    public static CellConverter<LocalTime> localTimeConverter() {
        return (value, ctx) -> {
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
    }

    public static CellConverter<OffsetDateTime> offsetDateTimeConverter() {
        return (value, ctx) -> {
            LocalDateTime local = localDateTimeConverter().convert(value, ctx);
            return local == null ? null : local.atOffset(ZoneOffset.UTC);
        };
    }

    public static CellConverter<UUID> uuidConverter() {
        return (value, ctx) -> {
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
