package cc.hrva.urlshortener.listener;

import cc.hrva.urlshortener.model.User;
import cc.hrva.urlshortener.service.SendingEmailService;
import cc.hrva.urlshortener.service.passkey.PasskeyAddedEvent;
import java.time.LocalDateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.core.task.TaskRejectedException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class PasskeyAddedEmailListenerTest {

    private static final User USER = User.builder().id(7L).email("alice@example.com").apiKeySlots(4L).build();
    private static final LocalDateTime ADDED_AT = LocalDateTime.of(2026, 10, 25, 14, 5, 33);

    @Mock
    private SendingEmailService sendingEmailService;

    private PasskeyAddedEmailListener listener;

    @BeforeEach
    void setUp() {
        listener = new PasskeyAddedEmailListener(sendingEmailService);
    }

    @Test
    void sendsTheSecurityNoticeForTheAddedPasskey() {
        listener.onPasskeyAdded(new PasskeyAddedEvent(USER, "MacBook", ADDED_AT));

        verify(sendingEmailService).sendPasskeyAddedEmail(USER, "MacBook", ADDED_AT);
    }

    @Test
    @ExtendWith(OutputCaptureExtension.class)
    void aNoticeThatCantBeQueuedIsLoggedByUserIdAndNeverFailsTheRequest(final CapturedOutput output) {
        doThrow(new TaskRejectedException("Executor did not accept the task")).when(sendingEmailService)
                .sendPasskeyAddedEmail(any(), any(), any());

        assertThatCode(() -> listener.onPasskeyAdded(new PasskeyAddedEvent(USER, "MacBook", ADDED_AT))).doesNotThrowAnyException();

        assertThat(output)
                .contains("Passkey added email could not be sent user id=7")
                .doesNotContain("alice@example.com")
                .doesNotContain("MacBook");
    }

}
