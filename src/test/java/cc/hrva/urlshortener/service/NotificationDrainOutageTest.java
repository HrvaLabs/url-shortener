package cc.hrva.urlshortener.service;

import cc.hrva.urlshortener.service.impl.DefaultNotificationDeliveryService;
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.sql.SQLTransientConnectionException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.transaction.CannotCreateTransactionException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;

class NotificationDrainOutageTest {

    private final DefaultNotificationDeliveryService service = mock(DefaultNotificationDeliveryService.class, CALLS_REAL_METHODS);
    private final Logger logger = (Logger) LoggerFactory.getLogger(DefaultNotificationDeliveryService.class);
    private final ListAppender<ILoggingEvent> events = new ListAppender<>();

    @BeforeEach
    void listen() {
        events.start();
        logger.addAppender(events);
    }

    @AfterEach
    void stopListening() {
        logger.detachAppender(events);
    }

    @Test
    void aDatabaseOutageDuringTheBackgroundDrainIsOneWarnLineWithoutAStack() {
        doThrow(new CannotCreateTransactionException("Could not open JPA EntityManager", new SQLTransientConnectionException("HikariPool-1")))
                .when(service).drain();

        service.drainInBackground();

        assertThat(events.list).singleElement().satisfies(event -> {
            assertThat(event.getLevel()).isEqualTo(Level.WARN);
            assertThat(event.getFormattedMessage())
                    .isEqualTo("Notification delivery drain skipped, the database isn't answering exception=org.springframework.transaction.CannotCreateTransactionException");
            assertThat(event.getThrowableProxy()).isNull();
        });
    }

    @Test
    void aRedisOutageDuringTheBackgroundDrainIsOneWarnLineToo() {
        doThrow(new RedisConnectionFailureException("Unable to connect")).when(service).drain();

        service.drainInBackground();

        assertThat(events.list).singleElement().extracting(ILoggingEvent::getFormattedMessage).asString()
                .contains("the cache isn't answering");
    }

    @Test
    void anyOtherFailureStillReachesTheAsyncHandler() {
        doThrow(new IllegalStateException("bug")).when(service).drain();

        assertThatThrownBy(service::drainInBackground).isInstanceOf(IllegalStateException.class);
    }

}
