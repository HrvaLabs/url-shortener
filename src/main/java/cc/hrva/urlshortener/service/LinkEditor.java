package cc.hrva.urlshortener.service;

import cc.hrva.urlshortener.model.Url;
import cc.hrva.urlshortener.model.User;
import cc.hrva.urlshortener.model.enums.ChangeChannel;
import cc.hrva.urlshortener.security.AdminTwoStep;

public record LinkEditor(User user, ChangeChannel via, boolean admin) {

    public static LinkEditor signedIn(final User user) {
        return new LinkEditor(user, ChangeChannel.WEB, AdminTwoStep.hasAdminPowers(user));
    }

    public static LinkEditor withApiKey(final User owner) {
        return new LinkEditor(owner, ChangeChannel.API_KEY, false);
    }

    public boolean owns(final Url url) {
        return url.getOwner() != null && user.getId() != null && user.getId().equals(url.getOwner().getId());
    }

}
