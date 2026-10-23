package cc.hrva.urlshortener.security;

import cc.hrva.urlshortener.dto.ClickRequest;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.assertj.core.api.Assertions.assertThat;

class ClickRequestResolverTest {

    private static final String CF_IP_COUNTRY = "CF-IPCountry";

    private final ClickRequestResolver clickRequestResolver = new ClickRequestResolver(new ClientIpResolver());

    @Test
    void clickIsReadFromTheCloudflareAndBrowserHeaders() {
        assertThat(clickRequestResolver.resolve(cloudflareRequest()))
                .isEqualTo(ClickRequest.of("203.0.113.7", "UA", "https://t.co/x", "HR"));
    }

    @Test
    void repeatedCountryHeaderLeavesTheCountryOut() {
        final var request = cloudflareRequest();
        request.addHeader(CF_IP_COUNTRY, "DE");

        assertThat(clickRequestResolver.resolve(request).country()).isNull();
    }

    @Test
    void missingRequestIsAnEmptyClick() {
        assertThat(clickRequestResolver.resolve(null)).isEqualTo(ClickRequest.of("", null, null, null));
    }

    private static MockHttpServletRequest cloudflareRequest() {
        final var request = new MockHttpServletRequest();
        request.setRemoteAddr("10.0.0.1");
        request.addHeader("CF-Connecting-IP", "203.0.113.7");
        request.addHeader("User-Agent", "UA");
        request.addHeader("Referer", "https://t.co/x");
        request.addHeader(CF_IP_COUNTRY, "HR");

        return request;
    }

}
