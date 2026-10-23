package cc.hrva.urlshortener.security;

import cc.hrva.urlshortener.dto.ClickRequest;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Collections;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class ClickRequestResolver {

    private static final String CF_IP_COUNTRY = "CF-IPCountry";

    private final ClientIpResolver clientIpResolver;

    public ClickRequest resolve(final HttpServletRequest request) {
        if (request == null) {
            return ClickRequest.of("", null, null, null);
        }

        return ClickRequest.of(clientIpResolver.getClientIp(request), request.getHeader(HttpHeaders.USER_AGENT),
                request.getHeader(HttpHeaders.REFERER), cloudflareCountry(request));
    }

    private static String cloudflareCountry(final HttpServletRequest request) {
        final var headers = request.getHeaders(CF_IP_COUNTRY);
        if (headers == null) {
            return null;
        }

        final var values = Collections.list(headers);
        return values.size() == 1 ? values.getFirst() : null;
    }

}
