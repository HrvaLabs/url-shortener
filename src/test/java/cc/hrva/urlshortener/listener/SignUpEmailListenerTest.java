package cc.hrva.urlshortener.listener;

import cc.hrva.urlshortener.model.User;
import cc.hrva.urlshortener.model.VerificationToken;
import cc.hrva.urlshortener.service.SendingEmailService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

@ExtendWith(MockitoExtension.class)
class SignUpEmailListenerTest {

    private static final User NEW_USER = User.builder().id(31L).email("ana@example.com").apiKeySlots(4L).build();

    @Mock
    private SendingEmailService sendingEmailService;

    @Test
    void aSignUpWithAPasswordSendsTheVerificationEmailAndTellsTheAdmin() {
        final var token = VerificationToken.builder().token("verify-me").build();

        new SignUpEmailListener(sendingEmailService).onUserRegistered(new UserRegisteredEvent(NEW_USER, token));

        verify(sendingEmailService).sendVerificationEmail(NEW_USER, token);
        verify(sendingEmailService).sendNewUserNotificationToAdmin(NEW_USER);
        verifyNoMoreInteractions(sendingEmailService);
    }

    @Test
    void aSignUpWithGoogleOrGitHubOnlyTellsTheAdmin() {
        new SignUpEmailListener(sendingEmailService).onUserRegistered(new UserRegisteredEvent(NEW_USER, null));

        verify(sendingEmailService).sendNewUserNotificationToAdmin(NEW_USER);
        verify(sendingEmailService, never()).sendVerificationEmail(any(), any());
        verifyNoMoreInteractions(sendingEmailService);
    }

}
