package io.github.excelimport.internal.read;

import io.github.excelimport.SheetSelector;
import io.github.excelimport.convert.CellValue;
import io.github.excelimport.exception.FileStructureException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.parsers.SAXParser;
import javax.xml.parsers.SAXParserFactory;
import org.apache.poi.openxml4j.exceptions.InvalidFormatException;
import org.apache.poi.openxml4j.exceptions.NotOfficeXmlFileException;
import org.apache.poi.openxml4j.opc.OPCPackage;
import org.apache.poi.openxml4j.opc.PackageAccess;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.eventusermodel.ReadOnlySharedStringsTable;
import org.apache.poi.xssf.eventusermodel.XSSFReader;
import org.apache.poi.xssf.model.StylesTable;
import org.xml.sax.Attributes;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import org.xml.sax.helpers.DefaultHandler;

/** Реализация на {@code XSSFReader} + SAX. */
public final class PoiStreamingSheetReader implements StreamingSheetReader {

    @Override
    public void forEachRow(
            Path source, SheetSelector selector, ReadOptions options, Consumer<RawRow> handler) {
        try (OPCPackage pkg = OPCPackage.open(source.toFile(), PackageAccess.READ)) {
            XSSFReader reader = new XSSFReader(pkg);
            ReadOnlySharedStringsTable strings = new ReadOnlySharedStringsTable(pkg);
            StylesTable styles = reader.getStylesTable();

            SheetSaxHandler.MergedFill mergedFill = options.expandMergedCells()
                    ? collectMergedRanges(reader, selector, strings, styles, options)
                    : SheetSaxHandler.MergedFill.EMPTY;

            try (InputStream sheet = openSheet(reader, selector)) {
                SheetSaxHandler saxHandler =
                        new SheetSaxHandler(strings, styles, options, mergedFill, handler);
                newParser().parse(new InputSource(sheet), saxHandler);
            }
        } catch (NotOfficeXmlFileException e) {
            throw new FileStructureException(
                    "не удалось открыть файл как .xlsx: " + source.getFileName(), e);
        } catch (InvalidFormatException | IOException e) {
            throw new FileStructureException(
                    "не удалось открыть файл как .xlsx: " + source.getFileName(), e);
        } catch (SAXException e) {
            throw new FileStructureException("повреждённый XML листа: " + source.getFileName(), e);
        } catch (org.apache.poi.openxml4j.exceptions.OpenXML4JException e) {
            throw new FileStructureException("не удалось прочитать структуру книги", e);
        }
    }

    private static SAXParser newParser() {
        try {
            SAXParserFactory factory = SAXParserFactory.newInstance();
            factory.setNamespaceAware(false);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            return factory.newSAXParser();
        } catch (ParserConfigurationException | SAXException e) {
            throw new IllegalStateException("не удалось создать SAX-парсер", e);
        }
    }

    private static InputStream openSheet(XSSFReader reader, SheetSelector selector)
            throws IOException, org.apache.poi.openxml4j.exceptions.InvalidFormatException {
        XSSFReader.SheetIterator iterator = (XSSFReader.SheetIterator) reader.getSheetsData();
        if (selector.name().isPresent()) {
            String wanted = selector.name().get();
            while (iterator.hasNext()) {
                InputStream stream = iterator.next();
                if (wanted.equals(iterator.getSheetName())) {
                    return stream;
                }
                stream.close();
            }
            throw new FileStructureException("в книге нет листа с именем: " + wanted);
        }
        int wanted = selector.index().orElseThrow();
        int current = 0;
        while (iterator.hasNext()) {
            InputStream stream = iterator.next();
            if (current++ == wanted) {
                return stream;
            }
            stream.close();
        }
        throw new FileStructureException("в книге нет листа с индексом: " + wanted);
    }

    /**
     * Предпроход: {@code mergeCells} в XML стоит после {@code sheetData}, поэтому
     * диапазоны и значения их верхних левых ячеек собираются отдельным проходом.
     *
     * <p>Возвращает {@link SheetSaxHandler.MergedFill} — структуру размером
     * O(число диапазонов), а не O(число покрытых ячеек): сами значения не размножаются
     * заранее по всем покрытым адресам, это делается лениво при выдаче каждой строки
     * (см. {@link SheetSaxHandler.MergedFill#apply}).
     */
    private SheetSaxHandler.MergedFill collectMergedRanges(
            XSSFReader reader,
            SheetSelector selector,
            ReadOnlySharedStringsTable strings,
            StylesTable styles,
            ReadOptions options)
            throws IOException, SAXException,
                    org.apache.poi.openxml4j.exceptions.InvalidFormatException {
        List<CellRangeAddress> ranges = new ArrayList<>();
        try (InputStream sheet = openSheet(reader, selector)) {
            newParser().parse(new InputSource(sheet), new DefaultHandler() {
                @Override
                public void startElement(String uri, String ln, String qName, Attributes attrs) {
                    if ("mergeCell".equals(qName)) {
                        ranges.add(CellRangeAddress.valueOf(attrs.getValue("ref")));
                    }
                }
            });
        }
        if (ranges.isEmpty()) {
            // Ни одного диапазона — короткий предпроход анкоров не нужен вовсе.
            return SheetSaxHandler.MergedFill.EMPTY;
        }

        // Диапазоны индексируются по первой строке, чтобы предпроход анкоров делал одно
        // обращение к карте на строку, а не перебирал все диапазоны для каждой строки
        // (иначе 100 000 строк × 1 000 диапазонов — 10^8 сравнений на пустом месте).
        Map<Integer, List<CellRangeAddress>> rangesByFirstRow = new HashMap<>();
        for (CellRangeAddress range : ranges) {
            rangesByFirstRow.computeIfAbsent(range.getFirstRow(), key -> new ArrayList<>()).add(range);
        }

        // Собираем значения верхних левых ячеек диапазонов вторым коротким проходом.
        Map<CellRangeAddress, CellValue> anchors = new HashMap<>();
        ReadOptions rawOptions = new ReadOptions(false, false, options.formulaPolicy());
        try (InputStream sheet = openSheet(reader, selector)) {
            SheetSaxHandler collector = new SheetSaxHandler(
                    strings, styles, rawOptions, SheetSaxHandler.MergedFill.EMPTY, row -> {
                        List<CellRangeAddress> startingHere = rangesByFirstRow.get(row.rowIndex());
                        if (startingHere == null) {
                            return;
                        }
                        for (CellRangeAddress range : startingHere) {
                            anchors.put(range, row.cell(range.getFirstColumn()));
                        }
                    });
            newParser().parse(new InputSource(sheet), collector);
        }

        return SheetSaxHandler.MergedFill.of(ranges, anchors);
    }
}
