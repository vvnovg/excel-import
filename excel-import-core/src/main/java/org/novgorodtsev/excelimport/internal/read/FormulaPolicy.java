package org.novgorodtsev.excelimport.internal.read;

/** Что делать с формулой, у которой в файле нет кэшированного результата. */
public enum FormulaPolicy {

    /** Считать ячейку пустой. */
    AS_NULL,
    /** Породить ошибку конвертации для этой ячейки. */
    AS_ERROR,
    /** Подставить текст формулы как строковое значение. */
    AS_FORMULA_TEXT
}
