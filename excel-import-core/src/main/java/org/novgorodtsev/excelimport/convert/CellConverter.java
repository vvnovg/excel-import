package org.novgorodtsev.excelimport.convert;

/**
 * Преобразует значение ячейки в значение поля. Реализации должны быть потокобезопасны
 * и не хранить состояние между вызовами: один экземпляр используется на весь импорт.
 *
 * <p>Для отсутствующего значения возвращают {@code null}, а не бросают исключение.
 * Обязательность проверяется на слое Jakarta-валидации ({@code @NotNull}).
 *
 * @param <V> тип значения поля после конвертации
 */
public interface CellConverter<V> {

    /**
     * @throws ConversionException если значение непусто, но не приводится к целевому типу
     */
    V convert(CellValue value, ConversionContext ctx);

    /**
     * Значение-заглушка (sentinel) для атрибута {@code converter()} аннотации
     * {@code @ExcelColumn}: означает «явный конвертер не задан — выбрать по типу поля».
     *
     * <p>Существует только для того, чтобы {@code converter()} мог остаться корректно
     * параметризованным ({@code Class<? extends CellConverter<?>>}) и при этом иметь
     * литерал класса в качестве значения по умолчанию — приём аналогичен
     * {@code JsonSerializer.None} из Jackson. Сама заглушка не участвует в конвертации;
     * распознавание «конвертер не задан» остаётся сравнением класса с
     * {@code CellConverter.None.class}.
     */
    final class None implements CellConverter<Object> {
        private None() {}

        @Override
        public Object convert(CellValue value, ConversionContext ctx) {
            throw new UnsupportedOperationException(
                    "CellConverter.None — это заглушка для значения по умолчанию "
                            + "@ExcelColumn.converter(); она никогда не вызывается для конвертации");
        }
    }
}
