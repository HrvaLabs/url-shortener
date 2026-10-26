package cc.hrva.urlshortener.service.passkey;

import cc.hrva.urlshortener.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.web.webauthn.api.Bytes;
import org.springframework.security.web.webauthn.api.PublicKeyCredentialUserEntity;
import org.springframework.security.web.webauthn.management.PublicKeyCredentialUserEntityRepository;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class JpaPublicKeyCredentialUserEntityRepository implements PublicKeyCredentialUserEntityRepository {

    private final UserRepository userRepository;

    @Override
    public PublicKeyCredentialUserEntity findById(final Bytes id) {
        return userRepository.findByWebauthnUserHandle(id.toBase64UrlString())
                .map(PasskeyOptionsFactory::userEntityOf)
                .orElse(null);
    }

    @Override
    public PublicKeyCredentialUserEntity findByUsername(final String username) {
        return userRepository.findByEmail(username)
                .filter(user -> user.getWebauthnUserHandle() != null)
                .map(PasskeyOptionsFactory::userEntityOf)
                .orElse(null);
    }

    @Override
    public void save(final PublicKeyCredentialUserEntity userEntity) {
        throw new UnsupportedOperationException("Passkey user handles are created by the passkey service");
    }

    @Override
    public void delete(final Bytes id) {
        throw new UnsupportedOperationException("Passkey user handles are removed with the account");
    }

}
