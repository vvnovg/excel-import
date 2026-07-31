package org.novgorodtsev.excelimport.spring;

import org.novgorodtsev.excelimport.ConflictStrategy;
import org.novgorodtsev.excelimport.ImportConfig;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Свойства под префиксом {@code excel-import}. Значения по умолчанию совпадают с ядром. */
@ConfigurationProperties(prefix = "excel-import")
public class ExcelImportProperties {

    /** Стратегия обработки конфликтов вставки. */
    public enum ConflictMode {
        NONE,
        DO_NOTHING,
        DO_UPDATE
    }

    private int batchSize = 1000;
    private int maxErrors = Integer.MAX_VALUE;
    private int maxErrorsInMemory = 1000;
    private int maxSplitDepth = 16;
    private int maxOutcomeMessagesInMemory = 50_000;
    private boolean skipBlankRows = true;
    private boolean expandMergedCells = true;
    private boolean includeDatabaseDetailInReport = true;
    private boolean dryRun;
    private int queryTimeoutSeconds;
    private Locale locale = Locale.getDefault();
    private Path tempDir = Path.of(System.getProperty("java.io.tmpdir"));
    private final Report report = new Report();
    private final Conflict conflict = new Conflict();

    /** Настройки Excel-отчёта. */
    public static class Report {

        private boolean enabled;
        private Path directory;
        /** Шаблон имени файла; {@code {name}} заменяется на имя исходного файла без расширения. */
        private String fileNamePattern = "{name}-report.xlsx";

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public Path getDirectory() {
            return directory;
        }

        public void setDirectory(Path directory) {
            this.directory = directory;
        }

        public String getFileNamePattern() {
            return fileNamePattern;
        }

        public void setFileNamePattern(String fileNamePattern) {
            this.fileNamePattern = fileNamePattern;
        }
    }

    /** Настройки ON CONFLICT. */
    public static class Conflict {

        private ConflictMode strategy = ConflictMode.NONE;
        private List<String> columns = new ArrayList<>();
        private List<String> updateColumns = new ArrayList<>();

        public ConflictMode getStrategy() {
            return strategy;
        }

        public void setStrategy(ConflictMode strategy) {
            this.strategy = strategy;
        }

        public List<String> getColumns() {
            return columns;
        }

        public void setColumns(List<String> columns) {
            this.columns = columns;
        }

        public List<String> getUpdateColumns() {
            return updateColumns;
        }

        public void setUpdateColumns(List<String> updateColumns) {
            this.updateColumns = updateColumns;
        }

        ConflictStrategy toConflictStrategy() {
            return switch (strategy) {
                case NONE -> ConflictStrategy.none();
                case DO_NOTHING -> ConflictStrategy.doNothing(columns.toArray(String[]::new));
                case DO_UPDATE -> ConflictStrategy.doUpdate(columns, updateColumns);
            };
        }
    }

    /** Собирает {@link ImportConfig} без пути отчёта — путь зависит от имени файла. */
    public ImportConfig toImportConfig() {
        return toImportConfig(null);
    }

    /**
     * Собирает {@link ImportConfig}, применяя все настроенные свойства и указанный путь
     * отчёта. {@code null} эквивалентен {@link #toImportConfig()}. Единственная точка сборки
     * билдера — {@link #toImportConfig()} делегирует сюда, чтобы два метода не разошлись.
     */
    public ImportConfig toImportConfig(Path reportPath) {
        return ImportConfig.builder()
                .batchSize(batchSize)
                .maxErrors(maxErrors)
                .maxErrorsInMemory(maxErrorsInMemory)
                .maxSplitDepth(maxSplitDepth)
                .maxOutcomeMessagesInMemory(maxOutcomeMessagesInMemory)
                .skipBlankRows(skipBlankRows)
                .expandMergedCells(expandMergedCells)
                .includeDatabaseDetailInReport(includeDatabaseDetailInReport)
                .dryRun(dryRun)
                .queryTimeoutSeconds(queryTimeoutSeconds)
                .locale(locale)
                .tempDir(tempDir)
                .conflictStrategy(conflict.toConflictStrategy())
                .reportPath(reportPath)
                .build();
    }

    /** Путь отчёта для конкретного исходного файла; null, если отчёты выключены. */
    public Path reportPathFor(String sourceFileName) {
        if (!report.isEnabled() || report.getDirectory() == null) {
            return null;
        }
        String base = sourceFileName.replaceFirst("\\.[^.]+$", "");
        return report.getDirectory().resolve(report.getFileNamePattern().replace("{name}", base));
    }

    public int getBatchSize() {
        return batchSize;
    }

    public void setBatchSize(int batchSize) {
        this.batchSize = batchSize;
    }

    public int getMaxErrors() {
        return maxErrors;
    }

    public void setMaxErrors(int maxErrors) {
        this.maxErrors = maxErrors;
    }

    public int getMaxErrorsInMemory() {
        return maxErrorsInMemory;
    }

    public void setMaxErrorsInMemory(int maxErrorsInMemory) {
        this.maxErrorsInMemory = maxErrorsInMemory;
    }

    public int getMaxSplitDepth() {
        return maxSplitDepth;
    }

    public void setMaxSplitDepth(int maxSplitDepth) {
        this.maxSplitDepth = maxSplitDepth;
    }

    public int getMaxOutcomeMessagesInMemory() {
        return maxOutcomeMessagesInMemory;
    }

    public void setMaxOutcomeMessagesInMemory(int value) {
        this.maxOutcomeMessagesInMemory = value;
    }

    public boolean isSkipBlankRows() {
        return skipBlankRows;
    }

    public void setSkipBlankRows(boolean skipBlankRows) {
        this.skipBlankRows = skipBlankRows;
    }

    public boolean isExpandMergedCells() {
        return expandMergedCells;
    }

    public void setExpandMergedCells(boolean expandMergedCells) {
        this.expandMergedCells = expandMergedCells;
    }

    public boolean isIncludeDatabaseDetailInReport() {
        return includeDatabaseDetailInReport;
    }

    public void setIncludeDatabaseDetailInReport(boolean value) {
        this.includeDatabaseDetailInReport = value;
    }

    public boolean isDryRun() {
        return dryRun;
    }

    public void setDryRun(boolean dryRun) {
        this.dryRun = dryRun;
    }

    public int getQueryTimeoutSeconds() {
        return queryTimeoutSeconds;
    }

    public void setQueryTimeoutSeconds(int queryTimeoutSeconds) {
        this.queryTimeoutSeconds = queryTimeoutSeconds;
    }

    public Locale getLocale() {
        return locale;
    }

    public void setLocale(Locale locale) {
        this.locale = locale;
    }

    public Path getTempDir() {
        return tempDir;
    }

    public void setTempDir(Path tempDir) {
        this.tempDir = tempDir;
    }

    public Report getReport() {
        return report;
    }

    public Conflict getConflict() {
        return conflict;
    }
}
