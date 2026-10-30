package cc.hrva.urlshortener.service.domain;

public final class UrlCacheKeys {

    private static final String SEPARATOR = "/";

    private UrlCacheKeys() {}

    public static String of(final Long domainId, final String alias) {
        return domainId == null ? alias : domainId + SEPARATOR + alias;
    }

}
