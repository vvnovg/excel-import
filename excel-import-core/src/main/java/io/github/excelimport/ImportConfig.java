package io.github.excelimport;

import io.github.excelimport.convert.BooleanWords;
import io.github.excelimport.internal.read.FormulaPolicy;
import io.github.excelimport.report.ReportStyle;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Objects;

/** Иммутабельная конфигурация одного вида импорта. Все проверки — в {@link Builder#build()}. */
public final class ImportConfig {

    private final int batchSize;
    private final SheetSelector sheet;
    private final int headerRow;
    private final int firstDataRow;
    private final boolean skipBlankRows;
    private final boolean expandMergedCells;
    private final FormulaPolicy formulaPolicy;
    private final HeaderMatchingPolicy headerMatching;
    private final NamingStrategy namingStrategy;
    private final TableRef targetTable;
    private final ConflictStrategy conflictStrategy;
    private final int maxErrors;
    private final int maxErrorsInMemory;
    private final int maxSplitDepth;
    private final int maxOutcomeMessagesInMemory;
    private final Path reportPath;
    private final ReportStyle reportStyle;
    private final boolean includeDatabaseDetailInReport;
    private final boolean dryRun;
    private final Locale locale;
    private final Path tempDir;
    private final int queryTimeoutSeconds;
    private final BooleanWords booleanWords;

    private ImportConfig(Builder builder) {
        this.batchSize = builder.batchSize;
        this.sheet = builder.sheet;
        this.headerRow = builder.headerRow;
        this.firstDataRow = builder.firstDataRow < 0 ? builder.headerRow + 1 : builder.firstDataRow;
        this.skipBlankRows = builder.skipBlankRows;
        this.expandMergedCells = builder.expandMergedCells;
        this.formulaPolicy = builder.formulaPolicy;
        this.headerMatching = builder.headerMatching;
        this.namingStrategy = builder.namingStrategy;
        this.targetTable = builder.targetTable;
        this.conflictStrategy = builder.conflictStrategy;
        this.maxErrors = builder.maxErrors;
        this.maxErrorsInMemory = builder.maxErrorsInMemory;
        this.maxSplitDepth = builder.maxSplitDepth;
        this.maxOutcomeMessagesInMemory = builder.maxOutcomeMessagesInMemory;
        this.reportPath = builder.reportPath;
        this.reportStyle = builder.reportStyle;
        this.includeDatabaseDetailInReport = builder.includeDatabaseDetailInReport;
        this.dryRun = builder.dryRun;
        this.locale = builder.locale;
        this.tempDir = builder.tempDir;
        this.queryTimeoutSeconds = builder.queryTimeoutSeconds;
        this.booleanWords = builder.booleanWords;
    }

    public static Builder builder() {
        return new Builder();
    }

    public int batchSize() {
        return batchSize;
    }

    /** null означает «взять из @ExcelSheet». */
    public SheetSelector sheet() {
        return sheet;
    }

    public int headerRow() {
        return headerRow;
    }

    public int firstDataRow() {
        return firstDataRow;
    }

    public boolean skipBlankRows() {
        return skipBlankRows;
    }

    public boolean expandMergedCells() {
        return expandMergedCells;
    }

    public FormulaPolicy formulaPolicy() {
        return formulaPolicy;
    }

    public HeaderMatchingPolicy headerMatching() {
        return headerMatching;
    }

    public NamingStrategy namingStrategy() {
        return namingStrategy;
    }

    /** null означает «взять из @TargetTable». */
    public TableRef targetTable() {
        return targetTable;
    }

    public ConflictStrategy conflictStrategy() {
        return conflictStrategy;
    }

    public int maxErrors() {
        return maxErrors;
    }

    public int maxErrorsInMemory() {
        return maxErrorsInMemory;
    }

    public int maxSplitDepth() {
        return maxSplitDepth;
    }

    public int maxOutcomeMessagesInMemory() {
        return maxOutcomeMessagesInMemory;
    }

    /** null означает «отчёт не генерировать». */
    public Path reportPath() {
        return reportPath;
    }

    public ReportStyle reportStyle() {
        return reportStyle;
    }

    public boolean includeDatabaseDetailInReport() {
        return includeDatabaseDetailInReport;
    }

    public boolean dryRun() {
        return dryRun;
    }

    public Locale locale() {
        return locale;
    }

    public Path tempDir() {
        return tempDir;
    }

    public int queryTimeoutSeconds() {
        return queryTimeoutSeconds;
    }

    public BooleanWords booleanWords() {
        return booleanWords;
    }

    /** Настройки чтения, выведенные из конфигурации. */
    public io.github.excelimport.internal.read.ReadOptions readOptions() {
        return new io.github.excelimport.internal.read.ReadOptions(
                skipBlankRows, expandMergedCells, formulaPolicy);
    }

    /** Билдер. Может переиспользоваться: {@code build()} снимает снимок значений. */
    public static final class Builder {

        private int batchSize = 1000;
        private SheetSelector sheet;
        private int headerRow = 0;
        private int firstDataRow = -1;
        private boolean skipBlankRows = true;
        private boolean expandMergedCells = true;
        private FormulaPolicy formulaPolicy = FormulaPolicy.AS_NULL;
        private HeaderMatchingPolicy headerMatching = HeaderMatchingPolicy.defaults();
        private NamingStrategy namingStrategy = NamingStrategy.SNAKE_CASE;
        private TableRef targetTable;
        private ConflictStrategy conflictStrategy = ConflictStrategy.none();
        private int maxErrors = Integer.MAX_VALUE;
        private int maxErrorsInMemory = 1000;
        private int maxSplitDepth = 16;
        private int maxOutcomeMessagesInMemory = 50_000;
        private Path reportPath;
        private ReportStyle reportStyle = ReportStyle.defaults();
        private boolean includeDatabaseDetailInReport = true;
        private boolean dryRun;
        private Locale locale = Locale.getDefault();
        private Path tempDir = Path.of(System.getProperty("java.io.tmpdir"));
        private int queryTimeoutSeconds;
        private BooleanWords booleanWords = BooleanWords.defaults();

        private Builder() {}

        public Builder batchSize(int value) {
            this.batchSize = value;
            return this;
        }

        public Builder sheet(SheetSelector value) {
            this.sheet = value;
            return this;
        }

        public Builder headerRow(int value) {
            this.headerRow = value;
            return this;
        }

        public Builder firstDataRow(int value) {
            this.firstDataRow = value;
            return this;
        }

        public Builder skipBlankRows(boolean value) {
            this.skipBlankRows = value;
            return this;
        }

        public Builder expandMergedCells(boolean value) {
            this.expandMergedCells = value;
            return this;
        }

        public Builder formulaPolicy(FormulaPolicy value) {
            this.formulaPolicy = Objects.requireNonNull(value, "formulaPolicy");
            return this;
        }

        public Builder headerMatching(HeaderMatchingPolicy value) {
            this.headerMatching = Objects.requireNonNull(value, "headerMatching");
            return this;
        }

        public Builder namingStrategy(NamingStrategy value) {
            this.namingStrategy = Objects.requireNonNull(value, "namingStrategy");
            return this;
        }

        public Builder targetTable(TableRef value) {
            this.targetTable = value;
            return this;
        }

        public Builder conflictStrategy(ConflictStrategy value) {
            this.conflictStrategy = Objects.requireNonNull(value, "conflictStrategy");
            return this;
        }

        public Builder maxErrors(int value) {
            this.maxErrors = value;
            return this;
        }

        public Builder maxErrorsInMemory(int value) {
            this.maxErrorsInMemory = value;
            return this;
        }

        public Builder maxSplitDepth(int value) {
            this.maxSplitDepth = value;
            return this;
        }

        public Builder maxOutcomeMessagesInMemory(int value) {
            this.maxOutcomeMessagesInMemory = value;
            return this;
        }

        public Builder reportPath(Path value) {
            this.reportPath = value;
            return this;
        }

        public Builder reportStyle(ReportStyle value) {
            this.reportStyle = Objects.requireNonNull(value, "reportStyle");
            return this;
        }

        public Builder includeDatabaseDetailInReport(boolean value) {
            this.includeDatabaseDetailInReport = value;
            return this;
        }

        public Builder dryRun(boolean value) {
            this.dryRun = value;
            return this;
        }

        public Builder locale(Locale value) {
            this.locale = Objects.requireNonNull(value, "locale");
            return this;
        }

        public Builder tempDir(Path value) {
            this.tempDir = Objects.requireNonNull(value, "tempDir");
            return this;
        }

        public Builder queryTimeoutSeconds(int value) {
            this.queryTimeoutSeconds = value;
            return this;
        }

        public Builder booleanWords(BooleanWords value) {
            this.booleanWords = Objects.requireNonNull(value, "booleanWords");
            return this;
        }

        public ImportConfig build() {
            if (batchSize < 1) {
                throw new IllegalArgumentException("batchSize должен быть >= 1, получено: " + batchSize);
            }
            if (headerRow < 0) {
                throw new IllegalArgumentException("headerRow должен быть >= 0, получено: " + headerRow);
            }
            if (firstDataRow >= 0 && firstDataRow <= headerRow) {
                throw new IllegalArgumentException(
                        "firstDataRow (" + firstDataRow + ") должен быть больше headerRow (" + headerRow + ")");
            }
            if (maxErrors < 0) {
                throw new IllegalArgumentException("maxErrors должен быть >= 0");
            }
            if (maxErrorsInMemory < 0) {
                throw new IllegalArgumentException("maxErrorsInMemory должен быть >= 0");
            }
            if (maxSplitDepth < 0) {
                throw new IllegalArgumentException("maxSplitDepth должен быть >= 0");
            }
            if (maxOutcomeMessagesInMemory < 0) {
                throw new IllegalArgumentException("maxOutcomeMessagesInMemory должен быть >= 0");
            }
            if (queryTimeoutSeconds < 0) {
                throw new IllegalArgumentException("queryTimeoutSeconds должен быть >= 0");
            }
            return new ImportConfig(this);
        }
    }
}
