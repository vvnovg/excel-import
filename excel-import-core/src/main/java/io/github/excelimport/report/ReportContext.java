package io.github.excelimport.report;

import io.github.excelimport.RowStatus;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.DataFormat;
import org.apache.poi.xssf.streaming.SXSSFWorkbook;

/**
 * Доступ к записываемой книге отчёта из {@link ReportRowCustomizer}.
 * Стили брать только через {@link #styleFor}: книга ограничена 64 000 уникальных
 * {@code CellStyle}, а кэш переиспользует одинаковые.
 */
public interface ReportContext {

    SXSSFWorkbook workbook();

    DataFormat dataFormat();

    /** 0-based индекс колонки «Статус импорта». */
    int statusColumnIndex();

    /** 0-based индекс колонки «Причина». */
    int reasonColumnIndex();

    /**
     * @param status     заливка по статусу строки; null — без заливки
     * @param dataFormat строка формата, например {@code "dd.MM.yyyy"}; null — общий формат
     */
    CellStyle styleFor(RowStatus status, String dataFormat);
}
