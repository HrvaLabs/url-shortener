package cc.hrva.urlshortener.configuration;

import com.zaxxer.hikari.HikariDataSource;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class DatabaseTimeoutConfiguration {

    @Bean
    static PostgresSocketTimeout postgresSocketTimeout() {
        return new PostgresSocketTimeout();
    }

    static final class PostgresSocketTimeout implements BeanPostProcessor {

        private static final String POSTGRES_URL = "jdbc:postgresql:";
        private static final String SOCKET_TIMEOUT = "socketTimeout";
        private static final String SOCKET_TIMEOUT_SECONDS = "30";

        @Override
        public Object postProcessAfterInitialization(final Object bean, final String beanName) {
            if (bean instanceof HikariDataSource dataSource && StringUtils.startsWith(dataSource.getJdbcUrl(), POSTGRES_URL)
                    && !StringUtils.containsIgnoreCase(dataSource.getJdbcUrl(), SOCKET_TIMEOUT)) {
                dataSource.addDataSourceProperty(SOCKET_TIMEOUT, SOCKET_TIMEOUT_SECONDS);
            }
            return bean;
        }

    }

}
