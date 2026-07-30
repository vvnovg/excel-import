package io.github.excelimport.internal.map;

import io.github.excelimport.RowError;
import java.util.List;

/**
 * Результат маппинга одной строки. Объект возвращается даже при ошибках — частично
 * заполненный, он нужен отчёту и логам.
 */
public record MappingResult<T>(T value, List<RowError> errors) {

    public MappingResult {
        errors = List.copyOf(errors);
    }

    public boolean isValid() {
        return errors.isEmpty();
    }
}
