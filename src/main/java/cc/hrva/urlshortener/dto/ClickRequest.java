package cc.hrva.urlshortener.dto;

public record ClickRequest(String clientIp, String userAgent, String referer, String country, boolean referrerKnown, boolean qrScan) {

    public static ClickRequest of(final String clientIp, final String userAgent, final String referer, final String country) {
        return new ClickRequest(clientIp, userAgent, referer, country, true, false);
    }

    public ClickRequest withQrScan() {
        return new ClickRequest(clientIp, userAgent, referer, country, referrerKnown, true);
    }

    public ClickRequest withUnknownReferrer() {
        return new ClickRequest(clientIp, userAgent, null, country, false, qrScan);
    }

    @Override
    public String toString() {
        return "ClickRequest[redacted]";
    }

}
