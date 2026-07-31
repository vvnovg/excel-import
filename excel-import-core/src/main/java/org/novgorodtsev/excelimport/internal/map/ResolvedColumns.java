package org.novgorodtsev.excelimport.internal.map;

import java.util.List;
import java.util.Optional;

/** Привязки колонок с уже определёнными индексами в конкретном файле. */
public record ResolvedColumns(List<Entry> entries) {

    public ResolvedColumns {
        entries = List.copyOf(entries);
    }

    public record Entry(ColumnBinding binding, int columnIndex) {}

    public Optional<Integer> indexOf(String fieldName) {
        return entries.stream()
                .filter(entry -> entry.binding().fieldName().equals(fieldName))
                .map(Entry::columnIndex)
                .findFirst();
    }
}
