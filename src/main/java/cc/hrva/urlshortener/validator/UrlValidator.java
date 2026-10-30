package cc.hrva.urlshortener.validator;

import cc.hrva.urlshortener.dto.LinkVerdict;
import cc.hrva.urlshortener.model.Url;

public interface UrlValidator {

    String SHORT_URL_TAKEN = "This short URL is already taken. Try another.";
    int MAX_LONG_URL_LENGTH = 2048;
    String LONG_URL_TOO_LONG = "Destination URL must be 2048 characters or fewer";
    String DOMAIN_NOT_USABLE = "Choose one of your active domains, or leave it empty for hrva.cc.";

    void longUrlInUrl(Url url);
    void checkIfUrlSafe(Url url);
    void checkIfLongUrlIsValid(Url url);
    void checkIfLongUrlIsNotAShortener(Url url);
    LinkVerdict checkIfLongUrlIsNotBlocklisted(Url url);
    void verifyUserAdminOrOwner(Url url);
    void verifyUserAdmin();
    void checkIfAnonymousUrlCreationEnabled();
    void checkIfShortUrlIsUnique(Long domainId, String shortUrl);
    void checkIfShortUrlIsFreeToReactivate(Long domainId, String shortUrl);
    void checkIfUrlNotBlocked(Url url);
    void checkIfDestinationNotBlocked(Url url);
    void checkIfShortUrlIsReserved(String shortUrl);
    void checkIfUrlExpirationDateIsInThePast(Url url);

}
