package cc.hrva.urlshortener.dto.bio;

import cc.hrva.urlshortener.model.BioPage;
import cc.hrva.urlshortener.model.BioPageButton;
import cc.hrva.urlshortener.model.enums.BioAccent;
import cc.hrva.urlshortener.model.enums.BioAvatarKind;
import cc.hrva.urlshortener.model.enums.BioIcon;
import cc.hrva.urlshortener.model.enums.BioTheme;
import java.util.List;

public record PublicBioPageResponse(String handle, String displayName, String bio, BioAvatarKind avatarKind, String avatarEmoji,
                                    BioAccent accent, BioTheme theme, boolean indexable, List<Button> buttons) {

    public static PublicBioPageResponse from(final BioPage page, final List<Button> buttons) {
        return new PublicBioPageResponse(page.getHandle(), page.getDisplayName(), page.getBio(), page.getAvatarKind(),
                page.getAvatarEmoji(), page.getAccent(), page.getTheme(), page.isIndexable(), buttons);
    }

    public record Button(String title, BioIcon icon, String shortUrl) {

        public static Button from(final BioPageButton button) {
            return new Button(button.getTitle(), button.getIcon(), button.getUrl().getShortUrl());
        }

    }

}
