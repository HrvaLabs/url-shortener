package cc.hrva.urlshortener.validator;

import cc.hrva.urlshortener.configuration.properties.AppProperties;
import cc.hrva.urlshortener.exception.LongUrlNotSpecifiedException;
import cc.hrva.urlshortener.exception.ShortUrlAlreadyExistsException;
import cc.hrva.urlshortener.exception.UrlValidationException;
import cc.hrva.urlshortener.model.Url;
import cc.hrva.urlshortener.repository.UrlRepository;
import cc.hrva.urlshortener.service.SafeBrowsingService;
import cc.hrva.urlshortener.service.UserService;
import cc.hrva.urlshortener.validator.impl.DefaultUrlValidator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.AssertionsForClassTypes.assertThatCode;
import static org.assertj.core.api.AssertionsForClassTypes.assertThatThrownBy;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DefaultUrlValidatorTest {

    private UrlValidator urlValidator;

    @Mock
    private UrlRepository urlRepository;

    @Mock
    private UserService userService;

    @Mock
    private AppProperties appProperties;

    @Mock
    private SafeBrowsingService safeBrowsingService;

    @BeforeEach
    void setUp() {
        this.urlValidator = new DefaultUrlValidator(userService, urlRepository, appProperties, safeBrowsingService);
    }

    @Test
    void shouldValidateLongUrlInUrl() {
        final Url url = Url.builder().longUrl("LongUrl").build();
        assertThatCode(() -> urlValidator.longUrlInUrl(url))
                .doesNotThrowAnyException();
    }

    @Test
    void shouldFailValidateLongUrlInUrl() {
        final Url url = Url.builder().build();
        assertThatThrownBy(() -> urlValidator.longUrlInUrl(url))
                .isInstanceOf(LongUrlNotSpecifiedException.class)
                .hasMessage("URL is required");
    }

    @Test
    void shouldValidateShortUrlIsUnique() {
        final String shortUrl = "test";
        when(urlRepository.existsUrlByShortUrlAndActiveTrue(shortUrl)).thenReturn(false);
        assertThatCode(() -> urlValidator.checkIfShortUrlIsUnique(shortUrl))
                .doesNotThrowAnyException();
    }

    @Test
    void shouldFailValidateShortUrlIsUnique() {
        final String shortUrl = "test";
        when(urlRepository.existsUrlByShortUrlAndActiveTrue(shortUrl)).thenReturn(true);
        assertThatThrownBy(() -> urlValidator.checkIfShortUrlIsUnique(shortUrl))
                .isInstanceOf(ShortUrlAlreadyExistsException.class)
                .hasMessage("This short URL is already taken. Try another.");
    }

    @Test
    void shouldRejectLongUrlWithDoubledScheme() {
        assertLongUrlRejected("https://https//wedding.hrva.cc");
    }

    @Test
    void shouldRejectLongUrlWithoutColonAfterScheme() {
        assertLongUrlRejected("https//wedding.hrva.cc");
    }

    @Test
    void shouldRejectLongUrlWithSingleSlashAfterScheme() {
        assertLongUrlRejected("https:/x.com");
    }

    @Test
    void shouldRejectLongUrlWithoutSlashesAfterScheme() {
        assertLongUrlRejected("https:x.com");
    }

    @Test
    void shouldRejectLongUrlWithTripleSlashAfterScheme() {
        assertLongUrlRejected("https:///x.com");
    }

    @Test
    void shouldRejectFtpLongUrl() {
        assertLongUrlRejected("ftp://x.com");
    }

    @Test
    void shouldRejectJavascriptLongUrl() {
        assertLongUrlRejected("javascript:alert(1)");
    }

    @Test
    void shouldRejectLongUrlWithWhitespaceInHost() {
        assertLongUrlRejected("https://exa mple.com");
    }

    @Test
    void shouldRejectLongUrlWithWhitespaceInPath() {
        assertLongUrlRejected("https://example.com/a b");
    }

    @Test
    void shouldRejectLongUrlWithHostWithoutDot() {
        assertLongUrlRejected("https://localhost");
    }

    @Test
    void shouldAcceptHttpsLongUrl() {
        assertLongUrlAccepted("https://wedding.hrva.cc");
    }

    @Test
    void shouldAcceptHttpLongUrlWithQueryAndFragment() {
        assertLongUrlAccepted("http://example.com/a?b=c#d");
    }

    @Test
    void shouldAcceptUppercaseSchemeLongUrl() {
        assertLongUrlAccepted("HTTP://Example.com/a");
    }

    @Test
    void shouldAcceptIpv4LongUrl() {
        assertLongUrlAccepted("https://192.168.1.10/x");
    }

    @Test
    void shouldAcceptIpv6LongUrl() {
        assertLongUrlAccepted("https://[2001:db8::1]/x");
    }

    @Test
    void shouldAcceptPunycodeLongUrl() {
        assertLongUrlAccepted("https://xn--bcher-kva.example/");
    }

    @Test
    void shouldAcceptUnicodeHostLongUrl() {
        assertLongUrlAccepted("https://bücher.example/");
    }

    @Test
    void shouldAcceptLongUrlWithUnencodedCharactersInQuery() {
        assertLongUrlAccepted("https://fonts.googleapis.com/css?family=Roboto|Open+Sans&a[]={x}");
    }

    private void assertLongUrlRejected(final String longUrl) {
        final Url url = Url.builder().longUrl(longUrl).build();
        assertThatThrownBy(() -> urlValidator.checkIfLongUrlIsValid(url))
                .isInstanceOf(UrlValidationException.class)
                .hasMessage("That doesn't look like a valid web address. Check the URL and try again.");
    }

    private void assertLongUrlAccepted(final String longUrl) {
        final Url url = Url.builder().longUrl(longUrl).build();
        assertThatCode(() -> urlValidator.checkIfLongUrlIsValid(url))
                .doesNotThrowAnyException();
    }
}