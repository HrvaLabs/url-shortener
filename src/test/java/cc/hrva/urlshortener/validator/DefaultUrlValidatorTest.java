package cc.hrva.urlshortener.validator;

import cc.hrva.urlshortener.configuration.properties.AppProperties;
import cc.hrva.urlshortener.exception.LongUrlNotSpecifiedException;
import cc.hrva.urlshortener.exception.NoAuthorizationException;
import cc.hrva.urlshortener.model.User;
import cc.hrva.urlshortener.model.codebook.Authorities;
import java.util.List;
import cc.hrva.urlshortener.exception.ShortUrlAlreadyExistsException;
import cc.hrva.urlshortener.exception.UrlValidationException;
import cc.hrva.urlshortener.model.Url;
import cc.hrva.urlshortener.repository.UrlRepository;
import cc.hrva.urlshortener.service.SafeBrowsingService;
import cc.hrva.urlshortener.service.SuspiciousUrlService;
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

    @Mock
    private SuspiciousUrlService suspiciousUrlService;

    @BeforeEach
    void setUp() {
        this.urlValidator = new DefaultUrlValidator(userService, urlRepository, appProperties, safeBrowsingService, suspiciousUrlService);
    }

    @Test
    void rejectsLinksToOtherShorteners() {
        final var url = Url.builder().longUrl("https://bit.ly/3abc").build();
        when(suspiciousUrlService.isShortener("https://bit.ly/3abc")).thenReturn(true);

        assertThatThrownBy(() -> urlValidator.checkIfLongUrlIsNotAShortener(url))
                .isInstanceOf(UrlValidationException.class)
                .hasMessage("Links to other URL shorteners can't be shortened. Use the final destination instead.");
    }

    @Test
    void acceptsDestinationsThatAreNotShorteners() {
        final var url = Url.builder().longUrl("https://example.com").build();
        when(suspiciousUrlService.isShortener("https://example.com")).thenReturn(false);

        urlValidator.checkIfLongUrlIsNotAShortener(url);
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
    void shouldLetAdminsChangeDestinations() {
        when(userService.getUserFromToken()).thenReturn(userWithRole(3L, "ROLE_ADMIN"));

        assertThatCode(() -> urlValidator.verifyUserAdmin()).doesNotThrowAnyException();
    }

    @Test
    void shouldRefuseRegularUsersChangingDestinations() {
        when(userService.getUserFromToken()).thenReturn(userWithRole(2L, "ROLE_USER"));

        assertThatThrownBy(() -> urlValidator.verifyUserAdmin())
                .isInstanceOf(NoAuthorizationException.class)
                .hasMessage("Only admins can change where a link points");
    }

    @Test
    void shouldRefuseDestinationChangesWithoutSignedInUser() {
        when(userService.getUserFromToken()).thenReturn(null);

        assertThatThrownBy(() -> urlValidator.verifyUserAdmin())
                .isInstanceOf(NoAuthorizationException.class);
    }

    @Test
    void shouldRefuseOwnershipCheckWithoutSignedInUser() {
        final Url url = Url.builder().owner(userWithRole(1L, "ROLE_USER")).build();
        when(userService.getUserFromToken()).thenReturn(null);

        assertThatThrownBy(() -> urlValidator.verifyUserAdminOrOwner(url))
                .isInstanceOf(NoAuthorizationException.class);
    }

    @Test
    void shouldRefuseUserWhoDoesNotOwnTheUrl() {
        final Url url = Url.builder().owner(userWithRole(1L, "ROLE_USER")).build();
        when(userService.getUserFromToken()).thenReturn(userWithRole(2L, "ROLE_USER"));

        assertThatThrownBy(() -> urlValidator.verifyUserAdminOrOwner(url))
                .isInstanceOf(NoAuthorizationException.class);
    }

    @Test
    void shouldRefuseRegularUserOnAnonymousUrl() {
        final Url url = Url.builder().build();
        when(userService.getUserFromToken()).thenReturn(userWithRole(2L, "ROLE_USER"));

        assertThatThrownBy(() -> urlValidator.verifyUserAdminOrOwner(url))
                .isInstanceOf(NoAuthorizationException.class);
    }

    @Test
    void shouldLetAdminManageAnonymousUrl() {
        final Url url = Url.builder().build();
        when(userService.getUserFromToken()).thenReturn(userWithRole(3L, "ROLE_ADMIN"));

        assertThatCode(() -> urlValidator.verifyUserAdminOrOwner(url))
                .doesNotThrowAnyException();
    }

    @Test
    void shouldLetOwnerManageTheirUrl() {
        final var owner = userWithRole(1L, "ROLE_USER");
        final Url url = Url.builder().owner(owner).build();
        when(userService.getUserFromToken()).thenReturn(owner);

        assertThatCode(() -> urlValidator.verifyUserAdminOrOwner(url))
                .doesNotThrowAnyException();
    }

    @Test
    void shouldRejectMcpAsReservedAlias() {
        assertThatThrownBy(() -> urlValidator.checkIfShortUrlIsReserved("MCP"))
                .isInstanceOf(ShortUrlAlreadyExistsException.class)
                .hasMessage("This short URL is reserved. Try another.");
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
    void shouldRejectLongUrlWithUnencodedPipeInQuery() {
        assertLongUrlRejected("https://fonts.googleapis.com/css?family=Roboto|Open+Sans");
    }

    @Test
    void shouldRejectLongUrlWithBarePercentInPath() {
        assertLongUrlRejected("https://example.com/%");
    }

    @Test
    void shouldRejectLongUrlWithUnencodedQuotesInQuery() {
        assertLongUrlRejected("https://example.com/?q=\"quoted\"");
    }

    @Test
    void shouldRejectLongUrlWithUnencodedAngleBracketsInPath() {
        assertLongUrlRejected("https://example.com/<x>");
    }

    @Test
    void shouldRejectLongUrlWithBracesInHost() {
        assertLongUrlRejected("https://ex{}ample.com");
    }

    @Test
    void shouldRejectLongUrlWithNonBreakingSpaceInPath() {
        assertLongUrlRejected("https://example.com/a b");
    }

    @Test
    void shouldRejectLongUrlWithOnlyDotAsHost() {
        assertLongUrlRejected("https://.");
    }

    @Test
    void shouldRejectLongUrlWithOnlyDotsAsHost() {
        assertLongUrlRejected("https://..");
    }

    @Test
    void shouldRejectLongUrlWithHostStartingWithDot() {
        assertLongUrlRejected("https://.com");
    }

    @Test
    void shouldRejectLongUrlWithEmptyHostLabel() {
        assertLongUrlRejected("https://example..com");
    }

    @Test
    void shouldRejectLongUrlWithHostLabelStartingWithHyphen() {
        assertLongUrlRejected("https://-x.com");
    }

    @Test
    void shouldRejectLongUrlWithHostLabelEndingWithHyphen() {
        assertLongUrlRejected("https://x-.com");
    }

    @Test
    void shouldRejectLongUrlWithNonNumericPort() {
        assertLongUrlRejected("https://example.com:abc");
    }

    @Test
    void shouldRejectEmptyLongUrl() {
        assertLongUrlRejected("");
    }

    @Test
    void shouldRejectWhitespaceOnlyLongUrl() {
        assertLongUrlRejected("   ");
    }

    @Test
    void shouldAcceptUnderscoreHostLongUrl() {
        assertLongUrlAccepted("https://my_site.example.com/");
    }

    @Test
    void shouldAcceptIpv6LongUrlWithPort() {
        assertLongUrlAccepted("https://[::1]:8080/x");
    }

    @Test
    void shouldAcceptLongUrlWithPort() {
        assertLongUrlAccepted("https://example.com:8080/p");
    }

    @Test
    void shouldAcceptLongUrlWithPercentEncodedPath() {
        assertLongUrlAccepted("https://example.com/%E2%82%AC");
    }

    @Test
    void shouldAcceptLongUrlWithTrailingDotInHost() {
        assertLongUrlAccepted("https://example.com.");
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

    private static User userWithRole(final Long id, final String role) {
        return User.builder()
                .id(id)
                .authorities(List.of(Authorities.builder().name(role).build()))
                .build();
    }
}