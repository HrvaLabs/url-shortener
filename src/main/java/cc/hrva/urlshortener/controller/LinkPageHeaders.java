package cc.hrva.urlshortener.controller;

import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;

public final class LinkPageHeaders {

    public static final String CACHE_CONTROL = "no-store, no-transform";
    public static final String CONTENT_SECURITY_POLICY =
            "default-src 'none'; img-src 'self'; style-src 'unsafe-inline'; base-uri 'none'; form-action 'none'; frame-ancestors 'none'";
    public static final String ROBOTS = "noindex, nofollow";
    public static final String NO_REFERRER = "no-referrer";

    private LinkPageHeaders() {}

    public static void apply(final HttpServletResponse response) {
        response.setHeader(HttpHeaders.CACHE_CONTROL, CACHE_CONTROL);
        response.setHeader("Content-Security-Policy", CONTENT_SECURITY_POLICY);
        response.setHeader("X-Robots-Tag", ROBOTS);
        response.setHeader("Referrer-Policy", NO_REFERRER);
    }

}
