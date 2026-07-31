package org.novgorodtsev.excelimport.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** Лист книги и расположение заголовка. Индексы 0-based. */
@Documented
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface ExcelSheet {

    /** Имя листа. Задаётся либо {@code name}, либо {@code index}, но не оба. */
    String name() default "";

    /** 0-based индекс листа. -1 означает «не задан». */
    int index() default -1;

    /** 0-based индекс строки заголовка. */
    int headerRow() default 0;

    /** 0-based индекс первой строки данных. -1 означает {@code headerRow + 1}. */
    int firstDataRow() default -1;
}
