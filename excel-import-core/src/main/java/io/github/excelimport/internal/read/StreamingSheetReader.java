package io.github.excelimport.internal.read;

import io.github.excelimport.SheetSelector;
import java.nio.file.Path;
import java.util.function.Consumer;

/** Потоковое чтение одного листа .xlsx без загрузки книги в память. */
public interface StreamingSheetReader {

    /**
     * Читает лист и вызывает {@code handler} на каждую строку в порядке возрастания
     * номера. Исключение из {@code handler} пробрасывается наружу, ресурсы при этом
     * закрываются.
     */
    void forEachRow(Path source, SheetSelector selector, ReadOptions options, Consumer<RawRow> handler);
}
