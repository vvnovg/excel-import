package org.novgorodtsev.excelimport.internal.convert;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.novgorodtsev.excelimport.convert.BooleanWords;
import org.novgorodtsev.excelimport.convert.CellConverter;
import org.novgorodtsev.excelimport.convert.CellValue;
import org.novgorodtsev.excelimport.convert.ConversionContext;
import org.novgorodtsev.excelimport.convert.ConversionException;
import org.novgorodtsev.excelimport.internal.read.ImmutableCellValue;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.util.CellAddress;
import org.junit.jupiter.api.Test;

class BuiltinConvertersTest {

    private static CellValue text(String value) {
        return new ImmutableCellValue(
                new CellAddress(0, 0), CellType.STRING, false, value, null, null, null, (byte) -1, false);
    }

    private static CellValue number(double value) {
        return new ImmutableCellValue(
                new CellAddress(0, 0), CellType.NUMERIC, false, String.valueOf(value), value,
                null, null, (byte) -1, false);
    }

    private static CellValue date(double serial) {
        return new ImmutableCellValue(
                new CellAddress(0, 0), CellType.NUMERIC, true, String.valueOf(serial), serial,
                null, null, (byte) -1, false);
    }

    private static ConversionContext ctx(Class<?> targetType, String... formats) {
        return new ConversionContext(
                "Колонка", targetType, List.of(formats), true, true, Locale.forLanguageTag("ru"),
                BooleanWords.defaults());
    }

    @Test
    void stringConverterTrimsAndNullsEmpty() {
        CellConverter<String> converter = BuiltinConverters.stringConverter();

        assertThat(converter.convert(text("  Иванов  "), ctx(String.class))).isEqualTo("Иванов");
        assertThat(converter.convert(text("   "), ctx(String.class))).isNull();
    }

    @Test
    void longConverterAcceptsNumericAndTextCells() {
        CellConverter<Long> converter = BuiltinConverters.longConverter();

        assertThat(converter.convert(number(42), ctx(Long.class))).isEqualTo(42L);
        assertThat(converter.convert(text("1 234"), ctx(Long.class))).isEqualTo(1234L);
        assertThat(converter.convert(text(""), ctx(Long.class))).isNull();
    }

    @Test
    void longConverterRejectsFractionalNumber() {
        assertThatThrownBy(() -> BuiltinConverters.longConverter().convert(number(1.5), ctx(Long.class)))
                .isInstanceOf(ConversionException.class)
                .satisfies(e -> assertThat(((ConversionException) e).code()).isEqualTo("NOT_INTEGER"));
    }

    @Test
    void bigDecimalConverterKeepsScaleFromTextAndHandlesComma() {
        CellConverter<BigDecimal> converter = BuiltinConverters.bigDecimalConverter();

        assertThat(converter.convert(text("1234,50"), ctx(BigDecimal.class)))
                .isEqualByComparingTo("1234.50");
        assertThat(converter.convert(number(1000.5), ctx(BigDecimal.class)))
                .isEqualByComparingTo("1000.5");
    }

    @Test
    void bigDecimalConverterRejectsGarbage() {
        assertThatThrownBy(() ->
                        BuiltinConverters.bigDecimalConverter().convert(text("abc"), ctx(BigDecimal.class)))
                .isInstanceOf(ConversionException.class)
                .hasMessageContaining("число");
    }

    @Test
    void localDateConverterReadsSerialDate() {
        CellConverter<LocalDate> converter = BuiltinConverters.localDateConverter();
        // 46232 — 2026-07-29 в системе 1900
        double serial = org.apache.poi.ss.usermodel.DateUtil.getExcelDate(
                LocalDate.of(2026, 7, 29).atStartOfDay(), false);

        assertThat(converter.convert(date(serial), ctx(LocalDate.class)))
                .isEqualTo(LocalDate.of(2026, 7, 29));
    }

    @Test
    void localDateConverterTriesFormatsInOrder() {
        CellConverter<LocalDate> converter = BuiltinConverters.localDateConverter();
        ConversionContext context = ctx(LocalDate.class, "dd.MM.yyyy", "yyyy-MM-dd");

        assertThat(converter.convert(text("29.07.2026"), context)).isEqualTo(LocalDate.of(2026, 7, 29));
        assertThat(converter.convert(text("2026-07-29"), context)).isEqualTo(LocalDate.of(2026, 7, 29));
    }

    @Test
    void localDateConverterReportsAllTriedFormats() {
        ConversionContext context = ctx(LocalDate.class, "dd.MM.yyyy");

        assertThatThrownBy(() -> BuiltinConverters.localDateConverter().convert(text("29/07/2026"), context))
                .isInstanceOf(ConversionException.class)
                .hasMessageContaining("dd.MM.yyyy");
    }

    @Test
    void localDateTimeConverterReadsSerialWithTime() {
        double serial = org.apache.poi.ss.usermodel.DateUtil.getExcelDate(
                LocalDateTime.of(2026, 7, 29, 13, 45), false);

        assertThat(BuiltinConverters.localDateTimeConverter().convert(date(serial), ctx(LocalDateTime.class)))
                .isEqualTo(LocalDateTime.of(2026, 7, 29, 13, 45));
    }

    @Test
    void booleanConverterUnderstandsRussianWords() {
        CellConverter<Boolean> converter = BuiltinConverters.booleanConverter();

        assertThat(converter.convert(text("Да"), ctx(Boolean.class))).isTrue();
        assertThat(converter.convert(text("нет"), ctx(Boolean.class))).isFalse();
        assertThat(converter.convert(text("1"), ctx(Boolean.class))).isTrue();
    }

    @Test
    void booleanConverterRejectsUnknownWord() {
        assertThatThrownBy(() -> BuiltinConverters.booleanConverter().convert(text("может быть"),
                        ctx(Boolean.class)))
                .isInstanceOf(ConversionException.class)
                .satisfies(e -> assertThat(((ConversionException) e).code()).isEqualTo("NOT_BOOLEAN"));
    }

    enum Status {
        ACTIVE,
        FIRED
    }

    @Test
    void enumConverterMatchesByNameIgnoringCase() {
        CellConverter<?> converter = BuiltinConverters.enumConverter(Status.class);

        assertThat(converter.convert(text("active"), ctx(Status.class))).isEqualTo(Status.ACTIVE);
    }

    @Test
    void enumConverterListsAllowedValuesOnFailure() {
        assertThatThrownBy(() ->
                        BuiltinConverters.enumConverter(Status.class).convert(text("UNKNOWN"), ctx(Status.class)))
                .isInstanceOf(ConversionException.class)
                .hasMessageContaining("ACTIVE")
                .hasMessageContaining("FIRED");
    }

    @Test
    void uuidConverterParsesAndRejects() {
        assertThat(BuiltinConverters.uuidConverter()
                        .convert(text("123e4567-e89b-12d3-a456-426614174000"), ctx(java.util.UUID.class)))
                .hasToString("123e4567-e89b-12d3-a456-426614174000");

        assertThatThrownBy(() ->
                        BuiltinConverters.uuidConverter().convert(text("не uuid"), ctx(java.util.UUID.class)))
                .isInstanceOf(ConversionException.class);
    }

    @Test
    void errorCellIsAlwaysAConversionFailure() {
        CellValue errorCell = new ImmutableCellValue(
                new CellAddress(0, 0), CellType.ERROR, false, "#N/A", null, null, null,
                org.apache.poi.ss.usermodel.FormulaError.NA.getCode(), false);

        assertThatThrownBy(() -> BuiltinConverters.stringConverter().convert(errorCell, ctx(String.class)))
                .isInstanceOf(ConversionException.class)
                .satisfies(e -> assertThat(((ConversionException) e).code()).isEqualTo("CELL_ERROR"))
                .hasMessageContaining("#N/A");
    }
}
