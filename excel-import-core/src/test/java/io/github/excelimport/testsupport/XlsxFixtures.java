package io.github.excelimport.testsupport;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.Map;
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

    /** Книга в системе дат 1904 (эпоха Mac) с одной датой в A1. */
    public static Path date1904Workbook(Path dir, LocalDate date) {
        Path file = dir.resolve("fixture-1904-" + System.nanoTime() + ".xlsx");
        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            var ctWorkbook = workbook.getCTWorkbook();
            var workbookPr = ctWorkbook.isSetWorkbookPr()
                    ? ctWorkbook.getWorkbookPr()
                    : ctWorkbook.addNewWorkbookPr();
            workbookPr.setDate1904(true);
            Sheet sheet = workbook.createSheet("Лист1");
            CellStyle dateStyle = workbook.createCellStyle();
            dateStyle.setDataFormat(workbook.createDataFormat().getFormat("dd.MM.yyyy"));
            Cell cell = sheet.createRow(0).createCell(0);
            cell.setCellValue(org.apache.poi.ss.usermodel.DateUtil.getExcelDate(
                    date.atStartOfDay(), true));
            cell.setCellStyle(dateStyle);
            try (OutputStream out = Files.newOutputStream(file)) {
                workbook.write(out);
            }
        } catch (IOException e) {
            throw new IllegalStateException("не удалось создать фикстуру 1904", e);
        }
        return file;
    }

    /**
     * Книга с ровно одной строковой ячейкой A1 на листе "Лист1", записанной как inline
     * string ({@code t="inlineStr"}), а не через общую таблицу строк (shared strings).
     *
     * <p>POI не даёт способа попросить это через объектную модель — {@code Cell.setCellValue(String)}
     * всегда пишет через shared strings, поэтому XML ячейки листа правится напрямую
     * (через файловую систему zip) уже после того, как POI собрал файл.
     */
    public static Path inlineStringSheet(Path dir, String value) {
        Path file = workbook(dir, "Лист1", sheet -> sheet.createRow(0).createCell(0).setCellValue(value));
        try (FileSystem zip = FileSystems.newFileSystem(file, Map.of())) {
            Path sheetEntry = zip.getPath("/xl/worksheets/sheet1.xml");
            String xml = Files.readString(sheetEntry, StandardCharsets.UTF_8);
            String patched = xml.replaceFirst(
                    "<c r=\"A1\"[^>]*t=\"s\"[^>]*><v>\\d+</v></c>",
                    "<c r=\"A1\" t=\"inlineStr\"><is><t>" + escapeXml(value) + "</t></is></c>");
            if (patched.equals(xml)) {
                throw new IllegalStateException(
                        "не удалось найти ячейку A1 с типом shared string для подмены");
            }
            Files.writeString(sheetEntry, patched, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("не удалось подменить ячейку на inline string", e);
        }
        return file;
    }

    private static String escapeXml(String value) {
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
