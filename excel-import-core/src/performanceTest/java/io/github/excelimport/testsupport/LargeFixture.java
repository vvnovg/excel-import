package io.github.excelimport.testsupport;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.xssf.streaming.SXSSFSheet;
import org.apache.poi.xssf.streaming.SXSSFWorkbook;

/**
 * Генерирует крупный .xlsx потоковой записью — сам генератор тоже не должен съедать heap.
 * Файл не коммитится в репозиторий, а создаётся перед перф-тестом.
 *
 * <p>Отклонение от брифа: явный {@code SXSSFWorkbook.dispose()} опущен — deprecated
 * в POI 5.4.0 (каталог закрепляет 5.4.1): временные файлы удаляются в {@code close()},
 * которым управляет try-with-resources, а сборка с {@code -Xlint:all -Werror} не терпит
 * предупреждений.
 */
public final class LargeFixture {

    private LargeFixture() {}

    /** Файл с {@code dataRows} строками данных и 10 колонками. */
    public static Path generate(Path dir, int dataRows) {
        Path file = dir.resolve("large-" + dataRows + ".xlsx");
        try (SXSSFWorkbook workbook = new SXSSFWorkbook(100)) {
            workbook.setCompressTempFiles(true);
            SXSSFSheet sheet = workbook.createSheet("Лист1");

            Row header = sheet.createRow(0);
            header.createCell(0).setCellValue("Ключ");
            header.createCell(1).setCellValue("ФИО");
            for (int column = 2; column < 10; column++) {
                header.createCell(column).setCellValue("Поле " + column);
            }

            for (int rowNum = 1; rowNum <= dataRows; rowNum++) {
                Row row = sheet.createRow(rowNum);
                row.createCell(0).setCellValue(rowNum);
                row.createCell(1).setCellValue("Сотрудник " + rowNum);
                for (int column = 2; column < 10; column++) {
                    row.createCell(column).setCellValue(rowNum + column);
                }
            }

            try (OutputStream out = Files.newOutputStream(file)) {
                workbook.write(out);
            }
            // dispose() не вызываем: close() из try-with-resources чистит временные файлы
        } catch (IOException e) {
            throw new IllegalStateException("не удалось сгенерировать большую фикстуру", e);
        }
        return file;
    }
}
