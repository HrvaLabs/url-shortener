package cc.hrva.urlshortener.dto;

import cc.hrva.urlshortener.model.BioPage;
import cc.hrva.urlshortener.model.BioPageButton;
import cc.hrva.urlshortener.model.PasskeyCredential;
import cc.hrva.urlshortener.model.enums.BioAccent;
import cc.hrva.urlshortener.model.enums.BioAvatarKind;
import cc.hrva.urlshortener.model.enums.BioIcon;
import cc.hrva.urlshortener.model.enums.BioTheme;
import cc.hrva.urlshortener.model.enums.ChannelType;
import cc.hrva.urlshortener.model.enums.ConsentSource;
import cc.hrva.urlshortener.model.enums.NotificationType;
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
        long recoveryCodesRemaining,
        BioPageExport bioPage,
        NotificationExport notifications) {

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

    public record BioPageExport(
            String handle,
            String displayName,
            String bio,
            BioAvatarKind avatarKind,
            String avatarEmoji,
            BioAccent accent,
            BioTheme theme,
            boolean indexable,
            boolean hidden,
            String hiddenReason,
            String handleRevokedReason,
            List<BioButtonExport> buttons,
            LocalDateTime createdAt,
            LocalDateTime updatedAt) {

        public static BioPageExport from(final BioPage page, final List<BioPageButton> buttons) {
            return new BioPageExport(
                    page.getHandle(),
                    page.getDisplayName(),
                    page.getBio(),
                    page.getAvatarKind(),
                    page.getAvatarEmoji(),
                    page.getAccent(),
                    page.getTheme(),
                    page.isIndexable(),
                    page.getHiddenAt() != null,
                    page.getHiddenReason(),
                    page.getHandleRevokedReason(),
                    buttons.stream().map(BioButtonExport::from).toList(),
                    page.getCreatedAt(),
                    page.getUpdatedAt());
        }

    }

    public record BioButtonExport(String title, BioIcon icon, String shortUrl, boolean visible) {

        public static BioButtonExport from(final BioPageButton button) {
            return new BioButtonExport(button.getTitle(), button.getIcon(), button.getUrl().getShortUrl(), button.isVisible());
        }

    }

    public record NotificationExport(List<ConsentExport> consentHistory, List<ChannelExport> channels) {}

    public record ConsentExport(
            NotificationType type,
            String channelLabel,
            LocalDateTime grantedAt,
            ConsentSource source,
            LocalDateTime revokedAt,
            ConsentSource revokedSource) {}

    public record ChannelExport(
            String name,
            ChannelType type,
            boolean enabled,
            List<NotificationType> events,
            String maskedTarget,
            LocalDateTime createdAt,
            LocalDateTime lastDeliveryAt,
            String lastDeliveryStatus) {}

}
