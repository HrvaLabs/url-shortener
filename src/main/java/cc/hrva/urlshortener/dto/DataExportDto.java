package cc.hrva.urlshortener.dto;

import cc.hrva.urlshortener.model.PasskeyCredential;
import cc.hrva.urlshortener.model.enums.PasskeyKind;
import java.time.LocalDateTime;
import java.util.List;

public record DataExportDto(
        LocalDateTime exportedAt,
        UserDto profile,
        List<UrlResponse> urls,
        List<ApiKeyResponse> apiKeys,
        List<EmailExport> emails,
        List<PasskeyExport> passkeys,
        long recoveryCodesRemaining) {

    public record EmailExport(String subject, String status, LocalDateTime sentAt, LocalDateTime createdAt) {}

    public record PasskeyExport(String label, PasskeyKind kind, boolean backedUp, LocalDateTime createdAt, LocalDateTime lastUsedAt) {

        public static PasskeyExport from(final PasskeyCredential passkey) {
            return new PasskeyExport(
                    passkey.getLabel(),
                    passkey.kind(),
                    passkey.isBackupState(),
                    passkey.getCreatedAt(),
                    passkey.getLastUsedAt());
        }

    }

}
