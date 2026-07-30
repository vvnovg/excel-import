package io.github.excelimport.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** Таблица-приёмник. Может быть переопределена в ImportConfig. */
@Documented
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface TargetTable {

    /** Схема; пустая строка означает search_path. */
    String schema() default "";

    String name();
}
