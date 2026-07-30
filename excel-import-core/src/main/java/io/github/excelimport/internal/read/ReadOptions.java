package io.github.excelimport.internal.read;

import java.util.Objects;

/**
 * Настройки чтения листа.
 *
 * @param skipBlankRows      пропускать полностью пустые строки
 * @param expandMergedCells  размножать значение объединённой ячейки на весь диапазон
 * @param formulaPolicy      поведение для формулы без кэшированного результата
 */
public record ReadOptions(
        boolean skipBlankRows, boolean expandMergedCells, FormulaPolicy formulaPolicy) {

    public ReadOptions {
        Objects.requireNonNull(formulaPolicy, "formulaPolicy");
    }

    public static ReadOptions defaults() {
        return new ReadOptions(true, true, FormulaPolicy.AS_NULL);
    }
}
