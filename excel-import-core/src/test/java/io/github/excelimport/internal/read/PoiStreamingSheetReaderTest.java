package io.github.excelimport.internal.read;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.excelimport.SheetSelector;
import io.github.excelimport.exception.FileStructureException;
import io.github.excelimport.testsupport.XlsxFixtures;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.apache.poi.ss.usermodel.CellType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PoiStreamingSheetReaderTest {

    @TempDir
    Path tempDir;

    private final StreamingSheetReader reader = new PoiStreamingSheetReader();

    private List<RawRow> readAll(Path file, SheetSelector selector, ReadOptions options) {
        List<RawRow> rows = new ArrayList<>();
        reader.forEachRow(file, selector, options, rows::add);
        return rows;
    }

    @Test
    void readsStringsAndNumbers() {
        Path file = XlsxFixtures.simpleSheet(tempDir, new Object[][] {
            {"ФИО", "Оклад"},
            {"Иванов", 1000.5},
        });

        List<RawRow> rows = readAll(file, SheetSelector.first(), ReadOptions.defaults());

        assertThat(rows).hasSize(2);
        assertThat(rows.get(0).cell(0).asString()).isEqualTo("ФИО");
        assertThat(rows.get(1).cell(0).type()).isEqualTo(CellType.STRING);
        assertThat(rows.get(1).cell(1).type()).isEqualTo(CellType.NUMERIC);
        assertThat(rows.get(1).cell(1).asNumeric()).isEqualTo(1000.5);
    }

    @Test
    void rowIndexIsZeroBasedAndExcelNumberIsOneBased() {
        Path file = XlsxFixtures.simpleSheet(tempDir, new Object[][] {{"a"}, {"b"}});

        List<RawRow> rows = readAll(file, SheetSelector.first(), ReadOptions.defaults());

        assertThat(rows.get(0).rowIndex()).isZero();
        assertThat(rows.get(0).excelRowNumber()).isEqualTo(1);
        assertThat(rows.get(1).rowIndex()).isEqualTo(1);
        assertThat(rows.get(1).excelRowNumber()).isEqualTo(2);
    }

    @Test
    void missingCellsBecomeBlankWithoutShiftingIndexes() {
        // в строке 1 нет ячейки B — значение C не должно сдвинуться на её место
        Path file = XlsxFixtures.simpleSheet(tempDir, new Object[][] {
            {"A", "B", "C"},
            {"a1", null, "c1"},
        });

        List<RawRow> rows = readAll(file, SheetSelector.first(), ReadOptions.defaults());

        RawRow data = rows.get(1);
        assertThat(data.cell(0).asString()).isEqualTo("a1");
        assertThat(data.cell(1).isBlank()).isTrue();
        assertThat(data.cell(1).asString()).isNull();
        assertThat(data.cell(2).asString()).isEqualTo("c1");
    }

    @Test
    void cellBeyondLastColumnIsBlankNotNull() {
        Path file = XlsxFixtures.simpleSheet(tempDir, new Object[][] {{"A"}});

        RawRow row = readAll(file, SheetSelector.first(), ReadOptions.defaults()).get(0);

        assertThat(row.cell(42)).isNotNull();
        assertThat(row.cell(42).isBlank()).isTrue();
    }

    @Test
    void missingRowsDoNotShiftRowIndexes() {
        // строки 1 (индекс 1) нет вовсе
        Path file = XlsxFixtures.simpleSheet(tempDir, new Object[][] {
            {"header"},
            null,
            {"data"},
        });

        List<RawRow> rows = readAll(file, SheetSelector.first(), ReadOptions.defaults());

        assertThat(rows).hasSize(2);
        assertThat(rows.get(1).rowIndex()).isEqualTo(2);
        assertThat(rows.get(1).excelRowNumber()).isEqualTo(3);
    }

    @Test
    void blankRowsAreSkippedByDefault() {
        Path file = XlsxFixtures.workbook(tempDir, "Лист1", sheet -> {
            sheet.createRow(0).createCell(0).setCellValue("header");
            sheet.createRow(1).createCell(0).setCellValue(""); // существует, но пустая
            sheet.createRow(2).createCell(0).setCellValue("data");
        });

        List<RawRow> rows = readAll(file, SheetSelector.first(), ReadOptions.defaults());

        assertThat(rows).hasSize(2);
        assertThat(rows.get(1).cell(0).asString()).isEqualTo("data");
    }

    @Test
    void blankRowsAreKeptWhenSkippingDisabled() {
        Path file = XlsxFixtures.workbook(tempDir, "Лист1", sheet -> {
            sheet.createRow(0).createCell(0).setCellValue("header");
            sheet.createRow(1).createCell(0).setCellValue("");
        });

        ReadOptions options = new ReadOptions(false, true, FormulaPolicy.AS_NULL);

        assertThat(readAll(file, SheetSelector.first(), options)).hasSize(2);
    }

    @Test
    void readsBooleanCells() {
        Path file = XlsxFixtures.simpleSheet(tempDir, new Object[][] {{Boolean.TRUE, Boolean.FALSE}});

        RawRow row = readAll(file, SheetSelector.first(), ReadOptions.defaults()).get(0);

        assertThat(row.cell(0).type()).isEqualTo(CellType.BOOLEAN);
        assertThat(row.cell(0).asBoolean()).isTrue();
        assertThat(row.cell(1).asBoolean()).isFalse();
        assertThat(row.cell(0).asString()).isEqualTo("TRUE");
    }

    @Test
    void selectsSheetByName() {
        Path file = XlsxFixtures.workbook(tempDir, "Первый", sheet -> {
            sheet.createRow(0).createCell(0).setCellValue("не тот");
            sheet.getWorkbook().createSheet("Второй").createRow(0).createCell(0).setCellValue("тот");
        });

        List<RawRow> rows = readAll(file, SheetSelector.byName("Второй"), ReadOptions.defaults());

        assertThat(rows.get(0).cell(0).asString()).isEqualTo("тот");
    }

    @Test
    void missingSheetByNameFails() {
        Path file = XlsxFixtures.simpleSheet(tempDir, new Object[][] {{"a"}});

        assertThatThrownBy(() ->
                        readAll(file, SheetSelector.byName("Нет такого"), ReadOptions.defaults()))
                .isInstanceOf(FileStructureException.class)
                .hasMessageContaining("Нет такого");
    }

    @Test
    void missingSheetByIndexFails() {
        Path file = XlsxFixtures.simpleSheet(tempDir, new Object[][] {{"a"}});

        assertThatThrownBy(() -> readAll(file, SheetSelector.byIndex(5), ReadOptions.defaults()))
                .isInstanceOf(FileStructureException.class)
                .hasMessageContaining("5");
    }

    @Test
    void nonZipFileFails() throws Exception {
        Path file = tempDir.resolve("broken.xlsx");
        Files.writeString(file, "это не xlsx");

        assertThatThrownBy(() -> readAll(file, SheetSelector.first(), ReadOptions.defaults()))
                .isInstanceOf(FileStructureException.class)
                .hasMessageContaining("не удалось открыть");
    }

    @Test
    void handlerCanStopEarlyWithoutLeakingResources() {
        Path file = XlsxFixtures.simpleSheet(tempDir, new Object[][] {{"a"}, {"b"}, {"c"}});
        List<RawRow> seen = new ArrayList<>();

        assertThatThrownBy(() -> reader.forEachRow(
                        file,
                        SheetSelector.first(),
                        ReadOptions.defaults(),
                        row -> {
                            seen.add(row);
                            if (seen.size() == 2) {
                                throw new IllegalStateException("стоп");
                            }
                        }))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("стоп");

        assertThat(seen).hasSize(2);
    }
}
