package io.github.excelimport;

import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;

/** Выбор листа книги: либо по имени, либо по 0-based индексу. */
public final class SheetSelector {

    private final String name;
    private final Integer index;

    private SheetSelector(String name, Integer index) {
        this.name = name;
        this.index = index;
    }

    public static SheetSelector byName(String name) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("имя листа не может быть пустым");
        }
        return new SheetSelector(name, null);
    }

    public static SheetSelector byIndex(int index) {
        if (index < 0) {
            throw new IllegalArgumentException("индекс листа не может быть отрицательным: " + index);
        }
        return new SheetSelector(null, index);
    }

    /** Первый лист книги. */
    public static SheetSelector first() {
        return byIndex(0);
    }

    public Optional<String> name() {
        return Optional.ofNullable(name);
    }

    public OptionalInt index() {
        return index == null ? OptionalInt.empty() : OptionalInt.of(index);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof SheetSelector other)) {
            return false;
        }
        return Objects.equals(name, other.name) && Objects.equals(index, other.index);
    }

    @Override
    public int hashCode() {
        return Objects.hash(name, index);
    }

    @Override
    public String toString() {
        return name != null ? "sheet[name=" + name + "]" : "sheet[index=" + index + "]";
    }
}
