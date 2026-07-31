package org.novgorodtsev.excelimport.internal.map;

import org.novgorodtsev.excelimport.NamingStrategy;
import org.novgorodtsev.excelimport.SheetSelector;
import org.novgorodtsev.excelimport.TableRef;
import org.novgorodtsev.excelimport.annotation.Column;
import org.novgorodtsev.excelimport.annotation.ExcelColumn;
import org.novgorodtsev.excelimport.annotation.ExcelSheet;
import org.novgorodtsev.excelimport.annotation.TargetTable;
import org.novgorodtsev.excelimport.convert.CellConverter;
import org.novgorodtsev.excelimport.exception.MappingConfigurationException;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.apache.poi.ss.util.CellReference;

/** Строит {@link MappingModel} по аннотациям класса. Вся валидация конфигурации — здесь. */
public final class MappingModelFactory {

    private MappingModelFactory() {}

    public static <T> MappingModel<T> create(Class<T> type, NamingStrategy naming) {
        ExcelSheet sheetAnnotation = type.getAnnotation(ExcelSheet.class);
        if (sheetAnnotation == null) {
            throw new MappingConfigurationException(
                    "на классе " + type.getName() + " нет аннотации @ExcelSheet");
        }

        SheetSelector sheet = resolveSheet(type, sheetAnnotation);
        int headerRow = sheetAnnotation.headerRow();
        if (headerRow < 0) {
            throw new MappingConfigurationException(
                    "headerRow не может быть отрицательным на " + type.getName());
        }
        int firstDataRow =
                sheetAnnotation.firstDataRow() < 0 ? headerRow + 1 : sheetAnnotation.firstDataRow();
        if (firstDataRow <= headerRow) {
            throw new MappingConfigurationException(
                    "firstDataRow должен быть больше headerRow на " + type.getName());
        }

        MethodHandles.Lookup lookup = privateLookup(type);
        List<ColumnBinding> excelColumns = new ArrayList<>();
        List<ColumnBinding> dbOnlyColumns = new ArrayList<>();
        Set<String> seenDbColumns = new HashSet<>();

        for (Field field : allFields(type)) {
            if (Modifier.isStatic(field.getModifiers()) || field.isSynthetic()) {
                continue;
            }
            ExcelColumn excel = field.getAnnotation(ExcelColumn.class);
            Column column = field.getAnnotation(Column.class);
            if (excel == null && column == null) {
                continue;
            }
            boolean insertable = excel == null || excel.insertable();
            if (!insertable && column != null) {
                throw new MappingConfigurationException(
                        "на " + type.getName() + "." + field.getName()
                                + " одновременно заданы @Column и @ExcelColumn(insertable = false);"
                                + " уберите одно из двух");
            }
            String dbColumn = null;
            if (insertable) {
                dbColumn = column != null ? column.value() : naming.toColumnName(field.getName());
                if (!seenDbColumns.add(dbColumn)) {
                    throw new MappingConfigurationException(
                            "колонка БД " + dbColumn + " привязана более одного раза в " + type.getName());
                }
            }
            ColumnBinding binding = buildBinding(type, field, excel, dbColumn, lookup);
            if (binding.boundToExcel()) {
                excelColumns.add(binding);
            } else {
                dbOnlyColumns.add(binding);
            }
        }

        if (excelColumns.isEmpty()) {
            throw new MappingConfigurationException(
                    "в классе " + type.getName() + " нет ни одного @ExcelColumn");
        }
        if (seenDbColumns.isEmpty()) {
            throw new MappingConfigurationException(
                    "в классе " + type.getName() + " нет ни одной вставляемой колонки:"
                            + " все поля помечены insertable = false");
        }

        return new MappingModel<>(
                type, sheet, headerRow, firstDataRow, resolveTable(type),
                excelColumns, dbOnlyColumns, defaultConstructor(type, lookup));
    }

    private static SheetSelector resolveSheet(Class<?> type, ExcelSheet annotation) {
        boolean hasName = !annotation.name().isEmpty();
        boolean hasIndex = annotation.index() >= 0;
        if (hasName == hasIndex) {
            throw new MappingConfigurationException(
                    "в @ExcelSheet на " + type.getName() + " должен быть задан ровно один из name/index");
        }
        return hasName ? SheetSelector.byName(annotation.name()) : SheetSelector.byIndex(annotation.index());
    }

    private static TableRef resolveTable(Class<?> type) {
        TargetTable annotation = type.getAnnotation(TargetTable.class);
        if (annotation == null) {
            return null; // обязана быть задана в ImportConfig — проверяется при сборке импортёра
        }
        String schema = annotation.schema().isEmpty() ? null : annotation.schema();
        return new TableRef(schema, annotation.name());
    }

    private static ColumnBinding buildBinding(
            Class<?> type,
            Field field,
            ExcelColumn excel,
            String dbColumn,
            MethodHandles.Lookup lookup) {
        String header = null;
        Integer index = null;
        if (excel != null) {
            int modes = 0;
            if (!excel.header().isEmpty()) {
                header = excel.header();
                modes++;
            }
            if (excel.index() >= 0) {
                index = excel.index();
                modes++;
            }
            if (!excel.letter().isEmpty()) {
                index = CellReference.convertColStringToIndex(excel.letter());
                modes++;
            }
            if (modes != 1) {
                throw new MappingConfigurationException(
                        "в @ExcelColumn на " + type.getName() + "." + field.getName()
                                + " должен быть задан ровно один из header/index/letter");
            }
        }

        try {
            return new ColumnBinding(
                    field.getName(),
                    header,
                    index,
                    excel == null || excel.required(),
                    dbColumn,
                    field.getType(),
                    lookup.unreflectSetter(field),
                    lookup.unreflectGetter(field),
                    excel == null ? List.of() : List.of(excel.formats()),
                    excel == null || excel.trim(),
                    excel == null || excel.emptyAsNull(),
                    excel == null ? CellConverter.None.class : excel.converter());
        } catch (IllegalAccessException e) {
            throw new MappingConfigurationException(
                    "нет доступа к полю " + type.getName() + "." + field.getName()
                            + "; сделайте класс и его поля видимыми для библиотеки",
                    e);
        }
    }

    private static List<Field> allFields(Class<?> type) {
        List<Field> fields = new ArrayList<>();
        for (Class<?> current = type; current != null && current != Object.class;
                current = current.getSuperclass()) {
            fields.addAll(List.of(current.getDeclaredFields()));
        }
        return fields;
    }

    private static MethodHandles.Lookup privateLookup(Class<?> type) {
        try {
            return MethodHandles.privateLookupIn(type, MethodHandles.lookup());
        } catch (IllegalAccessException e) {
            throw new MappingConfigurationException(
                    "модуль, содержащий " + type.getName()
                            + ", должен открыть пакет для org.novgorodtsev.excelimport (opens ...)",
                    e);
        }
    }

    private static MethodHandle defaultConstructor(Class<?> type, MethodHandles.Lookup lookup) {
        try {
            return lookup.findConstructor(type, MethodType.methodType(void.class));
        } catch (NoSuchMethodException | IllegalAccessException e) {
            throw new MappingConfigurationException(
                    "у " + type.getName() + " нет доступного конструктора без аргументов", e);
        }
    }
}
