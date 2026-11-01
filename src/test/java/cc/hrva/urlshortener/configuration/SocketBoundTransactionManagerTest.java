package cc.hrva.urlshortener.configuration;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.concurrent.Executor;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.ConnectionHolder;
import org.springframework.orm.jpa.JpaTransactionManager;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class SocketBoundTransactionManagerTest {

    @Test
    void aTransactionWithATimeoutWaitsForTheDatabaseNoLongerThanItsTimeout() throws SQLException {
        final var connection = mock(Connection.class);

        DatabaseTimeoutConfiguration.SocketBoundTransactionManager.limitSocketReads(new ConnectionHolder(connection), 3);

        verify(connection).setNetworkTimeout(any(Executor.class), org.mockito.ArgumentMatchers.eq(3000));
    }

    @Test
    void aConnectionThatCannotTakeATimeoutStillRunsItsTransaction() throws SQLException {
        final var connection = mock(Connection.class);
        doThrow(new SQLException("not supported")).when(connection).setNetworkTimeout(any(), anyInt());

        assertThatCode(() -> DatabaseTimeoutConfiguration.SocketBoundTransactionManager.limitSocketReads(new ConnectionHolder(connection), 3))
                .doesNotThrowAnyException();
        assertThatCode(() -> DatabaseTimeoutConfiguration.SocketBoundTransactionManager.limitSocketReads(null, 3)).doesNotThrowAnyException();
    }

    @Test
    void theApplicationsTransactionManagerIsTheOneThatLimitsSocketReads() {
        final var manager = new DatabaseTimeoutConfiguration().transactionManager(new org.springframework.beans.factory.support.StaticListableBeanFactory()
                .getBeanProvider(org.springframework.boot.transaction.autoconfigure.TransactionManagerCustomizers.class));

        assertThat(manager).isInstanceOf(JpaTransactionManager.class).isInstanceOf(DatabaseTimeoutConfiguration.SocketBoundTransactionManager.class);
    }

}
