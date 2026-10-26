package cc.hrva.urlshortener.listener;

import cc.hrva.urlshortener.service.SendingEmailService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;

@Component
@RequiredArgsConstructor
public class SignUpEmailListener {

    private final SendingEmailService sendingEmailService;

    @TransactionalEventListener(fallbackExecution = true)
    public void onUserRegistered(final UserRegisteredEvent event) {
        if (event.verificationToken() != null) {
            sendingEmailService.sendVerificationEmail(event.user(), event.verificationToken());
        }
        sendingEmailService.sendNewUserNotificationToAdmin(event.user());
    }

}
