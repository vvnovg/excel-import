package io.github.excelimport.convert;

/**
 * Конвертер значения ячейки Excel в значение поля типа {@code V}.
 *
 * <p>Метод конвертации определяется отдельной задачей вместе с {@code ConversionContext};
 * здесь объявлена только форма типа, необходимая для атрибута {@code converter()}
 * аннотации {@code @ExcelColumn} и поля {@code converterType} в {@code ColumnBinding}.
 *
 * @param <V> тип значения поля после конвертации
 */
public interface CellConverter<V> {
}
