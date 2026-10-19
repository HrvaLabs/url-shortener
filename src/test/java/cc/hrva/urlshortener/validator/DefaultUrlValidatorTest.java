package cc.hrva.urlshortener.validator;

import cc.hrva.urlshortener.configuration.properties.AppProperties;
import cc.hrva.urlshortener.exception.LongUrlNotSpecifiedException;
import cc.hrva.urlshortener.exception.ShortUrlAlreadyExistsException;
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
    void shouldRejectMcpAsReservedAlias() {
        assertThatThrownBy(() -> urlValidator.checkIfShortUrlIsReserved("MCP"))
                .isInstanceOf(ShortUrlAlreadyExistsException.class)
                .hasMessage("This short URL is reserved. Try another.");
    }
}