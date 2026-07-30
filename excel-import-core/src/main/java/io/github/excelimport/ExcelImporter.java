package io.github.excelimport;

import io.github.excelimport.convert.CellConverter;
import io.github.excelimport.exception.ExcelImportException;
import io.github.excelimport.exception.MappingConfigurationException;
import io.github.excelimport.internal.ImportRun;
import io.github.excelimport.internal.convert.ConverterRegistry;
import io.github.excelimport.internal.map.MappingModel;
import io.github.excelimport.internal.map.MappingModelFactory;
import io.github.excelimport.internal.map.RowMapper;
import io.github.excelimport.internal.outcome.SpillableRowOutcomeStore;
import io.github.excelimport.internal.read.PoiStreamingSheetReader;
import io.github.excelimport.internal.read.StreamingSheetReader;
import io.github.excelimport.internal.report.ReportWriter;
import io.github.excelimport.internal.validate.BeanValidator;
import io.github.excelimport.internal.write.BatchProcessor;
import io.github.excelimport.internal.write.BatchSplitter;
import io.github.excelimport.internal.write.DefaultSqlErrorClassifier;
import io.github.excelimport.internal.write.InsertExecutor;
import io.github.excelimport.internal.write.RowBinder;
import io.github.excelimport.internal.write.SqlBuilder;
import io.github.excelimport.outcome.RowOutcomeStore;
import io.github.excelimport.report.ReportRowCustomizer;
import io.github.excelimport.validate.BatchValidator;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;
import javax.sql.DataSource;

/**
 * Точка входа библиотеки. Потокобезопасен и переиспользуем: разбор аннотаций,
 * шаблон SQL и {@code ValidatorFactory} считаются один раз при сборке.
 * Один вызов {@link #importFile(Path)} — один изолированный прогон.
 */
public final class ExcelImporter<T> implements AutoCloseable {

    private final MappingModel<T> model;
    private final ImportConfig config;
    private final StreamingSheetReader reader;
    private final ConverterRegistry converters;
    private final BeanValidator beanValidator;
    private final DataSource dataSource;
    private final List<BatchValidator<T>> batchValidators;
    private final SqlErrorClassifier sqlErrorClassifier;
    private final ReportRowCustomizer reportRowCustomizer;
    private final ImportListener listener;
    private final Supplier<RowOutcomeStore> outcomeStoreFactory;
    private final SqlBuilder sqlBuilder;
    private final RowBinder<T> rowBinder;

    private ExcelImporter(Builder<T> builder) {
        this.config = builder.config;
        MappingModel<T> parsed = MappingModelFactory.create(builder.type, config.namingStrategy());
        if (config.targetTable() != null) {
            parsed = parsed.withTable(config.targetTable());
        }
        if (parsed.table() == null) {
            throw new MappingConfigurationException(
                    "не задана таблица-приёмник: добавьте @TargetTable на " + builder.type.getName()
                            + " или ImportConfig.targetTable(...)");
        }
        if (config.sheet() != null) {
            parsed = parsed.withSheet(config.sheet(), config.headerRow(), config.firstDataRow());
        }
        this.model = parsed;
        this.reader = builder.reader != null ? builder.reader : new PoiStreamingSheetReader();
        this.converters = builder.converters;
        this.beanValidator = new BeanValidator(config.locale(), model);
        this.dataSource = builder.dataSource;
        this.batchValidators = List.copyOf(builder.batchValidators);
        this.sqlErrorClassifier = builder.sqlErrorClassifier != null
                ? builder.sqlErrorClassifier
                : new DefaultSqlErrorClassifier();
        this.reportRowCustomizer = builder.reportRowCustomizer;
        this.listener = builder.listener != null ? builder.listener : new ImportListener() {};
        this.outcomeStoreFactory = builder.outcomeStoreFactory != null
                ? builder.outcomeStoreFactory
                : () -> new SpillableRowOutcomeStore(
                        config.tempDir(), config.maxOutcomeMessagesInMemory());
        this.sqlBuilder =
                new SqlBuilder(model.table(), model.allDbColumns(), config.conflictStrategy());
        this.rowBinder = new RowBinder<>(model.allBindings());
        // Ранняя проверка конвертеров: ошибки конфигурации должны всплывать здесь,
        // а не на середине импорта.
        model.excelColumns().forEach(converters::resolve);
    }

    public static <T> Builder<T> builder(Class<T> type) {
        return new Builder<>(type);
    }

    /** Импортирует файл. Файл читается дважды: данные и затем генерация отчёта. */
    public ImportReport importFile(Path source) {
        Objects.requireNonNull(source, "source");
        if (!Files.isReadable(source)) {
            throw new ExcelImportException("файл недоступен для чтения: " + source);
        }
        return run(source, source.getFileName().toString());
    }

    /**
     * Импортирует поток. Поток копируется во временный файл, потому что нужны два прохода;
     * временный файл удаляется по завершении.
     */
    public ImportReport importFile(InputStream source, String sourceName) {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(sourceName, "sourceName");
        Path temporary;
        try {
            temporary = Files.createTempFile(config.tempDir(), "excel-import-", ".xlsx");
            Files.copy(source, temporary, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new ExcelImportException("не удалось скопировать поток во временный файл", e);
        }
        try {
            return run(temporary, sourceName);
        } finally {
            try {
                Files.deleteIfExists(temporary);
            } catch (IOException e) {
                // временный файл останется в tempDir; это не повод падать
            }
        }
    }

    private ImportReport run(Path source, String sourceName) {
        InsertExecutor<T> executor =
                new InsertExecutor<>(sqlBuilder, rowBinder, config.queryTimeoutSeconds());
        BatchSplitter<T> splitter = new BatchSplitter<>(
                config.maxSplitDepth(), sqlErrorClassifier, config.includeDatabaseDetailInReport());
        BatchProcessor<T> processor = new BatchProcessor<>(
                dataSource, executor, splitter, batchValidators, config.dryRun(), config.batchSize());
        ReportWriter reportWriter = config.reportPath() == null
                ? null
                : new ReportWriter(reader, config.reportStyle(), reportRowCustomizer);

        try (RowOutcomeStore outcomes = outcomeStoreFactory.get()) {
            ImportRun<T> importRun = new ImportRun<>(
                    model,
                    config,
                    reader,
                    beanValidator,
                    processor,
                    outcomes,
                    reportWriter,
                    listener,
                    (m, resolved) -> new RowMapper<>(
                            m, resolved, converters, config.locale(), config.booleanWords()));
            return importRun.execute(source, sourceName);
        }
    }

    @Override
    public void close() {
        beanValidator.close();
    }

    /** Сборщик импортёра. Не потокобезопасен; собранный {@link ExcelImporter} — да. */
    public static final class Builder<T> {

        private final Class<T> type;
        private ImportConfig config = ImportConfig.builder().build();
        private DataSource dataSource;
        private StreamingSheetReader reader;
        private final ConverterRegistry converters = new ConverterRegistry();
        private final List<BatchValidator<T>> batchValidators = new ArrayList<>();
        private SqlErrorClassifier sqlErrorClassifier;
        private ReportRowCustomizer reportRowCustomizer;
        private ImportListener listener;
        private Supplier<RowOutcomeStore> outcomeStoreFactory;

        private Builder(Class<T> type) {
            this.type = Objects.requireNonNull(type, "type");
        }

        public Builder<T> config(ImportConfig value) {
            this.config = Objects.requireNonNull(value, "config");
            return this;
        }

        public Builder<T> dataSource(DataSource value) {
            this.dataSource = Objects.requireNonNull(value, "dataSource");
            return this;
        }

        /** Подмена читателя — только для тестов библиотеки. */
        Builder<T> reader(StreamingSheetReader value) {
            this.reader = value;
            return this;
        }

        public Builder<T> converter(Class<?> targetType, CellConverter<?> converter) {
            converters.register(targetType, converter);
            return this;
        }

        public Builder<T> batchValidator(BatchValidator<T> validator) {
            batchValidators.add(Objects.requireNonNull(validator, "validator"));
            return this;
        }

        public Builder<T> sqlErrorClassifier(SqlErrorClassifier value) {
            this.sqlErrorClassifier = value;
            return this;
        }

        public Builder<T> reportRowCustomizer(ReportRowCustomizer value) {
            this.reportRowCustomizer = value;
            return this;
        }

        public Builder<T> listener(ImportListener value) {
            this.listener = value;
            return this;
        }

        public Builder<T> outcomeStoreFactory(Supplier<RowOutcomeStore> value) {
            this.outcomeStoreFactory = value;
            return this;
        }

        public ExcelImporter<T> build() {
            if (dataSource == null) {
                throw new IllegalStateException(
                        "не задан dataSource: ExcelImporter.builder(...).dataSource(ds)");
            }
            return new ExcelImporter<>(this);
        }
    }
}
