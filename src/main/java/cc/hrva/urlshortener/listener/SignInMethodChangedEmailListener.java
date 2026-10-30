package cc.hrva.urlshortener.listener;

import cc.hrva.urlshortener.service.SendingEmailService;
import cc.hrva.urlshortener.service.signin.SignInMethodChangedEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Slf4j
@Component
@RequiredArgsConstructor
public class SignInMethodChangedEmailListener {

    private final SendingEmailService sendingEmailService;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onSignInMethodChanged(final SignInMethodChangedEvent event) {
        try {
            sendingEmailService.sendSignInMethodChangedEmail(event.user(), event.change(), event.changedAt());
        } catch (final RuntimeException exception) {
            log.warn("Sign-in method changed email could not be sent user id={}", event.user().getId(), exception);
        }
    }

}
