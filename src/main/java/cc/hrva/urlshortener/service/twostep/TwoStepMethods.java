package cc.hrva.urlshortener.service.twostep;

import cc.hrva.urlshortener.model.User;
import cc.hrva.urlshortener.model.enums.TwoStepMethod;
import cc.hrva.urlshortener.repository.PasskeyCredentialRepository;
import cc.hrva.urlshortener.repository.TwoFactorRecoveryCodeRepository;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class TwoStepMethods {

    private final PasskeyCredentialRepository passkeyCredentialRepository;
    private final TwoFactorRecoveryCodeRepository recoveryCodeRepository;

    public List<TwoStepMethod> of(final User user) {
        final var methods = new ArrayList<TwoStepMethod>();
        if (Boolean.TRUE.equals(user.getTwoFactorEnabled())) {
            methods.add(TwoStepMethod.TOTP);
        }
        if (passkeyCredentialRepository.countByUser(user) > 0) {
            methods.add(TwoStepMethod.WEBAUTHN);
        }
        if (recoveryCodeRepository.countByUserAndUsedFalse(user) > 0) {
            methods.add(TwoStepMethod.RECOVERY_CODE);
        }

        return List.copyOf(methods);
    }

}
