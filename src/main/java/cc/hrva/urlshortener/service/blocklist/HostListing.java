package cc.hrva.urlshortener.service.blocklist;

public record HostListing(boolean shortener, boolean riskyEnding, BrandSite brandSite) {

    public enum BrandSite { LISTED, NOT_LISTED, UNKNOWN }

    public static HostListing unknown() {
        return new HostListing(false, false, BrandSite.UNKNOWN);
    }

}
