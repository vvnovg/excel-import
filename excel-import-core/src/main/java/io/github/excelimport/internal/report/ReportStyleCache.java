package io.github.excelimport.internal.report;

import io.github.excelimport.RowStatus;
import io.github.excelimport.report.ReportStyle;
import java.util.HashMap;
import java.util.Map;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.DataFormat;
import org.apache.poi.xssf.streaming.SXSSFWorkbook;
import org.apache.poi.xssf.usermodel.XSSFCellStyle;
import org.apache.poi.xssf.usermodel.XSSFColor;

/**
 * Кэш стилей по паре (статус, формат). Без него книга получила бы по стилю на ячейку
 * и упёрлась в предел 64 000 уникальных стилей.
 */
final class ReportStyleCache {

    private record Key(RowStatus status, String dataFormat, boolean reason) {}

    private final SXSSFWorkbook workbook;
    private final ReportStyle style;
    private final DataFormat dataFormat;
    private final Map<Key, CellStyle> cache = new HashMap<>();

    ReportStyleCache(SXSSFWorkbook workbook, ReportStyle style) {
        this.workbook = workbook;
        this.style = style;
        this.dataFormat = workbook.createDataFormat();
    }

    DataFormat dataFormat() {
        return dataFormat;
    }

    CellStyle styleFor(RowStatus status, String format) {
        return cache.computeIfAbsent(new Key(status, format, false), this::create);
    }

    /** Стиль ячейки причины: отдельный ключ, т.к. добавляет {@code reasonAlignment}. */
    CellStyle reasonStyleFor(RowStatus status, String format) {
        return cache.computeIfAbsent(new Key(status, format, true), this::create);
    }

    private CellStyle create(Key key) {
        XSSFCellStyle cellStyle = (XSSFCellStyle) workbook.createCellStyle();
        XSSFColor fill = fillFor(key.status());
        if (fill != null) {
            cellStyle.setFillForegroundColor(fill);
            cellStyle.setFillPattern(style.fillPattern());
        }
        if (key.dataFormat() != null) {
            cellStyle.setDataFormat(dataFormat.getFormat(key.dataFormat()));
        }
        if (key.reason()) {
            cellStyle.setAlignment(style.reasonAlignment());
        }
        return cellStyle;
    }

    private XSSFColor fillFor(RowStatus status) {
        if (status == null) {
            return null;
        }
        return switch (status) {
            case INSERTED -> style.insertedFill();
            case REJECTED -> style.rejectedFill();
            case SKIPPED, NOT_PROCESSED -> style.skippedFill();
        };
    }
}
