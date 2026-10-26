package cc.hrva.urlshortener.dto.bio;

import cc.hrva.urlshortener.model.BioPage;
import java.time.LocalDateTime;

public record AdminBioPageResponse(
        Long id,
        String handle,
        String displayName,
        String bio,
        Long ownerId,
        String ownerEmail,
        boolean ownerActive,
        boolean hidden,
        String hiddenReason,
        LocalDateTime hiddenAt,
        String hiddenBy,
        String handleRevokedReason,
        long buttons,
        long visibleButtons,
        boolean reviewOpen,
        LocalDateTime createdAt,
        LocalDateTime updatedAt) {

    public static AdminBioPageResponse from(final BioPage page, final long buttons, final long visibleButtons, final boolean reviewOpen) {
        final var owner = page.getOwner();
        return new AdminBioPageResponse(
                page.getId(),
                page.getHandle(),
                page.getDisplayName(),
                page.getBio(),
                owner.getId(),
                owner.getEmail(),
                !Boolean.FALSE.equals(owner.getActive()),
                page.getHiddenAt() != null,
                page.getHiddenReason(),
                page.getHiddenAt(),
                page.getHiddenBy(),
                page.getHandleRevokedReason(),
                buttons,
                visibleButtons,
                reviewOpen,
                page.getCreatedAt(),
                page.getUpdatedAt());
    }

}
