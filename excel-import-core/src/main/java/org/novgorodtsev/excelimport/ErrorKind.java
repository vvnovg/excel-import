package org.novgorodtsev.excelimport;

/** Категория ошибки — определяет, на каком слое она возникла. */
public enum ErrorKind {

    /** Структура файла: нет листа, нет обязательной колонки, дубли заголовков. */
    STRUCTURE,
    /** Не удалось преобразовать значение ячейки в тип поля. */
    CONVERSION,
    /** Нарушено ограничение Jakarta Bean Validation. */
    CONSTRAINT,
    /** Ошибка от пользовательского BatchValidator. */
    BATCH,
    /** Ошибка, вернувшаяся из PostgreSQL. */
    DATABASE
}
