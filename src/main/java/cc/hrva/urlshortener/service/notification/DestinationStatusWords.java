package cc.hrva.urlshortener.service.notification;

public final class DestinationStatusWords {

    private DestinationStatusWords() {
    }

    public static String of(final String code) {
        return switch (code == null ? "" : code) {
            case "404" -> "the page wasn't found (404)";
            case "410" -> "the page was removed (410)";
            case "nxdomain" -> "the website's domain doesn't exist";
            case "refused" -> "the website refused the connection";
            case "tls" -> "the website's secure connection failed";
            default -> "the destination stopped working";
        };
    }

}
