package io.github.excelimport.internal.write;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import org.junit.jupiter.api.Test;

class GuardedConnectionTest {

    @Test
    void queryMethodsAreDelegated() throws SQLException {
        Connection real = mock(Connection.class);
        PreparedStatement statement = mock(PreparedStatement.class);
        when(real.prepareStatement("select 1")).thenReturn(statement);

        Connection guarded = GuardedConnection.wrap(real);

        assertThat(guarded.prepareStatement("select 1")).isSameAs(statement);
    }

    @Test
    void commitIsForbidden() {
        Connection guarded = GuardedConnection.wrap(mock(Connection.class));

        assertThatThrownBy(guarded::commit)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("commit");
    }

    @Test
    void rollbackIsForbidden() {
        Connection guarded = GuardedConnection.wrap(mock(Connection.class));

        assertThatThrownBy(guarded::rollback).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void closeIsForbiddenAndDoesNotReachRealConnection() throws SQLException {
        Connection real = mock(Connection.class);
        Connection guarded = GuardedConnection.wrap(real);

        assertThatThrownBy(guarded::close).isInstanceOf(IllegalStateException.class);
        verify(real, never()).close();
    }

    @Test
    void setAutoCommitIsForbidden() {
        Connection guarded = GuardedConnection.wrap(mock(Connection.class));

        assertThatThrownBy(() -> guarded.setAutoCommit(true)).isInstanceOf(IllegalStateException.class);
    }
}
