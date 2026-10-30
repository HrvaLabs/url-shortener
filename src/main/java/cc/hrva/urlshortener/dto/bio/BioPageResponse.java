package cc.hrva.urlshortener.dto.bio;

import cc.hrva.urlshortener.model.BioPage;
import cc.hrva.urlshortener.model.BioPageButton;
import cc.hrva.urlshortener.model.enums.BioAccent;
import cc.hrva.urlshortener.model.enums.BioAvatarKind;
import cc.hrva.urlshortener.model.enums.BioIcon;
import cc.hrva.urlshortener.model.enums.BioLinkState;
import cc.hrva.urlshortener.model.enums.BioTheme;
import cc.hrva.urlshortener.util.DomainNames;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.LocalDateTime;
import java.util.List;

public record BioPageResponse(Long id, String handle, String displayName, String bio, BioAvatarKind avatarKind, String avatarEmoji,
                              BioAccent accent, BioTheme theme, boolean indexable, boolean hidden, String hiddenReason,
                              String handleRevokedReason, List<Button> buttons, LocalDateTime createdAt, LocalDateTime updatedAt) {

    public static BioPageResponse from(final BioPage page, final List<Button> buttons) {
        return new BioPageResponse(page.getId(), page.getHandle(), page.getDisplayName(), page.getBio(), page.getAvatarKind(),
                page.getAvatarEmoji(), page.getAccent(), page.getTheme(), page.isIndexable(), page.getHiddenAt() != null,
                page.getHiddenReason(), page.getHandleRevokedReason(), buttons, page.getCreatedAt(), page.getUpdatedAt());
    }

    public record Button(Long id, Long urlId, String shortUrl, String destinationHost, String title, BioIcon icon, boolean visible,
                         BioLinkState linkState, boolean shownOnPage, @JsonInclude(JsonInclude.Include.NON_NULL) String domain) {

        public static Button from(final BioPageButton button, final BioLinkState linkState) {
            final var url = button.getUrl();
            return new Button(button.getId(), url.getId(), url.getShortUrl(), DomainNames.hostOf(url.getLongUrl()).orElse(""),
                    button.getTitle(), button.getIcon(), button.isVisible(), linkState,
                    button.isVisible() && linkState == BioLinkState.LIVE, url.getDomain() == null ? null : url.getDomain().getHostname());
        }

    }

}
