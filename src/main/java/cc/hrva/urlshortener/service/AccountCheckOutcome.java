package cc.hrva.urlshortener.service;

public enum AccountCheckOutcome {

    SKIPPED,
    FETCH_FAILED,
    NO_FORM,
    REAL_SIGN_IN_PAGE,
    HELD,
    NOTICED

}
