package cc.hrva.urlshortener.service.moderation;

import cc.hrva.urlshortener.service.ReviewQueueService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RetiredReasonsCleanupTest {

    @Mock
    private ReviewQueueService reviewQueueService;

    @InjectMocks
    private RetiredReasonsCleanup retiredReasonsCleanup;

    @Test
    void runsOnceTheApplicationIsReadyAndLogsTheCount() {
        when(reviewQueueService.resolveOpenWithOnlyRetiredReasons()).thenReturn(3);

        try (final var context = new AnnotationConfigApplicationContext()) {
            context.registerBean(RetiredReasonsCleanup.class, () -> retiredReasonsCleanup);
            context.refresh();
            context.publishEvent(new ApplicationReadyEvent(new SpringApplication(), new String[0], context, Duration.ZERO));
        }

        verify(reviewQueueService).resolveOpenWithOnlyRetiredReasons();
    }

    @Test
    void aFailureIsLoggedAndDoesNotStopStartup() {
        when(reviewQueueService.resolveOpenWithOnlyRetiredReasons()).thenThrow(new IllegalStateException("database down"));

        assertThatCode(retiredReasonsCleanup::closeAtStartup).doesNotThrowAnyException();
    }

}
