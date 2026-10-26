package cc.hrva.urlshortener.listener;

import cc.hrva.urlshortener.service.SendingEmailService;
import cc.hrva.urlshortener.service.passkey.PasskeyAddedEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Slf4j
@Component
@RequiredArgsConstructor
public class PasskeyAddedEmailListener {

    private final SendingEmailService sendingEmailService;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onPasskeyAdded(final PasskeyAddedEvent event) {
        try {
            sendingEmailService.sendPasskeyAddedEmail(event.user(), event.label(), event.addedAt());
        } catch (final RuntimeException exception) {
            log.warn("Passkey added email could not be sent user id={}", event.user().getId(), exception);
        }
    }

}
