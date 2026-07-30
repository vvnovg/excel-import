package io.github.excelimport.internal.read;

import io.github.excelimport.convert.CellValue;
import java.util.Objects;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.util.CellAddress;

/** Неизменяемая реализация {@link CellValue}. */
public record ImmutableCellValue(
        CellAddress address,
        CellType type,
        boolean dateFormatted,
        String stringValue,
        Double numericValue,
        Boolean booleanValue,
        String formulaText,
        byte errorCode)
        implements CellValue {

    public ImmutableCellValue {
        Objects.requireNonNull(address, "address");
        Objects.requireNonNull(type, "type");
    }

    @Override
    public String asString() {
        return stringValue;
    }

    @Override
    public Double asNumeric() {
        return numericValue;
    }

    @Override
    public Boolean asBoolean() {
        return booleanValue;
    }

    @Override
    public String formula() {
        return formulaText;
    }
}
