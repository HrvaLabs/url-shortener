package cc.hrva.urlshortener.validator;

import cc.hrva.urlshortener.model.Url;

public interface UrlValidator {

    void longUrlInUrl(Url url);
    void checkIfUrlSafe(Url url);
    void checkIfLongUrlIsValid(Url url);
    void checkIfLongUrlIsNotAShortener(Url url);
    void verifyUserAdminOrOwner(Url url);
    void verifyUserAdmin();
    void checkIfAnonymousUrlCreationEnabled();
    void checkIfShortUrlIsUnique(String shortUrl);
    void checkIfShortUrlIsReserved(String shortUrl);
    void checkIfUrlExpirationDateIsInThePast(Url url);

}
