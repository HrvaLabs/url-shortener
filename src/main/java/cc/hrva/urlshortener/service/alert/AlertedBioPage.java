package cc.hrva.urlshortener.service.alert;

import cc.hrva.urlshortener.model.BioPage;
import cc.hrva.urlshortener.model.User;
import java.util.Optional;

public record AlertedBioPage(long pageId, String handle, Long ownerId) {

    public static AlertedBioPage of(final BioPage page) {
        return new AlertedBioPage(page.getId(), page.getHandle(), Optional.ofNullable(page.getOwner()).map(User::getId).orElse(null));
    }

}
