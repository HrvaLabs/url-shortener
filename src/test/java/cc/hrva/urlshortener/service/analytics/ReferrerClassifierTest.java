package cc.hrva.urlshortener.service.analytics;

import cc.hrva.urlshortener.configuration.properties.AppProperties;
import cc.hrva.urlshortener.dto.ClickRequest;
import java.util.Collections;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import static org.assertj.core.api.Assertions.assertThat;

class ReferrerClassifierTest {

    private ReferrerClassifier referrerClassifier;

    @BeforeEach
    void setUp() {
        final var appProperties = new AppProperties();
        appProperties.setServerUrl("https://hrva.cc");
        appProperties.setFrontendUrl("https://app.hrva.cc");
        referrerClassifier = new ReferrerClassifier(appProperties);
        referrerClassifier.init();
    }

    @ParameterizedTest
    @MethodSource("referers")
    void refererIsReducedToItsHost(final String referer, final String referrer) {
        assertThat(referrerClassifier.classify(click(referer))).isEqualTo(referrer);
    }

    @Test
    void unknownReferrerStaysUnknown() {
        assertThat(referrerClassifier.classify(click("https://t.co/x").withUnknownReferrer())).isEqualTo("unknown");
    }

    private static Stream<Arguments> referers() {
        return Stream.of(
                Arguments.of(null, "direct"),
                Arguments.of("  ", "direct"),
                Arguments.of("https://www.Google.com/search?q=x", "google.com"),
                Arguments.of("https://user:pw@Example.COM:8443/a?q=1", "example.com"),
                Arguments.of("https://t.co/abc", "t.co"),
                Arguments.of("https://bücher.de/", "xn--bcher-kva.de"),
                Arguments.of("https://app.hrva.cc/dashboard", "hrva.cc"),
                Arguments.of("https://hrva.cc/x", "hrva.cc"),
                Arguments.of("https://www.hrva.cc/", "hrva.cc"),
                Arguments.of("android-app://com.google.android.gm/", "app:com.google.android.gm"),
                Arguments.of("android-app://", "other"),
                Arguments.of("http://192.168.1.10/", "other"),
                Arguments.of("http://[2001:db8::1]/", "other"),
                Arguments.of("not a url", "other"),
                Arguments.of("javascript:alert(1)", "other"),
                Arguments.of("ftp://example.com/", "other"),
                Arguments.of("https://exa mple.com/", "other"),
                Arguments.of("https://" + "a".repeat(300) + ".com/", "other"),
                Arguments.of("https://" + hostOfLength(255) + "/", hostOfLength(255)),
                Arguments.of("https://" + hostOfLength(256) + "/", "other"));
    }

    private static String hostOfLength(final int length) {
        final var labels = String.join(".", Collections.nCopies(4, "a".repeat(60)));
        return labels + "." + "b".repeat(length - labels.length() - 5) + ".com";
    }

    private static ClickRequest click(final String referer) {
        return ClickRequest.of("203.0.113.7", "ua", referer, "HR");
    }

}
