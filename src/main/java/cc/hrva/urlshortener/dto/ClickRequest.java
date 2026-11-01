package cc.hrva.urlshortener.dto;

public record ClickRequest(String clientIp, String userAgent, String referer, String country, boolean referrerKnown, boolean qrScan, boolean head,
                           String query) {

    public static ClickRequest of(final String clientIp, final String userAgent, final String referer, final String country) {
        return new ClickRequest(clientIp, userAgent, referer, country, true, false, false, null);
    }

    public ClickRequest withQrScan() {
        return new ClickRequest(clientIp, userAgent, referer, country, referrerKnown, true, head, query);
    }

    public ClickRequest withUnknownReferrer() {
        return new ClickRequest(clientIp, userAgent, null, country, false, qrScan, head, query);
    }

    public ClickRequest asHead() {
        return new ClickRequest(clientIp, userAgent, referer, country, referrerKnown, qrScan, true, query);
    }

    public ClickRequest withQuery(final String forwardedQuery) {
        return new ClickRequest(clientIp, userAgent, referer, country, referrerKnown, qrScan, head, forwardedQuery);
    }

    @Override
    public String toString() {
        return "ClickRequest[redacted]";
    }

}
