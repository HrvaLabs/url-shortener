package cc.hrva.urlshortener.security;

import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.http.HttpHeaders;

public final class CallbackRedirects {

    public static final String REFERRER_POLICY = "Referrer-Policy";
    public static final String NO_REFERRER = "no-referrer";
    public static final String NO_STORE = "no-store";

    private CallbackRedirects() {}

    public static void send(final HttpServletResponse response, final String location) throws IOException {
        response.setHeader(REFERRER_POLICY, NO_REFERRER);
        response.setHeader(HttpHeaders.CACHE_CONTROL, NO_STORE);
        response.sendRedirect(location);
    }

}
