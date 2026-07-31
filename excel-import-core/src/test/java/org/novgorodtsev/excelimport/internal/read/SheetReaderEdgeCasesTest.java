package org.novgorodtsev.excelimport.internal.read;

import static org.assertj.core.api.Assertions.assertThat;

import org.novgorodtsev.excelimport.SheetSelector;
import org.novgorodtsev.excelimport.testsupport.XlsxFixtures;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.FormulaError;
import org.apache.poi.ss.usermodel.Row;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SheetReaderEdgeCasesTest {

    @TempDir
    Path tempDir;

    private final StreamingSheetReader reader = new PoiStreamingSheetReader();

    private List<RawRow> readAll(Path file, ReadOptions options) {
        List<RawRow> rows = new ArrayList<>();
        reader.forEachRow(file, SheetSelector.first(), options, rows::add);
        return rows;
    }

    @Test
    void dateCellIsNumericButFlaggedAsDate() {
        Path file = XlsxFixtures.simpleSheet(tempDir, new Object[][] {{LocalDate.of(2026, 7, 29)}});

        RawRow row = readAll(file, ReadOptions.defaults()).get(0);

        assertThat(row.cell(0).type()).isEqualTo(CellType.NUMERIC);
        assertThat(row.cell(0).dateFormatted()).isTrue();
        assertThat(row.cell(0).asLocalDateTime()).isEqualTo(LocalDateTime.of(2026, 7, 29, 0, 0));
    }

    @Test
    void plainNumberIsNotFlaggedAsDate() {
        Path file = XlsxFixtures.simpleSheet(tempDir, new Object[][] {{42}});

        assertThat(readAll(file, ReadOptions.defaults()).get(0).cell(0).dateFormatted()).isFalse();
    }

    @Test
    void inlineStringIsRead() {
        // POI не даёт способа через объектную модель попросить именно inline string —
        // обычная запись строки всегда уходит в общую таблицу строк (shared strings),
        // поэтому фикстура правит XML ячейки напрямую на t="inlineStr" после того,
        // как POI собрал файл (см. XlsxFixtures.inlineStringSheet).
        Path file = XlsxFixtures.inlineStringSheet(tempDir, "инлайн");

        assertThat(readAll(file, ReadOptions.defaults()).get(0).cell(0).asString())
                .isEqualTo("инлайн");
    }

    @Test
    void errorCellCarriesErrorCodeAndText() {
        Path file = XlsxFixtures.workbook(tempDir, "Лист1", sheet -> {
            Cell cell = sheet.createRow(0).createCell(0);
            cell.setCellErrorValue(FormulaError.NA.getCode());
        });

        RawRow row = readAll(file, ReadOptions.defaults()).get(0);

        assertThat(row.cell(0).type()).isEqualTo(CellType.ERROR);
        assertThat(row.cell(0).errorCode()).isEqualTo(FormulaError.NA.getCode());
        assertThat(row.cell(0).asString()).isEqualTo("#N/A");
    }

    @Test
    void formulaWithCachedResultUsesCachedValue() {
        Path file = XlsxFixtures.workbook(tempDir, "Лист1", sheet -> {
            Row row = sheet.createRow(0);
            row.createCell(0).setCellValue(2);
            row.createCell(1).setCellValue(3);
            Cell formula = row.createCell(2);
            formula.setCellFormula("A1+B1");
            // POI не считает формулу сама — кэшируем результат вручную
            formula.setCellValue(5);
        });

        RawRow row = readAll(file, ReadOptions.defaults()).get(0);

        assertThat(row.cell(2).asNumeric()).isEqualTo(5.0);
        assertThat(row.cell(2).formula()).isEqualTo("A1+B1");
    }

    @Test
    void formulaWithoutCacheIsBlankUnderAsNullPolicy() {
        Path file = formulaWithoutCache();

        RawRow row = readAll(file, ReadOptions.defaults()).get(0);

        assertThat(row.cell(2).isBlank()).isTrue();
        assertThat(row.cell(2).formula()).isEqualTo("A1+B1");
    }

    @Test
    void formulaWithoutCacheBecomesTextUnderFormulaTextPolicy() {
        Path file = formulaWithoutCache();

        RawRow row = readAll(file, new ReadOptions(true, true, FormulaPolicy.AS_FORMULA_TEXT)).get(0);

        assertThat(row.cell(2).asString()).isEqualTo("A1+B1");
    }

    @Test
    void formulaWithoutCacheBecomesErrorUnderErrorPolicy() {
        Path file = formulaWithoutCache();

        RawRow row = readAll(file, new ReadOptions(true, true, FormulaPolicy.AS_ERROR)).get(0);

        assertThat(row.cell(2).type()).isEqualTo(CellType.ERROR);
        assertThat(row.cell(2).asString()).isEqualTo("#FORMULA_NOT_CACHED");
    }

    private Path formulaWithoutCache() {
        return XlsxFixtures.workbook(tempDir, "Лист1", sheet -> {
            Row row = sheet.createRow(0);
            row.createCell(0).setCellValue(2);
            row.createCell(1).setCellValue(3);
            row.createCell(2).setCellFormula("A1+B1");
        });
    }

    @Test
    void date1904WorkbookIsInterpretedCorrectly() {
        Path file = XlsxFixtures.date1904Workbook(tempDir, LocalDate.of(2026, 7, 29));

        RawRow row = readAll(file, ReadOptions.defaults()).get(0);

        assertThat(row.cell(0).asLocalDateTime()).isEqualTo(LocalDateTime.of(2026, 7, 29, 0, 0));
    }
}
