package io.github.excelimport.internal.read;

import io.github.excelimport.convert.CellValue;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DateUtil;
import org.apache.poi.ss.util.CellAddress;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.model.SharedStrings;
import org.apache.poi.xssf.model.StylesTable;
import org.apache.poi.xssf.usermodel.XSSFCellStyle;
import org.xml.sax.Attributes;
import org.xml.sax.helpers.DefaultHandler;

/**
 * Разбирает {@code sheetN.xml} и отдаёт {@link RawRow} на каждую строку.
 * Свой обработчик вместо {@code XSSFSheetXMLHandler} нужен потому, что тот отдаёт
 * только отформатированную строку и теряет тип ячейки, признак даты и код ошибки.
 */
final class SheetSaxHandler extends DefaultHandler {

    private final SharedStrings sharedStrings;
    private final StylesTable styles;
    private final ReadOptions options;
    private final MergedFill mergedFill;
    private final Consumer<RawRow> handler;

    private int currentRowIndex = -1;
    private Map<Integer, CellValue> currentCells;

    private CellAddress cellAddress;
    private String cellTypeAttr;
    private int cellStyleIndex = -1;
    private final StringBuilder valueBuffer = new StringBuilder();
    private final StringBuilder formulaBuffer = new StringBuilder();
    private boolean inValue;
    private boolean inFormula;
    private boolean inInlineString;

    SheetSaxHandler(
            SharedStrings sharedStrings,
            StylesTable styles,
            ReadOptions options,
            MergedFill mergedFill,
            Consumer<RawRow> handler) {
        this.sharedStrings = sharedStrings;
        this.styles = styles;
        this.options = options;
        this.mergedFill = mergedFill;
        this.handler = handler;
    }

    @Override
    public void startElement(String uri, String localName, String qName, Attributes attrs) {
        switch (qName) {
            case "row" -> {
                String r = attrs.getValue("r");
                currentRowIndex = r != null ? Integer.parseInt(r) - 1 : currentRowIndex + 1;
                currentCells = new HashMap<>();
            }
            case "c" -> {
                String ref = attrs.getValue("r");
                cellAddress = ref != null
                        ? new CellAddress(ref)
                        : new CellAddress(currentRowIndex, currentCells.size());
                cellTypeAttr = attrs.getValue("t");
                String s = attrs.getValue("s");
                cellStyleIndex = s != null ? Integer.parseInt(s) : -1;
                valueBuffer.setLength(0);
                formulaBuffer.setLength(0);
                inInlineString = false;
            }
            case "v" -> inValue = true;
            case "f" -> inFormula = true;
            case "is" -> inInlineString = true;
            case "t" -> {
                if (inInlineString) {
                    inValue = true;
                }
            }
            default -> {
                // остальные элементы (sheetData, cols, dimension, ...) не интересуют
            }
        }
    }

    @Override
    public void characters(char[] ch, int start, int length) {
        if (inValue) {
            valueBuffer.append(ch, start, length);
        } else if (inFormula) {
            formulaBuffer.append(ch, start, length);
        }
    }

    @Override
    public void endElement(String uri, String localName, String qName) {
        switch (qName) {
            case "v", "t" -> inValue = false;
            case "f" -> inFormula = false;
            case "is" -> inInlineString = false;
            case "c" -> currentCells.put(cellAddress.getColumn(), buildCellValue());
            case "row" -> emitRow();
            default -> {
                // не интересует
            }
        }
    }

    private CellValue buildCellValue() {
        String raw = valueBuffer.length() == 0 ? null : valueBuffer.toString();
        String formula = formulaBuffer.length() == 0 ? null : formulaBuffer.toString();
        boolean dateFormatted = isDateFormatted();

        if (formula != null) {
            return buildFormulaCell(raw, formula, dateFormatted);
        }
        if (raw == null) {
            return new ImmutableCellValue(
                    cellAddress, CellType.BLANK, dateFormatted, null, null, null, null, (byte) -1);
        }
        return switch (cellTypeAttr == null ? "n" : cellTypeAttr) {
            case "s" -> string(sharedStrings.getItemAt(Integer.parseInt(raw)).getString(), dateFormatted);
            case "inlineStr", "str" -> string(raw, dateFormatted);
            case "b" -> {
                boolean value = "1".equals(raw);
                yield new ImmutableCellValue(
                        cellAddress,
                        CellType.BOOLEAN,
                        false,
                        value ? "TRUE" : "FALSE",
                        null,
                        value,
                        null,
                        (byte) -1);
            }
            case "e" -> new ImmutableCellValue(
                    cellAddress, CellType.ERROR, false, raw, null, null, null, errorCode(raw));
            default -> numeric(raw, dateFormatted);
        };
    }

    private CellValue buildFormulaCell(String raw, String formula, boolean dateFormatted) {
        if (raw == null) {
            return switch (options.formulaPolicy()) {
                case AS_NULL -> new ImmutableCellValue(
                        cellAddress, CellType.BLANK, dateFormatted, null, null, null, formula, (byte) -1);
                case AS_FORMULA_TEXT -> new ImmutableCellValue(
                        cellAddress, CellType.STRING, false, formula, null, null, formula, (byte) -1);
                case AS_ERROR -> new ImmutableCellValue(
                        cellAddress,
                        CellType.ERROR,
                        false,
                        "#FORMULA_NOT_CACHED",
                        null,
                        null,
                        formula,
                        (byte) -1);
            };
        }
        // есть кэшированный результат — используем его тип
        if ("e".equals(cellTypeAttr)) {
            return new ImmutableCellValue(
                    cellAddress, CellType.ERROR, false, raw, null, null, formula, errorCode(raw));
        }
        if ("str".equals(cellTypeAttr) || "s".equals(cellTypeAttr) || "inlineStr".equals(cellTypeAttr)) {
            String text = "s".equals(cellTypeAttr)
                    ? sharedStrings.getItemAt(Integer.parseInt(raw)).getString()
                    : raw;
            return new ImmutableCellValue(
                    cellAddress, CellType.STRING, dateFormatted, text, null, null, formula, (byte) -1);
        }
        if ("b".equals(cellTypeAttr)) {
            boolean value = "1".equals(raw);
            return new ImmutableCellValue(
                    cellAddress,
                    CellType.BOOLEAN,
                    false,
                    value ? "TRUE" : "FALSE",
                    null,
                    value,
                    formula,
                    (byte) -1);
        }
        double number = Double.parseDouble(raw);
        return new ImmutableCellValue(
                cellAddress, CellType.NUMERIC, dateFormatted, raw, number, null, formula, (byte) -1);
    }

    private CellValue string(String text, boolean dateFormatted) {
        return new ImmutableCellValue(
                cellAddress, CellType.STRING, dateFormatted, text, null, null, null, (byte) -1);
    }

    private CellValue numeric(String raw, boolean dateFormatted) {
        double number = Double.parseDouble(raw);
        return new ImmutableCellValue(
                cellAddress, CellType.NUMERIC, dateFormatted, raw, number, null, null, (byte) -1);
    }

    private static byte errorCode(String raw) {
        try {
            return org.apache.poi.ss.usermodel.FormulaError.forString(raw).getCode();
        } catch (IllegalArgumentException e) {
            return (byte) -1;
        }
    }

    private boolean isDateFormatted() {
        if (cellStyleIndex < 0 || styles == null) {
            return false;
        }
        XSSFCellStyle style = styles.getStyleAt(cellStyleIndex);
        if (style == null) {
            return false;
        }
        return DateUtil.isADateFormat(style.getDataFormat(), style.getDataFormatString());
    }

    private void emitRow() {
        Map<Integer, CellValue> cells = currentCells;
        if (options.expandMergedCells()) {
            mergedFill.apply(currentRowIndex, cells);
        }
        RawRow row = new RawRow(currentRowIndex, cells);
        currentCells = null;
        if (options.skipBlankRows() && row.isBlank()) {
            return;
        }
        handler.accept(row);
    }

    /**
     * Диапазоны объединённых ячеек с известным (непустым) значением якоря, готовые к
     * заполнению покрытых ячеек «на лету» при выдаче каждой строки.
     *
     * <p>Хранит ровно {@code merges.size()} записей — по одной на диапазон, а не по одной
     * на каждую покрытую ячейку. Это принципиально: диапазон вида {@code A1:A100000}
     * покрывает 100 000 ячеек, но должен занимать столько же памяти, сколько диапазон
     * {@code A1:A2} — иначе потребление кучи растёт с числом строк файла, что запрещено
     * ограничением проекта (100 000 строк × 10 колонок должны читаться под {@code -Xmx256m}).
     *
     * <p>{@link #apply(int, Map)} вызывается для строк в порядке возрастания индекса (как их
     * отдаёт SAX-парсер), поэтому используется скользящее окно (sweep line) по диапазонам,
     * отсортированным по первой строке: каждый диапазон добавляется в «активные» и убирается
     * из них не более одного раза за весь проход. Побочный эффект: при заполнении текущей
     * строки перебираются только диапазоны, реально её покрывающие (обычно единицы), а не
     * все диапазоны листа.
     */
    static final class MergedFill {

        static final MergedFill EMPTY = new MergedFill(List.of());

        private record Merge(CellRangeAddress range, CellValue anchor) {}

        private final List<Merge> merges;
        private final List<Merge> active = new ArrayList<>();
        private int cursor;

        private MergedFill(List<Merge> merges) {
            this.merges = merges;
        }

        /**
         * Строит заполнитель из диапазонов и значений их верхних левых ячеек. Диапазоны с
         * отсутствующим или пустым якорем отбрасываются — заполнять для них нечем.
         */
        static MergedFill of(List<CellRangeAddress> ranges, Map<CellRangeAddress, CellValue> anchors) {
            List<Merge> merges = new ArrayList<>();
            for (CellRangeAddress range : ranges) {
                CellValue anchor = anchors.get(range);
                if (anchor != null && !anchor.isBlank()) {
                    merges.add(new Merge(range, anchor));
                }
            }
            merges.sort(Comparator.comparingInt(m -> m.range().getFirstRow()));
            return merges.isEmpty() ? EMPTY : new MergedFill(merges);
        }

        void apply(int rowIndex, Map<Integer, CellValue> cells) {
            if (merges.isEmpty()) {
                return;
            }
            active.removeIf(m -> m.range().getLastRow() < rowIndex);
            while (cursor < merges.size() && merges.get(cursor).range().getFirstRow() <= rowIndex) {
                active.add(merges.get(cursor));
                cursor++;
            }
            for (Merge m : active) {
                CellRangeAddress range = m.range();
                for (int c = range.getFirstColumn(); c <= range.getLastColumn(); c++) {
                    if (rowIndex == range.getFirstRow() && c == range.getFirstColumn()) {
                        continue; // сама ячейка-якорь — её значение уже верно в currentCells
                    }
                    CellValue existing = cells.get(c);
                    // непустой существующий (например, ещё один якорь) значение не трогаем;
                    // отсутствующую или пустую (placeholder-стиль без значения) ячейку — заполняем
                    if (existing == null || existing.isBlank()) {
                        cells.put(c, copyTo(m.anchor(), new CellAddress(rowIndex, c)));
                    }
                }
            }
        }
    }

    private static CellValue copyTo(CellValue source, CellAddress address) {
        return new ImmutableCellValue(
                address,
                source.type(),
                source.dateFormatted(),
                source.asString(),
                source.asNumeric(),
                source.asBoolean(),
                source.formula(),
                source.errorCode());
    }
}
