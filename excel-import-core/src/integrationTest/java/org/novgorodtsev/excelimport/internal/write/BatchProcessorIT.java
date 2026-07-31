package org.novgorodtsev.excelimport.internal.write;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.novgorodtsev.excelimport.ConflictStrategy;
import org.novgorodtsev.excelimport.NamingStrategy;
import org.novgorodtsev.excelimport.RowError;
import org.novgorodtsev.excelimport.RowRef;
import org.novgorodtsev.excelimport.TableRef;
import org.novgorodtsev.excelimport.annotation.Column;
import org.novgorodtsev.excelimport.annotation.ExcelColumn;
import org.novgorodtsev.excelimport.annotation.ExcelSheet;
import org.novgorodtsev.excelimport.annotation.TargetTable;
import org.novgorodtsev.excelimport.internal.map.MappingModel;
import org.novgorodtsev.excelimport.internal.map.MappingModelFactory;
import org.novgorodtsev.excelimport.testsupport.PostgresSupport;
import org.novgorodtsev.excelimport.validate.BatchValidator;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class BatchProcessorIT {

    @ExcelSheet(name = "S")
    @TargetTable(name = "employee")
    public static class Employee {
        @ExcelColumn(header = "Номер")
        @Column("personnel_no")
        public Long personnelNo;

        @ExcelColumn(header = "ФИО")
        @Column("full_name")
        public String fullName;

        public Employee() {}

        static Employee of(long no, String name) {
            Employee employee = new Employee();
            employee.personnelNo = no;
            employee.fullName = name;
            return employee;
        }
    }

    private final DataSource dataSource = PostgresSupport.dataSource();
    private final MappingModel<Employee> model =
            MappingModelFactory.create(Employee.class, NamingStrategy.SNAKE_CASE);

    @BeforeEach
    void resetTable() {
        PostgresSupport.execute(
                "DROP TABLE IF EXISTS employee",
                "CREATE TABLE employee ("
                        + "personnel_no bigint PRIMARY KEY, "
                        + "full_name text NOT NULL)");
    }

    private BatchProcessor<Employee> processor(ConflictStrategy conflict, List<BatchValidator<Employee>> validators) {
        SqlBuilder sqlBuilder = new SqlBuilder(TableRef.of("employee"), model.allDbColumns(), conflict);
        InsertExecutor<Employee> executor =
                new InsertExecutor<>(sqlBuilder, new RowBinder<>(model.allBindings()), 0);
        BatchSplitter<Employee> splitter =
                new BatchSplitter<>(16, new DefaultSqlErrorClassifier(), true);
        return new BatchProcessor<>(dataSource, executor, splitter, validators, false, 1000);
    }

    private static List<RowRef<Employee>> batch(long... numbers) {
        List<RowRef<Employee>> rows = new ArrayList<>();
        int rowNum = 1;
        for (long number : numbers) {
            rows.add(new RowRef<>(rowNum++, Employee.of(number, "Сотрудник " + number)));
        }
        return rows;
    }

    @Test
    void insertsWholeBatchInOneTransaction() throws SQLException {
        BatchProcessor.BatchOutcome outcome = processor(ConflictStrategy.none(), List.of())
                .process(batch(1, 2, 3));

        assertThat(outcome.insertedCount()).isEqualTo(3);
        assertThat(outcome.errors()).isEmpty();
        assertThat(PostgresSupport.countRows("employee")).isEqualTo(3);
    }

    @Test
    void duplicateKeyInsideBatchIsIsolatedByBisection() throws SQLException {
        // строка 2 конфликтует со строкой 1 по первичному ключу
        BatchProcessor.BatchOutcome outcome = processor(ConflictStrategy.none(), List.of())
                .process(batch(1, 1, 2, 3));

        assertThat(outcome.errors()).hasSize(1);
        assertThat(outcome.insertedCount()).isEqualTo(3);
        assertThat(PostgresSupport.countRows("employee")).isEqualTo(3);
    }

    @Test
    void databaseErrorMessageMentionsConstraint() throws SQLException {
        BatchProcessor.BatchOutcome outcome = processor(ConflictStrategy.none(), List.of())
                .process(batch(1, 1));

        assertThat(outcome.errors()).singleElement().satisfies(error -> {
            assertThat(error.code()).isEqualTo("23505");
            assertThat(error.message()).contains("employee_pkey");
        });
    }

    @Test
    void onConflictDoNothingSwallowsDuplicates() throws SQLException {
        BatchProcessor<Employee> processor =
                processor(ConflictStrategy.doNothing("personnel_no"), List.of());

        processor.process(batch(1, 2));
        BatchProcessor.BatchOutcome second = processor.process(batch(2, 3));

        assertThat(second.errors()).isEmpty();
        assertThat(second.insertedCount()).isEqualTo(1); // только 3
        assertThat(PostgresSupport.countRows("employee")).isEqualTo(3);
    }

    @Test
    void onConflictDoUpdateOverwritesExistingRow() throws SQLException {
        processor(ConflictStrategy.none(), List.of()).process(batch(1));

        BatchProcessor<Employee> upserting = processor(
                ConflictStrategy.doUpdate(List.of("personnel_no"), List.of("full_name")), List.of());
        upserting.process(List.of(new RowRef<>(1, Employee.of(1, "Новое имя"))));

        assertThat(PostgresSupport.countRows("employee")).isEqualTo(1);
        assertThat(selectName(1)).isEqualTo("Новое имя");
    }

    @Test
    void batchValidatorSeesRowsCommittedByPreviousBatch() throws SQLException {
        BatchValidator<Employee> noDuplicatesInDb = (rows, connection) -> {
            List<RowError> errors = new ArrayList<>();
            try (var statement = connection.prepareStatement(
                    "SELECT personnel_no FROM employee WHERE personnel_no = ANY (?)")) {
                Long[] numbers = rows.stream().map(row -> row.value().personnelNo).toArray(Long[]::new);
                statement.setArray(1, connection.createArrayOf("bigint", numbers));
                try (var rs = statement.executeQuery()) {
                    List<Long> existing = new ArrayList<>();
                    while (rs.next()) {
                        existing.add(rs.getLong(1));
                    }
                    for (RowRef<Employee> row : rows) {
                        if (existing.contains(row.value().personnelNo)) {
                            errors.add(RowError.batch(
                                    row.rowNum(), "ALREADY_EXISTS", "запись уже есть в БД"));
                        }
                    }
                }
            }
            return errors;
        };

        BatchProcessor<Employee> processor =
                processor(ConflictStrategy.none(), List.of(noDuplicatesInDb));
        processor.process(batch(1, 2));
        BatchProcessor.BatchOutcome second = processor.process(batch(2, 3));

        assertThat(second.errors()).singleElement()
                .satisfies(error -> assertThat(error.code()).isEqualTo("ALREADY_EXISTS"));
        assertThat(second.insertedCount()).isEqualTo(1);
        assertThat(PostgresSupport.countRows("employee")).isEqualTo(3);
    }

    @Test
    void validatorAttemptingCommitIsRejected() {
        BatchValidator<Employee> misbehaving = (rows, connection) -> {
            connection.commit();
            return List.of();
        };

        assertThatThrownBy(() -> processor(ConflictStrategy.none(), List.of(misbehaving)).process(batch(1)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("commit");
        assertThat(PostgresSupport.countRows("employee")).isZero();
    }

    @Test
    void missingTableIsFatalAndPropagates() {
        PostgresSupport.execute("DROP TABLE IF EXISTS employee");

        assertThatThrownBy(() -> processor(ConflictStrategy.none(), List.of()).process(batch(1)))
                .isInstanceOf(SQLException.class)
                .satisfies(e -> assertThat(((SQLException) e).getSQLState()).isEqualTo("42P01"));
    }

    @Test
    void dryRunRollsBackEverything() throws SQLException {
        SqlBuilder sqlBuilder =
                new SqlBuilder(TableRef.of("employee"), model.allDbColumns(), ConflictStrategy.none());
        BatchProcessor<Employee> dryRun = new BatchProcessor<>(
                dataSource,
                new InsertExecutor<>(sqlBuilder, new RowBinder<>(model.allBindings()), 0),
                new BatchSplitter<>(16, new DefaultSqlErrorClassifier(), true),
                List.of(),
                true,
                1000);

        dryRun.process(batch(1, 2, 3));

        assertThat(PostgresSupport.countRows("employee")).isZero();
    }

    private String selectName(long personnelNo) throws SQLException {
        try (var connection = dataSource.getConnection();
                var statement =
                        connection.prepareStatement("SELECT full_name FROM employee WHERE personnel_no = ?")) {
            statement.setLong(1, personnelNo);
            try (var rs = statement.executeQuery()) {
                rs.next();
                return rs.getString(1);
            }
        }
    }
}
