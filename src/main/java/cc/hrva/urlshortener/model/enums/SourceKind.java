package cc.hrva.urlshortener.model.enums;

public enum SourceKind {

    DOMAINS,
    LINKS,
    TOP_SITES,
    SHORTENERS,
    ENDINGS,
    BRAND_SITES;

    public boolean ranked() {
        return this == TOP_SITES || this == BRAND_SITES;
    }

}
