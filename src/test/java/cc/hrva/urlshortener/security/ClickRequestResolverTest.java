package cc.hrva.urlshortener.security;

import cc.hrva.urlshortener.dto.ClickRequest;
import org.apache.tomcat.util.http.InvalidParameterException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.spy;

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

    @ParameterizedTest
    @ValueSource(strings = {"qr", "qr=", "qr=1", "fbclid=abc&qr"})
    void aQrMarkerMakesTheClickAQrScan(final String query) {
        assertThat(clickRequestResolver.resolve(withQuery(query)))
                .isEqualTo(ClickRequest.of("203.0.113.7", "UA", "https://t.co/x", "HR").withQrScan());
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "QR", "qrcode=1", "utm_source=qr"})
    void otherQueriesAreDirect(final String query) {
        assertThat(clickRequestResolver.resolve(withQuery(query)))
                .isEqualTo(ClickRequest.of("203.0.113.7", "UA", "https://t.co/x", "HR"));
    }

    @Test
    void aQueryTheServerCannotParseIsADirectClick() {
        final var request = spy(withQuery("utm_campaign=50%off&qr"));
        doThrow(new InvalidParameterException("Character decoding failed", 400)).when(request).getParameter("qr");

        assertThat(clickRequestResolver.resolve(request))
                .isEqualTo(ClickRequest.of("203.0.113.7", "UA", "https://t.co/x", "HR"));
    }

    private static MockHttpServletRequest withQuery(final String query) {
        final var request = cloudflareRequest();
        request.setQueryString(query);
        for (final var pair : query.split("&")) {
            if (!pair.isEmpty()) {
                final var separator = pair.indexOf('=');
                request.addParameter(separator < 0 ? pair : pair.substring(0, separator), separator < 0 ? "" : pair.substring(separator + 1));
            }
        }

        return request;
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
