package io.github.excelimport.internal.map;

import io.github.excelimport.HeaderMatchingPolicy;
import io.github.excelimport.exception.FileStructureException;
import io.github.excelimport.internal.read.RawRow;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Определяет, в каких колонках файла лежат поля модели. */
public final class HeaderResolver {

    private HeaderResolver() {}

    public static ResolvedColumns resolve(
            MappingModel<?> model, RawRow headerRow, HeaderMatchingPolicy policy) {
        Map<String, Integer> headerIndexes = indexHeaders(headerRow, policy);
        List<ResolvedColumns.Entry> entries = new ArrayList<>();
        List<String> missing = new ArrayList<>();

        for (ColumnBinding binding : model.excelColumns()) {
            if (binding.columnIndex() != null) {
                entries.add(new ResolvedColumns.Entry(binding, binding.columnIndex()));
                continue;
            }
            Integer index = headerIndexes.get(policy.normalize(binding.headerName()));
            if (index != null) {
                entries.add(new ResolvedColumns.Entry(binding, index));
            } else if (binding.required()) {
                missing.add(binding.headerName());
            }
        }

        if (!missing.isEmpty()) {
            throw new FileStructureException(
                    "в строке заголовка не найдены обязательные колонки: " + missing
                            + "; фактические заголовки: " + headerTexts(headerRow));
        }
        return new ResolvedColumns(entries);
    }

    private static Map<String, Integer> indexHeaders(RawRow headerRow, HeaderMatchingPolicy policy) {
        Map<String, Integer> indexes = new HashMap<>();
        Set<String> duplicates = new HashSet<>();
        for (int column = 0; column <= headerRow.lastColumnIndex(); column++) {
            String text = headerRow.cell(column).asString();
            if (text == null || text.isBlank()) {
                continue;
            }
            String normalized = policy.normalize(text);
            if (indexes.putIfAbsent(normalized, column) != null) {
                duplicates.add(normalized);
            }
        }
        if (!duplicates.isEmpty()) {
            throw new FileStructureException(
                    "заголовки повторяются, сопоставление неоднозначно: " + duplicates);
        }
        return indexes;
    }

    private static List<String> headerTexts(RawRow headerRow) {
        List<String> texts = new ArrayList<>();
        for (int column = 0; column <= headerRow.lastColumnIndex(); column++) {
            String text = headerRow.cell(column).asString();
            if (text != null && !text.isBlank()) {
                texts.add(text);
            }
        }
        return texts;
    }
}
