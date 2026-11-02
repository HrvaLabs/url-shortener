package cc.hrva.urlshortener.listener;

import cc.hrva.urlshortener.mcp.bridge.AppConnectedEvent;
import cc.hrva.urlshortener.service.SendingEmailService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Slf4j
@Component
@RequiredArgsConstructor
public class AppConnectedEmailListener {

    private final SendingEmailService sendingEmailService;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onAppConnected(final AppConnectedEvent event) {
        try {
            sendingEmailService.sendAppConnectedEmail(event.user(), event.destination(), event.connectedAt());
        } catch (final RuntimeException exception) {
            log.warn("App connected email could not be sent user id={}", event.user().getId(), exception);
        }
    }

}
