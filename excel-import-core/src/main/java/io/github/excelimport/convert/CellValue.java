package io.github.excelimport.convert;

import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.util.CellAddress;

/**
 * Значение одной ячейки в терминах POI, но без объекта {@code Cell}: на первом проходе
 * чтение идёт через SAX и объектной модели книги не существует (см. §2.2 спеки).
 */
public interface CellValue {

    /** Адрес ячейки, например {@code C7}. */
    CellAddress address();

    /** Тип ячейки. Для пустой ячейки — {@link CellType#BLANK}. */
    CellType type();

    /** true, если стиль ячейки — формат даты (результат {@code DateUtil.isADateFormat}). */
    boolean dateFormatted();

    /** Текстовое представление; null для пустой ячейки. */
    String asString();

    /** Числовое значение; null, если ячейка не числовая. */
    Double asNumeric();

    /** Логическое значение; null, если ячейка не логическая. */
    Boolean asBoolean();

    /** Текст формулы без ведущего знака равенства; null, если ячейка не формула. */
    String formula();

    /**
     * Код ошибки Excel (см. {@code org.apache.poi.ss.usermodel.FormulaError}).
     * Значим только при {@link #type()} == {@link CellType#ERROR}, иначе -1.
     */
    byte errorCode();

    default boolean isBlank() {
        return type() == CellType.BLANK;
    }

    static CellValue blank(CellAddress address) {
        return new io.github.excelimport.internal.read.ImmutableCellValue(
                address, CellType.BLANK, false, null, null, null, null, (byte) -1);
    }
}
