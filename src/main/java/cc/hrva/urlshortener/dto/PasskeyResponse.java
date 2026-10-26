package cc.hrva.urlshortener.dto;

import cc.hrva.urlshortener.model.PasskeyCredential;
import cc.hrva.urlshortener.model.enums.PasskeyKind;
import java.time.LocalDateTime;
import java.util.List;

public record PasskeyResponse(
        Long id,
        String label,
        PasskeyKind kind,
        boolean backupEligible,
        boolean backedUp,
        Boolean discoverable,
        List<String> transports,
        LocalDateTime createdAt,
        LocalDateTime lastUsedAt) {

    public static PasskeyResponse from(final PasskeyCredential passkey) {
        return new PasskeyResponse(
                passkey.getId(),
                passkey.getLabel(),
                passkey.kind(),
                passkey.isBackupEligible(),
                passkey.isBackupState(),
                passkey.getDiscoverable(),
                passkey.transportList(),
                passkey.getCreatedAt(),
                passkey.getLastUsedAt());
    }

}
