package cc.hrva.urlshortener.service.alert;

import cc.hrva.urlshortener.model.Url;
import cc.hrva.urlshortener.model.User;
import cc.hrva.urlshortener.service.domain.ShortLinks;
import cc.hrva.urlshortener.util.DomainNames;
import java.util.Optional;

public record AlertedLink(long urlId, String shortUrl, String destinationHost, Long ownerId, String domain) {

    public static AlertedLink of(final Url url) {
        return new AlertedLink(
                url.getId(),
                url.getShortUrl(),
                DomainNames.hostOf(url.getLongUrl()).orElse(null),
                Optional.ofNullable(url.getOwner()).map(User::getId).orElse(null),
                ShortLinks.hostnameOf(url));
    }

}
