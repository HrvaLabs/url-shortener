package cc.hrva.urlshortener.configuration;

import com.zaxxer.hikari.HikariDataSource;
import java.sql.SQLException;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.transaction.autoconfigure.TransactionManagerCustomizers;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.datasource.ConnectionHolder;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Configuration(proxyBeanMethods = false)
public class DatabaseTimeoutConfiguration {

    @Bean
    static PostgresSocketTimeout postgresSocketTimeout() {
        return new PostgresSocketTimeout();
    }

    @Bean
    PlatformTransactionManager transactionManager(final ObjectProvider<TransactionManagerCustomizers> customizers) {
        final var transactionManager = new SocketBoundTransactionManager();
        customizers.ifAvailable(each -> each.customize(transactionManager));
        return transactionManager;
    }

    static final class PostgresSocketTimeout implements BeanPostProcessor {

        private static final String POSTGRES_URL = "jdbc:postgresql:";
        private static final Map<String, String> TIMEOUT_SECONDS = Map.of("socketTimeout", "120", "cancelSignalTimeout", "2");

        @Override
        public Object postProcessAfterInitialization(final Object bean, final String beanName) {
            if (bean instanceof HikariDataSource dataSource && StringUtils.startsWith(dataSource.getJdbcUrl(), POSTGRES_URL)) {
                TIMEOUT_SECONDS.forEach((name, seconds) -> {
                    if (!StringUtils.containsIgnoreCase(dataSource.getJdbcUrl(), name)) {
                        dataSource.addDataSourceProperty(name, seconds);
                    }
                });
            }
            return bean;
        }

    }

    @Slf4j
    static final class SocketBoundTransactionManager extends JpaTransactionManager {

        @Override
        protected void doBegin(final Object transaction, final TransactionDefinition definition) {
            super.doBegin(transaction, definition);
            if (definition.getTimeout() > 0 && getDataSource() != null) {
                limitSocketReads(TransactionSynchronizationManager.getResource(getDataSource()), definition.getTimeout());
            }
        }

        static void limitSocketReads(final Object resource, final int seconds) {
            if (!(resource instanceof ConnectionHolder holder)) {
                return;
            }
            try {
                holder.getConnection().setNetworkTimeout(Runnable::run, Math.toIntExact(TimeUnit.SECONDS.toMillis(seconds)));
            } catch (final SQLException | UnsupportedOperationException exception) {
                log.warn("Could not limit the transaction's socket reads exception={}", exception.getClass().getName());
            }
        }

    }

}
