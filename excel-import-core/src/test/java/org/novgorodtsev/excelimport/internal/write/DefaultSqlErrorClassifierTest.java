package org.novgorodtsev.excelimport.internal.write;

import static org.assertj.core.api.Assertions.assertThat;

import org.novgorodtsev.excelimport.SqlErrorClassifier;
import java.sql.SQLException;
import org.junit.jupiter.api.Test;

class DefaultSqlErrorClassifierTest {

    private final SqlErrorClassifier classifier = new DefaultSqlErrorClassifier();

    @Test
    void connectionFailureIsFatal() {
        assertThat(classifier.isFatal(new SQLException("connection lost", "08006"))).isTrue();
    }

    @Test
    void missingTableIsFatal() {
        assertThat(classifier.isFatal(new SQLException("relation does not exist", "42P01"))).isTrue();
    }

    @Test
    void missingColumnIsFatal() {
        assertThat(classifier.isFatal(new SQLException("column does not exist", "42703"))).isTrue();
    }

    @Test
    void insufficientPrivilegeIsFatal() {
        assertThat(classifier.isFatal(new SQLException("permission denied", "42501"))).isTrue();
    }

    @Test
    void outOfResourcesIsFatal() {
        assertThat(classifier.isFatal(new SQLException("disk full", "53100"))).isTrue();
    }

    @Test
    void uniqueViolationIsNotFatal() {
        assertThat(classifier.isFatal(new SQLException("duplicate key", "23505"))).isFalse();
    }

    @Test
    void notNullViolationIsNotFatal() {
        assertThat(classifier.isFatal(new SQLException("null value", "23502"))).isFalse();
    }

    @Test
    void dataExceptionIsNotFatal() {
        assertThat(classifier.isFatal(new SQLException("numeric overflow", "22003"))).isFalse();
    }

    @Test
    void unknownSqlStateIsTreatedAsFatal() {
        // консервативно: неизвестное состояние лучше не делить пополам вслепую
        assertThat(classifier.isFatal(new SQLException("что-то странное", "XX000"))).isTrue();
    }

    @Test
    void nullSqlStateIsFatal() {
        assertThat(classifier.isFatal(new SQLException("без состояния"))).isTrue();
    }

    @Test
    void fatalCauseInsideBatchExceptionIsDetected() {
        SQLException fatal = new SQLException("connection lost", "08006");
        SQLException batch = new java.sql.BatchUpdateException(new int[0], fatal);

        assertThat(classifier.isFatal(batch)).isTrue();
    }
}
