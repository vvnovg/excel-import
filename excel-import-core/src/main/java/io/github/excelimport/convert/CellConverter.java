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

    /**
     * Значение-заглушка (sentinel) для атрибута {@code converter()} аннотации
     * {@code @ExcelColumn}: означает «явный конвертер не задан — выбрать по типу поля».
     *
     * <p>Существует только для того, чтобы {@code converter()} мог остаться корректно
     * параметризованным ({@code Class<? extends CellConverter<?>>}) и при этом иметь
     * литерал класса в качестве значения по умолчанию — приём аналогичен
     * {@code JsonSerializer.None} из Jackson. Сама заглушка не участвует в конвертации;
     * когда в этот интерфейс добавят метод конвертации, {@code None} не должен получить
     * реализацию — распознавание «конвертер не задан» остаётся сравнением класса с
     * {@code CellConverter.None.class}.
     */
    final class None implements CellConverter<Object> {
        private None() {}
    }
}
