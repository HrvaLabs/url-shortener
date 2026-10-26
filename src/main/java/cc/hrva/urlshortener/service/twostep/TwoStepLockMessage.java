package cc.hrva.urlshortener.service.twostep;

import cc.hrva.urlshortener.model.User;
import cc.hrva.urlshortener.model.enums.PasskeyKind;
import cc.hrva.urlshortener.repository.PasskeyCredentialRepository;
import cc.hrva.urlshortener.service.LoginAttemptService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class TwoStepLockMessage {

    private final PasskeyCredentialRepository passkeyCredentialRepository;

    public String of(final User user) {
        final var signsInWithAPasskey = passkeyCredentialRepository.findByUserOrderByCreatedAtDesc(user).stream()
                .anyMatch(passkey -> passkey.kind() == PasskeyKind.SYNCED || passkey.kind() == PasskeyKind.DEVICE_BOUND);

        return signsInWithAPasskey ? LoginAttemptService.TWO_FACTOR_BLOCKED_OR_PASSKEY : LoginAttemptService.TWO_FACTOR_BLOCKED;
    }

}
