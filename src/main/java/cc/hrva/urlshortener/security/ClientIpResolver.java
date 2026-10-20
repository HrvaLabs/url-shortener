package cc.hrva.urlshortener.security;

import jakarta.servlet.http.HttpServletRequest;
import java.net.InetAddress;
import java.util.Collections;
import java.util.Optional;
import org.springframework.stereotype.Component;

@Component
public class ClientIpResolver {

    private static final String CF_CONNECTING_IP = "CF-Connecting-IP";

    public String getClientIp(final HttpServletRequest request) {
        if (request == null) {
            return "";
        }

        return cloudflareConnectingIp(request).orElseGet(request::getRemoteAddr);
    }

    private static Optional<String> cloudflareConnectingIp(final HttpServletRequest request) {
        final var headers = request.getHeaders(CF_CONNECTING_IP);
        if (headers == null) {
            return Optional.empty();
        }

        final var values = Collections.list(headers);
        if (values.size() != 1 || values.getFirst() == null || values.getFirst().isBlank()) {
            return Optional.empty();
        }

        try {
            return Optional.of(InetAddress.ofLiteral(values.getFirst().strip()).getHostAddress());
        } catch (final IllegalArgumentException exception) {
            return Optional.empty();
        }
    }

}
