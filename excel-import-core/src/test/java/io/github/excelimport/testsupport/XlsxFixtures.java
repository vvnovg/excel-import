package io.github.excelimport.testsupport;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.function.Consumer;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

/** Генерация .xlsx-фикстур в памяти. Только для тестов. */
public final class XlsxFixtures {

    private XlsxFixtures() {}

    /** Создаёт книгу с одним листом и отдаёт путь к файлу. */
    public static Path workbook(Path dir, String sheetName, Consumer<Sheet> builder) {
        Path file = dir.resolve("fixture-" + System.nanoTime() + ".xlsx");
        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            Sheet sheet = workbook.createSheet(sheetName);
            builder.accept(sheet);
            try (OutputStream out = Files.newOutputStream(file)) {
                workbook.write(out);
            }
        } catch (IOException e) {
            throw new IllegalStateException("не удалось создать фикстуру", e);
        }
        return file;
    }

    /**
     * Лист из матрицы значений. {@code null} в ячейке означает «ячейку не создавать».
     * Поддерживаются String, Number, Boolean, LocalDate.
     */
    public static Path simpleSheet(Path dir, Object[][] rows) {
        return workbook(dir, "Лист1", sheet -> fill(sheet, rows));
    }

    public static void fill(Sheet sheet, Object[][] rows) {
        CellStyle dateStyle = sheet.getWorkbook().createCellStyle();
        dateStyle.setDataFormat(sheet.getWorkbook().createDataFormat().getFormat("dd.MM.yyyy"));

        for (int r = 0; r < rows.length; r++) {
            if (rows[r] == null) {
                continue; // строка вообще не создаётся — проверяем пропуски строк
            }
            Row row = sheet.createRow(r);
            for (int c = 0; c < rows[r].length; c++) {
                Object value = rows[r][c];
                if (value == null) {
                    continue; // ячейка не создаётся — проверяем пропуски ячеек
                }
                Cell cell = row.createCell(c);
                if (value instanceof String s) {
                    cell.setCellValue(s);
                } else if (value instanceof Number n) {
                    cell.setCellValue(n.doubleValue());
                } else if (value instanceof Boolean b) {
                    cell.setCellValue(b);
                } else if (value instanceof LocalDate d) {
                    cell.setCellValue(d);
                    cell.setCellStyle(dateStyle);
                } else {
                    throw new IllegalArgumentException("неподдерживаемый тип: " + value.getClass());
                }
            }
        }
    }
}
