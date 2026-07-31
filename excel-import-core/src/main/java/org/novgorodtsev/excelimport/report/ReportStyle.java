package org.novgorodtsev.excelimport.report;

import java.util.Objects;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.HorizontalAlignment;
import org.apache.poi.ss.usermodel.IndexedColors;
import org.apache.poi.xssf.usermodel.XSSFColor;

/** Оформление Excel-отчёта в терминах POI. Иммутабельно, собирается билдером. */
public final class ReportStyle {

    private static final ReportStyle DEFAULTS = builder().build();

    private final XSSFColor insertedFill;
    private final XSSFColor rejectedFill;
    private final XSSFColor skippedFill;
    private final FillPatternType fillPattern;
    private final String statusColumnHeader;
    private final String reasonColumnHeader;
    private final HorizontalAlignment reasonAlignment;
    private final String dateFormat;
    private final String insertedText;
    private final String rejectedText;
    private final String skippedText;
    private final String summarySheetName;
    private final String reportSheetNamePrefix;

    private ReportStyle(Builder builder) {
        this.insertedFill = builder.insertedFill;
        this.rejectedFill = builder.rejectedFill;
        this.skippedFill = builder.skippedFill;
        this.fillPattern = builder.fillPattern;
        this.statusColumnHeader = builder.statusColumnHeader;
        this.reasonColumnHeader = builder.reasonColumnHeader;
        this.reasonAlignment = builder.reasonAlignment;
        this.dateFormat = builder.dateFormat;
        this.insertedText = builder.insertedText;
        this.rejectedText = builder.rejectedText;
        this.skippedText = builder.skippedText;
        this.summarySheetName = builder.summarySheetName;
        this.reportSheetNamePrefix = builder.reportSheetNamePrefix;
    }

    public static ReportStyle defaults() {
        return DEFAULTS;
    }

    public static Builder builder() {
        return new Builder();
    }

    public XSSFColor insertedFill() {
        return insertedFill;
    }

    public XSSFColor rejectedFill() {
        return rejectedFill;
    }

    public XSSFColor skippedFill() {
        return skippedFill;
    }

    public FillPatternType fillPattern() {
        return fillPattern;
    }

    public String statusColumnHeader() {
        return statusColumnHeader;
    }

    public String reasonColumnHeader() {
        return reasonColumnHeader;
    }

    public HorizontalAlignment reasonAlignment() {
        return reasonAlignment;
    }

    public String dateFormat() {
        return dateFormat;
    }

    public String insertedText() {
        return insertedText;
    }

    public String rejectedText() {
        return rejectedText;
    }

    public String skippedText() {
        return skippedText;
    }

    public String summarySheetName() {
        return summarySheetName;
    }

    public String reportSheetNamePrefix() {
        return reportSheetNamePrefix;
    }

    /** Билдер. Цвета принимаются и как {@link XSSFColor}, и как {@link IndexedColors}. */
    public static final class Builder {

        private XSSFColor insertedFill = rgb(0xC6, 0xEF, 0xCE);
        private XSSFColor rejectedFill = rgb(0xFF, 0xC7, 0xCE);
        private XSSFColor skippedFill = rgb(0xF2, 0xF2, 0xF2);
        private FillPatternType fillPattern = FillPatternType.SOLID_FOREGROUND;
        private String statusColumnHeader = "Статус импорта";
        private String reasonColumnHeader = "Причина";
        private HorizontalAlignment reasonAlignment = HorizontalAlignment.LEFT;
        private String dateFormat = "dd.MM.yyyy";
        private String insertedText = "Загружено";
        private String rejectedText = "Ошибка";
        private String skippedText = "Не обработано";
        private String summarySheetName = "Сводка";
        private String reportSheetNamePrefix = "Отчёт";

        private Builder() {}

        public Builder insertedFill(XSSFColor value) {
            this.insertedFill = Objects.requireNonNull(value, "insertedFill");
            return this;
        }

        public Builder insertedFill(IndexedColors value) {
            return insertedFill(toXssf(value));
        }

        public Builder rejectedFill(XSSFColor value) {
            this.rejectedFill = Objects.requireNonNull(value, "rejectedFill");
            return this;
        }

        public Builder rejectedFill(IndexedColors value) {
            return rejectedFill(toXssf(value));
        }

        public Builder skippedFill(XSSFColor value) {
            this.skippedFill = Objects.requireNonNull(value, "skippedFill");
            return this;
        }

        public Builder skippedFill(IndexedColors value) {
            return skippedFill(toXssf(value));
        }

        public Builder fillPattern(FillPatternType value) {
            this.fillPattern = Objects.requireNonNull(value, "fillPattern");
            return this;
        }

        public Builder statusColumnHeader(String value) {
            this.statusColumnHeader = Objects.requireNonNull(value, "statusColumnHeader");
            return this;
        }

        public Builder reasonColumnHeader(String value) {
            this.reasonColumnHeader = Objects.requireNonNull(value, "reasonColumnHeader");
            return this;
        }

        public Builder reasonAlignment(HorizontalAlignment value) {
            this.reasonAlignment = Objects.requireNonNull(value, "reasonAlignment");
            return this;
        }

        public Builder dateFormat(String value) {
            this.dateFormat = Objects.requireNonNull(value, "dateFormat");
            return this;
        }

        public Builder insertedText(String value) {
            this.insertedText = Objects.requireNonNull(value, "insertedText");
            return this;
        }

        public Builder rejectedText(String value) {
            this.rejectedText = Objects.requireNonNull(value, "rejectedText");
            return this;
        }

        public Builder skippedText(String value) {
            this.skippedText = Objects.requireNonNull(value, "skippedText");
            return this;
        }

        public Builder summarySheetName(String value) {
            this.summarySheetName = Objects.requireNonNull(value, "summarySheetName");
            return this;
        }

        public Builder reportSheetNamePrefix(String value) {
            this.reportSheetNamePrefix = Objects.requireNonNull(value, "reportSheetNamePrefix");
            return this;
        }

        public ReportStyle build() {
            return new ReportStyle(this);
        }

        private static XSSFColor rgb(int red, int green, int blue) {
            return new XSSFColor(new byte[] {(byte) red, (byte) green, (byte) blue});
        }

        private static XSSFColor toXssf(IndexedColors color) {
            short[] triplet = color.getIndex() >= 0
                    ? org.apache.poi.hssf.util.HSSFColor.getIndexHash()
                            .get((int) color.getIndex())
                            .getTriplet()
                    : new short[] {0, 0, 0};
            return rgb(triplet[0], triplet[1], triplet[2]);
        }
    }
}
