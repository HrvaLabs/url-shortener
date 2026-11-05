package cc.hrva.urlshortener.service.moderation;

import cc.hrva.urlshortener.service.ReviewQueueService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class RetiredReasonsCleanup {

    private final ReviewQueueService reviewQueueService;

    @EventListener(ApplicationReadyEvent.class)
    public void closeAtStartup() {
        try {
            final var closed = reviewQueueService.resolveOpenWithOnlyRetiredReasons();
            log.info("Closed {} reviews whose reasons no longer decide anything", closed);
        } catch (final RuntimeException exception) {
            log.warn("Closing reviews with retired reasons failed exception={}", exception.getClass().getName());
        }
    }

}
