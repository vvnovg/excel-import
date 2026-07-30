package io.github.excelimport.internal;

import io.github.excelimport.ErrorKind;
import io.github.excelimport.ImportConfig;
import io.github.excelimport.ImportListener;
import io.github.excelimport.ImportReport;
import io.github.excelimport.ImportRunInfo;
import io.github.excelimport.ImportStatus;
import io.github.excelimport.RowError;
import io.github.excelimport.RowOutcome;
import io.github.excelimport.RowRef;
import io.github.excelimport.exception.ImportAbortedException;
import io.github.excelimport.exception.ReportGenerationException;
import io.github.excelimport.internal.map.HeaderResolver;
import io.github.excelimport.internal.map.MappingModel;
import io.github.excelimport.internal.map.MappingResult;
import io.github.excelimport.internal.map.ResolvedColumns;
import io.github.excelimport.internal.map.RowMapper;
import io.github.excelimport.internal.read.RawRow;
import io.github.excelimport.internal.read.ReadOptions;
import io.github.excelimport.internal.read.StreamingSheetReader;
import io.github.excelimport.internal.report.ReportWriter;
import io.github.excelimport.internal.validate.BeanValidator;
import io.github.excelimport.internal.write.BatchProcessor;
import io.github.excelimport.outcome.RowOutcomeStore;
import java.nio.file.Path;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Оркестрация одного вызова {@code importFile}. Одноразовый объект: всё изменяемое
 * состояние прогона живёт здесь, а не в переиспользуемом {@code ExcelImporter}.
 */
public final class ImportRun<T> {

    private static final Logger log = LoggerFactory.getLogger(ImportRun.class);

    /** Сигнал раннего выхода из потокового чтения при достижении лимита ошибок. */
    private static final class AbortReading extends RuntimeException {
        private static final long serialVersionUID = 1L;

        AbortReading() {
            super(null, null, false, false);
        }
    }

    private final MappingModel<T> model;
    private final ImportConfig config;
    private final StreamingSheetReader reader;
    private final BeanValidator beanValidator;
    private final BatchProcessor<T> processor;
    private final RowOutcomeStore outcomes;
    private final ReportWriter reportWriter;
    private final ImportListener listener;
    private final RowMapperFactory<T> rowMapperFactory;

    /** Абстракция над созданием маппера — нужна, чтобы прогон не знал про реестр конвертеров. */
    public interface RowMapperFactory<T> {
        RowMapper<T> create(MappingModel<T> model, ResolvedColumns resolved);
    }

    private final UUID runId = UUID.randomUUID();
    private final List<RowError> collectedErrors = new ArrayList<>();
    private final List<RowRef<T>> currentBatch = new ArrayList<>();

    private RowMapper<T> mapper;
    private long totalRows;
    private long insertedRows;
    private long rejectedRows;
    private int batchesCommitted;
    private int batchIndex;
    private boolean errorLimitReached;
    private SQLException fatalFailure;

    public ImportRun(
            MappingModel<T> model,
            ImportConfig config,
            StreamingSheetReader reader,
            BeanValidator beanValidator,
            BatchProcessor<T> processor,
            RowOutcomeStore outcomes,
            ReportWriter reportWriter,
            ImportListener listener,
            RowMapperFactory<T> rowMapperFactory) {
        this.model = model;
        this.config = config;
        this.reader = reader;
        this.beanValidator = beanValidator;
        this.processor = processor;
        this.outcomes = outcomes;
        this.reportWriter = reportWriter;
        this.listener = listener;
        this.rowMapperFactory = rowMapperFactory;
    }

    public ImportReport execute(Path source, String sourceName) {
        Instant startedAt = Instant.now();
        notifyListener(() -> listener.onImportStarted(new ImportRunInfo(
                runId, sourceName, model.table(), config.batchSize(), startedAt)));

        ReadOptions readOptions = config.readOptions();
        try {
            try {
                reader.forEachRow(source, model.sheet(), readOptions, this::handleRow);
                // финальный сброс тоже может поднять AbortReading (лимит ошибок был
                // достигнут раньше отклонёнными строками) — ловим здесь же, маркер
                // не должен уходить наружу
                if (fatalFailure == null) {
                    flushBatch();
                }
            } catch (AbortReading stoppedEarly) {
                log.warn(
                        "чтение прервано досрочно: {}",
                        errorLimitReached
                                ? "достигнут предел ошибок (" + config.maxErrors() + ")"
                                : "фатальная ошибка БД");
            }
        } catch (SQLException e) {
            fatalFailure = e;
        }

        ImportStatus status = resolveStatus();
        ImportReport report = buildReport(sourceName, startedAt, status, null);

        Path reportPath = writeReportIfRequested(source, readOptions, report);
        if (config.reportPath() != null) {
            // перестраиваем отчёт: в нём должен быть путь к файлу либо, если запись
            // не удалась, статус PARTIAL и сообщение об этом в errors (§6 спеки)
            ImportStatus afterReport =
                    reportPath == null && status == ImportStatus.SUCCESS ? ImportStatus.PARTIAL : status;
            report = buildReport(sourceName, startedAt, afterReport, reportPath);
        }

        ImportReport finalReport = report;
        notifyListener(() -> listener.onImportFinished(finalReport));

        if (fatalFailure != null) {
            throw new ImportAbortedException(
                    "импорт прерван фатальной ошибкой БД: " + fatalFailure.getMessage(),
                    fatalFailure,
                    finalReport);
        }
        if (errorLimitReached) {
            throw new ImportAbortedException(
                    "импорт прерван: превышен предел ошибок (" + config.maxErrors() + ")",
                    finalReport);
        }
        return finalReport;
    }

    private void handleRow(RawRow row) {
        if (row.rowIndex() < model.headerRowIndex()) {
            return;
        }
        if (row.rowIndex() == model.headerRowIndex()) {
            ResolvedColumns resolved = HeaderResolver.resolve(model, row, config.headerMatching());
            mapper = rowMapperFactory.create(model, resolved);
            return;
        }
        if (row.rowIndex() < model.firstDataRowIndex()) {
            return;
        }
        if (mapper == null) {
            // строка заголовка отсутствует в файле вовсе
            throw new io.github.excelimport.exception.FileStructureException(
                    "в файле нет строки заголовка с индексом " + model.headerRowIndex());
        }

        totalRows++;
        int rowNum = row.excelRowNumber();

        MappingResult<T> mapped = mapper.map(row);
        List<RowError> errors = new ArrayList<>(mapped.errors());
        if (errors.isEmpty()) {
            errors.addAll(beanValidator.validate(mapped.value(), rowNum));
        }
        if (!errors.isEmpty()) {
            rejectRow(rowNum, errors);
            return;
        }

        currentBatch.add(new RowRef<>(rowNum, mapped.value()));
        if (currentBatch.size() >= config.batchSize()) {
            try {
                flushBatch();
            } catch (SQLException e) {
                fatalFailure = e;
                throw new AbortReading();
            }
        }
    }

    private void rejectRow(int rowNum, List<RowError> errors) {
        rejectedRows++;
        outcomes.put(rowNum, RowOutcome.rejected(joinMessages(errors)));
        for (RowError error : errors) {
            recordError(error);
        }
        checkErrorLimit();
    }

    private void flushBatch() throws SQLException {
        if (currentBatch.isEmpty()) {
            return;
        }
        batchIndex++;
        List<RowRef<T>> batch = List.copyOf(currentBatch);
        currentBatch.clear();

        BatchProcessor.BatchOutcome outcome = processor.process(batch);
        if (outcome.statementCount() > 1) {
            int finalBatchIndex = batchIndex;
            int size = batch.size();
            notifyListener(() -> listener.onBatchSplit(finalBatchIndex, outcome.statementCount(), size));
        }

        Map<Integer, List<RowError>> errorsByRow = outcome.errors().stream()
                .collect(Collectors.groupingBy(RowError::rowNum));

        for (RowRef<T> row : batch) {
            List<RowError> rowErrors = errorsByRow.get(row.rowNum());
            if (rowErrors == null) {
                outcomes.put(row.rowNum(), RowOutcome.inserted());
            } else {
                rejectedRows++;
                outcomes.put(row.rowNum(), RowOutcome.rejected(joinMessages(rowErrors)));
                rowErrors.forEach(this::recordError);
            }
        }

        insertedRows += outcome.insertedCount();
        batchesCommitted++;
        int inserted = outcome.insertedCount();
        int finalBatchIndex = batchIndex;
        long total = insertedRows;
        notifyListener(() -> listener.onBatchCommitted(finalBatchIndex, inserted, total));

        checkErrorLimit();
        if (errorLimitReached) {
            throw new AbortReading();
        }
    }

    private void recordError(RowError error) {
        if (collectedErrors.size() < config.maxErrorsInMemory()) {
            collectedErrors.add(error);
        }
        notifyListener(() -> listener.onRowRejected(error));
    }

    private void checkErrorLimit() {
        if (rejectedRows > config.maxErrors()) {
            errorLimitReached = true;
        }
    }

    /**
     * К сообщениям с известной колонкой добавляется её заголовок — само сообщение
     * (например, «… не является целым числом») контекста колонки не содержит, а в
     * отчёте причина должна читаться без подглядывания в исходник.
     */
    private static String joinMessages(List<RowError> errors) {
        return errors.stream()
                .map(error -> error.columnHeader() == null
                        ? error.message()
                        : error.columnHeader() + ": " + error.message())
                .collect(Collectors.joining("; "));
    }

    private ImportStatus resolveStatus() {
        if (fatalFailure != null || errorLimitReached) {
            return ImportStatus.FAILED;
        }
        return rejectedRows == 0 ? ImportStatus.SUCCESS : ImportStatus.PARTIAL;
    }

    private ImportReport buildReport(
            String sourceName, Instant startedAt, ImportStatus status, Path reportPath) {
        return new ImportReport(
                runId,
                sourceName,
                totalRows,
                insertedRows,
                rejectedRows,
                batchesCommitted,
                Duration.between(startedAt, Instant.now()),
                reportPath,
                List.copyOf(collectedErrors),
                errorLimitReached,
                status);
    }

    private Path writeReportIfRequested(Path source, ReadOptions readOptions, ImportReport report) {
        if (config.reportPath() == null || reportWriter == null) {
            return null;
        }
        outcomes.seal();
        try {
            reportWriter.write(
                    source, model.sheet(), readOptions, outcomes, report, config.reportPath());
            return config.reportPath();
        } catch (ReportGenerationException e) {
            log.warn("импорт выполнен, но отчёт не сформирован: {}", e.getMessage());
            collectedErrors.add(new RowError(
                    1, null, null, ErrorKind.STRUCTURE, "REPORT_FAILED",
                    "не удалось сформировать отчёт: " + e.getMessage()));
            return null;
        }
    }

    private void notifyListener(Runnable action) {
        try {
            action.run();
        } catch (RuntimeException e) {
            log.warn("ImportListener бросил исключение, продолжаю импорт: {}", e.toString());
        }
    }
}
