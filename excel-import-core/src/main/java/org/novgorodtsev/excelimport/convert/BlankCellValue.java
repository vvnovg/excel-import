package org.novgorodtsev.excelimport.convert;

import java.time.LocalDateTime;
import java.util.Objects;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.util.CellAddress;

/**
 * Реализация пустой ячейки. Используется как замена для конструирования
 * пустых {@code CellValue} в публичном API, чтобы избежать зависимости от
 * внутреннего пакета {@code org.novgorodtsev.excelimport.internal}.
 */
record BlankCellValue(CellAddress address) implements CellValue {
    public BlankCellValue {
        Objects.requireNonNull(address, "address");
    }

    @Override
    public CellType type() {
        return CellType.BLANK;
    }

    @Override
    public boolean dateFormatted() {
        return false;
    }

    @Override
    public String asString() {
        return null;
    }

    @Override
    public Double asNumeric() {
        return null;
    }

    @Override
    public Boolean asBoolean() {
        return null;
    }

    @Override
    public String formula() {
        return null;
    }

    @Override
    public byte errorCode() {
        return -1;
    }

    @Override
    public LocalDateTime asLocalDateTime() {
        return null;
    }
}
