package cc.hrva.urlshortener.dto;

public record ClickRequest(String clientIp, String userAgent, String referer, String country, boolean referrerKnown, boolean qrScan, boolean head) {

    public static ClickRequest of(final String clientIp, final String userAgent, final String referer, final String country) {
        return new ClickRequest(clientIp, userAgent, referer, country, true, false, false);
    }

    public ClickRequest withQrScan() {
        return new ClickRequest(clientIp, userAgent, referer, country, referrerKnown, true, head);
    }

    public ClickRequest withUnknownReferrer() {
        return new ClickRequest(clientIp, userAgent, null, country, false, qrScan, head);
    }

    public ClickRequest asHead() {
        return new ClickRequest(clientIp, userAgent, referer, country, referrerKnown, qrScan, true);
    }

    @Override
    public String toString() {
        return "ClickRequest[redacted]";
    }

}
