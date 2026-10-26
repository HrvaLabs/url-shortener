package cc.hrva.urlshortener.service.passkey;

import cc.hrva.urlshortener.model.PasskeyCredential;
import cc.hrva.urlshortener.model.User;
import cc.hrva.urlshortener.repository.PasskeyCredentialRepository;
import cc.hrva.urlshortener.repository.UserRepository;
import com.webauthn4j.converter.util.CborConverter;
import com.webauthn4j.converter.util.ObjectConverter;
import com.webauthn4j.data.attestation.AttestationObject;
import com.webauthn4j.data.attestation.authenticator.AAGUID;
import com.webauthn4j.data.attestation.authenticator.AttestedCredentialData;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.security.web.webauthn.api.AuthenticatorTransport;
import org.springframework.security.web.webauthn.api.Bytes;
import org.springframework.security.web.webauthn.api.CredentialRecord;
import org.springframework.security.web.webauthn.api.ImmutableCredentialRecord;
import org.springframework.security.web.webauthn.api.ImmutablePublicKeyCose;
import org.springframework.security.web.webauthn.api.PublicKeyCredentialType;
import org.springframework.security.web.webauthn.management.UserCredentialRepository;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class JpaUserCredentialRepository implements UserCredentialRepository {

    private static final List<AuthenticatorTransport> TRANSPORT_ORDER = Arrays.asList(AuthenticatorTransport.values());
    private static final Comparator<AuthenticatorTransport> IN_TRANSPORT_ORDER = Comparator
            .comparingInt(JpaUserCredentialRepository::orderOf)
            .thenComparing(AuthenticatorTransport::getValue);

    private final PasskeyCredentialRepository passkeyCredentialRepository;
    private final UserRepository userRepository;
    private final Clock clock;
    private final CborConverter cborConverter = new ObjectConverter().getCborConverter();

    @Override
    public void save(final CredentialRecord credentialRecord) {
        passkeyCredentialRepository.findByCredentialId(credentialRecord.getCredentialId().toBase64UrlString())
                .ifPresentOrElse(
                        passkey -> recordUse(passkey, credentialRecord),
                        () -> add(credentialRecord));
    }

    @Override
    public CredentialRecord findByCredentialId(final Bytes credentialId) {
        return passkeyCredentialRepository.findByCredentialId(credentialId.toBase64UrlString())
                .map(this::credentialRecordOf)
                .orElse(null);
    }

    @Override
    public List<CredentialRecord> findByUserId(final Bytes userHandle) {
        return userRepository.findByWebauthnUserHandle(userHandle.toBase64UrlString())
                .map(passkeyCredentialRepository::findByUserOrderByCreatedAtDesc)
                .orElseGet(List::of)
                .stream()
                .map(this::credentialRecordOf)
                .toList();
    }

    @Override
    public void delete(final Bytes credentialId) {
        passkeyCredentialRepository.findByCredentialId(credentialId.toBase64UrlString())
                .ifPresent(passkeyCredentialRepository::delete);
    }

    private void recordUse(final PasskeyCredential passkey, final CredentialRecord credentialRecord) {
        passkey.setSignCount(Math.max(passkey.getSignCount(), credentialRecord.getSignatureCount()));
        passkey.setLastUsedAt(LocalDateTime.now(clock));
        passkeyCredentialRepository.save(passkey);
    }

    private void add(final CredentialRecord credentialRecord) {
        passkeyCredentialRepository.save(PasskeyCredential.builder()
                .user(ownerOf(credentialRecord))
                .credentialId(credentialRecord.getCredentialId().toBase64UrlString())
                .publicKey(new Bytes(credentialRecord.getPublicKey().getBytes()).toBase64UrlString())
                .attestationObject(credentialRecord.getAttestationObject().toBase64UrlString())
                .signCount(credentialRecord.getSignatureCount())
                .uvInitialized(credentialRecord.isUvInitialized())
                .backupEligible(credentialRecord.isBackupEligible())
                .backupState(credentialRecord.isBackupState())
                .transports(storedTransports(credentialRecord.getTransports()))
                .aaguid(aaguidOf(credentialRecord.getAttestationObject()))
                .label(credentialRecord.getLabel())
                .createdAt(LocalDateTime.now(clock))
                .build());
    }

    private User ownerOf(final CredentialRecord credentialRecord) {
        return userRepository.findByWebauthnUserHandle(credentialRecord.getUserEntityUserId().toBase64UrlString())
                .orElseThrow(() -> new IllegalStateException("No account has this passkey user handle"));
    }

    private String aaguidOf(final Bytes attestationObject) {
        return Optional.ofNullable(cborConverter.readValue(attestationObject.getBytes(), AttestationObject.class))
                .map(attestation -> attestation.getAuthenticatorData().getAttestedCredentialData())
                .map(AttestedCredentialData::getAaguid)
                .filter(aaguid -> !AAGUID.ZERO.equals(aaguid) && aaguid.getValue() != null)
                .map(aaguid -> aaguid.getValue().toString())
                .orElse(null);
    }

    private CredentialRecord credentialRecordOf(final PasskeyCredential passkey) {
        final var created = instantOf(passkey.getCreatedAt());

        return ImmutableCredentialRecord.builder()
                .credentialType(PublicKeyCredentialType.PUBLIC_KEY)
                .credentialId(Bytes.fromBase64(passkey.getCredentialId()))
                .userEntityUserId(Bytes.fromBase64(passkey.getUser().getWebauthnUserHandle()))
                .publicKey(ImmutablePublicKeyCose.fromBase64(passkey.getPublicKey()))
                .signatureCount(passkey.getSignCount())
                .uvInitialized(passkey.isUvInitialized())
                .transports(PasskeyOptionsFactory.transportsOf(passkey))
                .backupEligible(passkey.isBackupEligible())
                .backupState(passkey.isBackupState())
                .attestationObject(Bytes.fromBase64(passkey.getAttestationObject()))
                .label(passkey.getLabel())
                .created(created)
                .lastUsed(passkey.getLastUsedAt() == null ? created : instantOf(passkey.getLastUsedAt()))
                .build();
    }

    private Instant instantOf(final LocalDateTime dateTime) {
        return dateTime.atZone(clock.getZone()).toInstant();
    }

    private static int orderOf(final AuthenticatorTransport transport) {
        final var index = TRANSPORT_ORDER.indexOf(transport);

        return index < 0 ? TRANSPORT_ORDER.size() : index;
    }

    private static String storedTransports(final Set<AuthenticatorTransport> transports) {
        if (transports == null || transports.isEmpty()) {
            return null;
        }

        return transports.stream()
                .sorted(IN_TRANSPORT_ORDER)
                .map(AuthenticatorTransport::getValue)
                .collect(Collectors.joining(PasskeyCredential.TRANSPORT_SEPARATOR));
    }

}
