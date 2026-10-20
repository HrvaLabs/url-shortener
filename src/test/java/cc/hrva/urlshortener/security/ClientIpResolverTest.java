package cc.hrva.urlshortener.security;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.assertj.core.api.Assertions.assertThat;

class ClientIpResolverTest {

    private static final String CF_CONNECTING_IP = "CF-Connecting-IP";
    private static final String PROXY_IP = "10.0.0.1";

    private final ClientIpResolver clientIpResolver = new ClientIpResolver();

    @Test
    void cloudflareIpv4AddressIsUsed() {
        assertThat(clientIpResolver.getClientIp(fromProxy("203.0.113.7"))).isEqualTo("203.0.113.7");
        assertThat(clientIpResolver.getClientIp(fromProxy(" 203.0.113.7 "))).isEqualTo("203.0.113.7");
    }

    @Test
    void cloudflareIpv6AddressIsUsed() {
        assertThat(clientIpResolver.getClientIp(fromProxy("2001:db8:1:2::7"))).isEqualTo("2001:db8:1:2:0:0:0:7");
        assertThat(clientIpResolver.getClientIp(fromProxy("[2001:db8:1:2::7]"))).isEqualTo("2001:db8:1:2:0:0:0:7");
    }

    @Test
    void absentHeaderFallsBackToRemoteAddress() {
        final var request = new MockHttpServletRequest();
        request.setRemoteAddr(PROXY_IP);

        assertThat(clientIpResolver.getClientIp(request)).isEqualTo(PROXY_IP);
    }

    @Test
    void blankGarbageOrCommaJoinedHeaderFallsBackToRemoteAddress() {
        for (final var value : new String[]{"", "   ", "not-an-ip", "203.0.113.7, 198.51.100.23", "203.0.113.7,198.51.100.23",
                "203.0.113.7:443", "999.1.1.1", "2001:db8::7::1"}) {
            assertThat(clientIpResolver.getClientIp(fromProxy(value))).as(value).isEqualTo(PROXY_IP);
        }
    }

    @Test
    void repeatedHeaderFallsBackToRemoteAddress() {
        final var request = fromProxy("203.0.113.7");
        request.addHeader(CF_CONNECTING_IP, "198.51.100.23");

        assertThat(clientIpResolver.getClientIp(request)).isEqualTo(PROXY_IP);
    }

    @Test
    void hostnameIsNotResolved() {
        assertThat(clientIpResolver.getClientIp(fromProxy("localhost"))).isEqualTo(PROXY_IP);
        assertThat(clientIpResolver.getClientIp(fromProxy("example.com"))).isEqualTo(PROXY_IP);
    }

    @Test
    void missingRequestYieldsEmptyAddress() {
        assertThat(clientIpResolver.getClientIp(null)).isEmpty();
    }

    private static MockHttpServletRequest fromProxy(final String cloudflareConnectingIp) {
        final var request = new MockHttpServletRequest();
        request.setRemoteAddr(PROXY_IP);
        request.addHeader(CF_CONNECTING_IP, cloudflareConnectingIp);

        return request;
    }

}
