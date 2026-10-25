package cc.hrva.urlshortener.validator;

import cc.hrva.urlshortener.dto.LinkVerdict;
import cc.hrva.urlshortener.model.Url;

public interface UrlValidator {

    String SHORT_URL_TAKEN = "This short URL is already taken. Try another.";

    void longUrlInUrl(Url url);
    void checkIfUrlSafe(Url url);
    void checkIfLongUrlIsValid(Url url);
    void checkIfLongUrlIsNotAShortener(Url url);
    LinkVerdict checkIfLongUrlIsNotBlocklisted(Url url);
    void verifyUserAdminOrOwner(Url url);
    void verifyUserAdmin();
    void checkIfAnonymousUrlCreationEnabled();
    void checkIfShortUrlIsUnique(String shortUrl);
    void checkIfShortUrlIsFreeToReactivate(String shortUrl);
    void checkIfUrlNotBlocked(Url url);
    void checkIfDestinationNotBlocked(Url url);
    void checkIfShortUrlIsReserved(String shortUrl);
    void checkIfUrlExpirationDateIsInThePast(Url url);

}
