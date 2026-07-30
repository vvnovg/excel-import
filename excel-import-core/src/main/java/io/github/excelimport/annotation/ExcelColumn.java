package io.github.excelimport.annotation;

import io.github.excelimport.convert.CellConverter;
import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** Привязка поля к колонке Excel. Колонка задаётся ровно одним из: header, index, letter. */
@Documented
@Target(ElementType.FIELD)
@Retention(RetentionPolicy.RUNTIME)
public @interface ExcelColumn {

    /** Текст в строке заголовка. */
    String header() default "";

    /** 0-based индекс колонки. -1 означает «не задан». */
    int index() default -1;

    /** Буква колонки, например {@code "A"} или {@code "AB"}. */
    String letter() default "";

    /** Если true, отсутствие колонки в файле — фатальная ошибка структуры. */
    boolean required() default true;

    /** Форматы для разбора дат и чисел, пробуются по порядку. */
    String[] formats() default {};

    /** Обрезать пробелы по краям. */
    boolean trim() default true;

    /** Пустую строку считать null. */
    boolean emptyAsNull() default true;

    /** Свой конвертер. {@code CellConverter.None.class} означает «выбрать по типу поля». */
    Class<? extends CellConverter<?>> converter() default CellConverter.None.class;
}
