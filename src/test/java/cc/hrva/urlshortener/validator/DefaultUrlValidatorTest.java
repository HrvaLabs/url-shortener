package cc.hrva.urlshortener.validator;

import cc.hrva.urlshortener.configuration.properties.AppProperties;
import cc.hrva.urlshortener.dto.LinkVerdict;
import cc.hrva.urlshortener.dto.LinkVerdict.Outcome;
import cc.hrva.urlshortener.exception.ApiException;
import cc.hrva.urlshortener.exception.LongUrlNotSpecifiedException;
import cc.hrva.urlshortener.exception.NoAuthorizationException;
import cc.hrva.urlshortener.model.User;
import cc.hrva.urlshortener.model.codebook.Authorities;
import cc.hrva.urlshortener.model.enums.BlockReason;
import java.util.List;
import java.util.Locale;
import cc.hrva.urlshortener.exception.ShortUrlAlreadyExistsException;
import cc.hrva.urlshortener.exception.UrlValidationException;
import cc.hrva.urlshortener.model.Url;
import cc.hrva.urlshortener.repository.UrlRepository;
import cc.hrva.urlshortener.service.LinkCheckService;
import cc.hrva.urlshortener.service.ReservedAliasService;
import cc.hrva.urlshortener.service.SafeBrowsingService;
import cc.hrva.urlshortener.service.SuspiciousUrlService;
import cc.hrva.urlshortener.service.UserService;
import cc.hrva.urlshortener.service.impl.DefaultSettingsService;
import cc.hrva.urlshortener.service.settings.SettingKey;
import cc.hrva.urlshortener.service.settings.SettingsServiceFixtures;
import cc.hrva.urlshortener.util.DomainNames;
import cc.hrva.urlshortener.validator.impl.DefaultUrlValidator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.AssertionsForClassTypes.assertThat;
import static org.assertj.core.api.AssertionsForClassTypes.assertThatCode;
import static org.assertj.core.api.AssertionsForClassTypes.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DefaultUrlValidatorTest {

    private UrlValidator urlValidator;

    @Mock
    private UrlRepository urlRepository;

    @Mock
    private UserService userService;

    private final DefaultSettingsService settingsService = SettingsServiceFixtures.defaults(new AppProperties());

    @Mock
    private SafeBrowsingService safeBrowsingService;

    @Mock
    private SuspiciousUrlService suspiciousUrlService;

    @Mock
    private LinkCheckService linkCheckService;

    @Mock
    private ReservedAliasService reservedAliasService;

    @BeforeEach
    void setUp() {
        this.urlValidator = new DefaultUrlValidator(userService, urlRepository, safeBrowsingService, suspiciousUrlService, linkCheckService,
                reservedAliasService, settingsService);
    }

    @Test
    void refusesBlocklistedDestinationsWithoutNamingTheList() {
        final var url = Url.builder().longUrl("https://njuskaio.cam/a/MAUB3").build();
        when(linkCheckService.check("https://njuskaio.cam/a/MAUB3")).thenReturn(new LinkVerdict(
                Outcome.BLOCK, List.of("on list HaGeZi TIF medium"), BlockReason.BLOCKLIST, "HaGeZi TIF medium"));

        assertThatThrownBy(() -> urlValidator.checkIfLongUrlIsNotBlocklisted(url))
                .isInstanceOf(UrlValidationException.class)
                .hasMessage("This destination is on a blocklist and can't be shortened. If you think that's a mistake, contact us.");
    }

    @Test
    void passesFlaggedDestinationsWithTheirVerdict() {
        final var url = Url.builder().longUrl("https://findfavoritedate.life/?u=f6yp608").build();
        final var verdict = new LinkVerdict(Outcome.FLAG, List.of("domain registered in the last 30 days"), null, null);
        when(linkCheckService.check("https://findfavoritedate.life/?u=f6yp608")).thenReturn(verdict);

        assertThat(urlValidator.checkIfLongUrlIsNotBlocklisted(url)).isEqualTo(verdict);
    }

    @Test
    void passesAllowedDestinationsWithTheirVerdict() {
        final var url = Url.builder().longUrl("https://github.com/HrvaLabs").build();
        final var verdict = new LinkVerdict(Outcome.ALLOWED, List.of("allowed domain github.com"), null, null);
        when(linkCheckService.check("https://github.com/HrvaLabs")).thenReturn(verdict);

        assertThat(urlValidator.checkIfLongUrlIsNotBlocklisted(url)).isEqualTo(verdict);
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
        when(urlRepository.aliasTaken(null, shortUrl)).thenReturn(false);
        assertThatCode(() -> urlValidator.checkIfShortUrlIsUnique(null, shortUrl))
                .doesNotThrowAnyException();
    }

    @Test
    void anAliasStaysTakenAfterItsLinkIsDeactivated() {
        when(urlRepository.aliasTaken(null, "promo")).thenReturn(true);

        assertThatThrownBy(() -> urlValidator.checkIfShortUrlIsUnique(null, "promo"))
                .isInstanceOf(ShortUrlAlreadyExistsException.class)
                .hasMessage("This short URL is already taken. Try another.");
    }

    @Test
    void reactivationIsRefusedWhileAnotherActiveLinkUsesTheAlias() {
        when(urlRepository.aliasActive(null, "promo")).thenReturn(true);

        assertThatThrownBy(() -> urlValidator.checkIfShortUrlIsFreeToReactivate(null, "promo"))
                .isInstanceOf(ShortUrlAlreadyExistsException.class)
                .hasMessage("Another active link already uses this short URL.");
    }

    @Test
    void aTakenAliasOnTheSameDomainIs409() {
        when(urlRepository.aliasTaken(7L, "menu")).thenReturn(true);

        assertThatThrownBy(() -> urlValidator.checkIfShortUrlIsUnique(7L, "menu"))
                .isInstanceOf(ShortUrlAlreadyExistsException.class)
                .hasMessage("This short URL is already taken. Try another.");
    }

    @Test
    void anAliasTakenOnHrvaIsFreeOnADomain() {
        when(urlRepository.aliasTaken(7L, "menu")).thenReturn(false);

        assertThatCode(() -> urlValidator.checkIfShortUrlIsUnique(7L, "menu")).doesNotThrowAnyException();

        verify(urlRepository, never()).aliasTaken(isNull(), anyString());
    }

    @Test
    void reactivationLooksForAnotherActiveLinkOnTheSameDomainOnly() {
        when(urlRepository.aliasActive(7L, "menu")).thenReturn(false, true);

        assertThatCode(() -> urlValidator.checkIfShortUrlIsFreeToReactivate(7L, "menu")).doesNotThrowAnyException();
        assertThatThrownBy(() -> urlValidator.checkIfShortUrlIsFreeToReactivate(7L, "menu"))
                .isInstanceOf(ShortUrlAlreadyExistsException.class)
                .hasMessage("Another active link already uses this short URL.");

        verify(urlRepository, never()).aliasActive(isNull(), anyString());
    }

    @Test
    void ownersCannotReactivateALinkBlockedAsUnsafe() {
        final var owner = userWithRole(2L, "ROLE_USER");
        final var url = Url.builder().owner(owner).threatType(cc.hrva.urlshortener.model.enums.ThreatType.MALWARE).build();
        when(userService.getUserFromToken()).thenReturn(owner);

        assertThatThrownBy(() -> urlValidator.checkIfUrlNotBlocked(url))
                .isInstanceOf(UrlValidationException.class)
                .hasMessage("This link was blocked as unsafe. Contact support if you think that's a mistake.");
    }

    @Test
    void adminsMayReactivateABlockedLink() {
        final var url = Url.builder().threatType(cc.hrva.urlshortener.model.enums.ThreatType.MALWARE).build();
        when(userService.getUserFromToken()).thenReturn(userWithRole(3L, "ROLE_ADMIN"));

        assertThatCode(() -> urlValidator.checkIfUrlNotBlocked(url)).doesNotThrowAnyException();
    }

    @Test
    void unblockedLinksPassTheBlockCheck() {
        assertThatCode(() -> urlValidator.checkIfUrlNotBlocked(Url.builder().build())).doesNotThrowAnyException();
    }

    @Test
    void ownersCannotReactivateALinkWithOnlyABlockReason() {
        final var owner = userWithRole(2L, "ROLE_USER");
        final var url = Url.builder().owner(owner).blockReason(BlockReason.DOMAIN_RULE).build();
        when(userService.getUserFromToken()).thenReturn(owner);

        assertThatThrownBy(() -> urlValidator.checkIfUrlNotBlocked(url))
                .isInstanceOf(UrlValidationException.class)
                .hasMessage("This link was blocked as unsafe. Contact support if you think that's a mistake.");
    }

    @Test
    void adminsMayReactivateALinkWithOnlyABlockReason() {
        final var url = Url.builder().blockReason(BlockReason.SCAM_SIGNALS).build();
        when(userService.getUserFromToken()).thenReturn(userWithRole(3L, "ROLE_ADMIN"));

        assertThatCode(() -> urlValidator.checkIfUrlNotBlocked(url)).doesNotThrowAnyException();
    }

    @Test
    void anAdminReactivatesALinkOnAnAllowListedDomainWithoutTheChecks() {
        final var url = Url.builder().longUrl("https://docs.google.com/x").blockReason(BlockReason.BLOCKLIST).build();
        when(userService.getUserFromToken()).thenReturn(userWithRole(3L, "ROLE_ADMIN"));
        when(linkCheckService.isAllowListed("https://docs.google.com/x")).thenReturn(true);

        assertThatCode(() -> urlValidator.checkIfDestinationNotBlocked(url)).doesNotThrowAnyException();
        verify(linkCheckService, never()).check(anyString());
    }

    @Test
    void anAdminIsToldWhyADestinationIsStillBlocked() {
        final var url = Url.builder().longUrl("https://jofogas.cards/x").blockReason(BlockReason.DOMAIN_RULE).build();
        when(userService.getUserFromToken()).thenReturn(userWithRole(3L, "ROLE_ADMIN"));
        when(linkCheckService.check("https://jofogas.cards/x")).thenReturn(new LinkVerdict(
                Outcome.BLOCK, List.of("blocked domain jofogas.cards", "on list HaGeZi Fake"), BlockReason.DOMAIN_RULE, "jofogas.cards"));

        assertThatThrownBy(() -> urlValidator.checkIfDestinationNotBlocked(url))
                .isInstanceOf(UrlValidationException.class)
                .hasMessage("This destination is still blocked: blocked domain jofogas.cards, on list HaGeZi Fake");
    }

    @Test
    void anOwnerReactivatingABlockedDestinationIsNotToldWhichListHasIt() {
        final var url = Url.builder().longUrl("https://jofogas.cards/x").build();
        when(userService.getUserFromToken()).thenReturn(userWithRole(2L, "ROLE_USER"));
        when(linkCheckService.check("https://jofogas.cards/x")).thenReturn(new LinkVerdict(
                Outcome.BLOCK, List.of("on list HaGeZi Fake"), BlockReason.BLOCKLIST, "HaGeZi Fake"));

        assertThatThrownBy(() -> urlValidator.checkIfDestinationNotBlocked(url))
                .isInstanceOf(UrlValidationException.class)
                .hasMessage("This destination is on a blocklist and can't be shortened. If you think that's a mistake, contact us.");
        verify(linkCheckService, never()).isAllowListed(anyString());
    }

    @Test
    void aDestinationTheCheckNoLongerBlocksCanBeReactivated() {
        final var url = Url.builder().longUrl("https://example.org/x").blockReason(BlockReason.BLOCKLIST).build();
        when(userService.getUserFromToken()).thenReturn(userWithRole(3L, "ROLE_ADMIN"));
        when(linkCheckService.check("https://example.org/x")).thenReturn(new LinkVerdict(Outcome.FLAG, List.of("login or verification lure"), null, null));

        assertThatCode(() -> urlValidator.checkIfDestinationNotBlocked(url)).doesNotThrowAnyException();
    }

    @Test
    void shouldFailValidateShortUrlIsUnique() {
        final String shortUrl = "test";
        when(urlRepository.aliasTaken(null, shortUrl)).thenReturn(true);
        assertThatThrownBy(() -> urlValidator.checkIfShortUrlIsUnique(null, shortUrl))
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
    void shouldLetOwnerManageTheirUrlWhenTheyAreLoadedAsAnotherInstance() {
        final Url url = Url.builder().owner(userWithRole(1L, "ROLE_USER")).build();
        when(userService.getUserFromToken()).thenReturn(userWithRole(1L, "ROLE_USER"));

        assertThatCode(() -> urlValidator.verifyUserAdminOrOwner(url))
                .doesNotThrowAnyException();
    }

    @Test
    void shouldRejectMcpAsReservedAlias() {
        when(reservedAliasService.isReserved("MCP")).thenReturn(true);

        assertThatThrownBy(() -> urlValidator.checkIfShortUrlIsReserved("MCP"))
                .isInstanceOf(ShortUrlAlreadyExistsException.class)
                .hasMessage("This short URL is reserved. Try another.");
    }

    @Test
    void shouldRejectACustomReservedAlias() {
        when(reservedAliasService.isReserved("Promo")).thenReturn(true);

        assertThatThrownBy(() -> urlValidator.checkIfShortUrlIsReserved("Promo"))
                .isInstanceOf(ShortUrlAlreadyExistsException.class)
                .hasMessage("This short URL is reserved. Try another.");
    }

    @Test
    void shouldAllowAnAliasThatIsNotReserved() {
        when(reservedAliasService.isReserved("summer-menu")).thenReturn(false);

        assertThatCode(() -> urlValidator.checkIfShortUrlIsReserved("summer-menu"))
                .doesNotThrowAnyException();
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

    @ParameterizedTest
    @ValueSource(strings = {"not a url", "http://", "https:///login", "https://exa mple.com/x", "https://[zz/x"})
    void destinationsWhoseHostCannotBeReadAreRefusedBeforeTheLinkCheck(final String longUrl) {
        assertThat(DomainNames.hostOf(longUrl)).isEmpty();
        assertLongUrlRejected(longUrl);
        verifyNoInteractions(linkCheckService);
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

    @Test
    void acceptsADestinationOfExactly2048Characters() {
        final var destination = "https://example.com/" + "a".repeat(2028);

        assertThat(destination.length()).isEqualTo(2048);
        assertLongUrlAccepted(destination);
    }

    @Test
    void refusesADestinationOver2048CharactersBeforeAnyLinkCheck() {
        final var url = Url.builder().longUrl("https://example.com/" + "a".repeat(2029)).build();

        assertThatThrownBy(() -> urlValidator.checkIfLongUrlIsValid(url))
                .isInstanceOf(UrlValidationException.class)
                .hasMessage("Destination URL must be 2048 characters or fewer");
        verifyNoInteractions(linkCheckService, suspiciousUrlService, safeBrowsingService);
    }

    @Test
    void refusesAnOverlongDestinationForItsLengthBeforeItsFormat() {
        final var url = Url.builder().longUrl("not a url " + "a".repeat(2039)).build();

        assertThatThrownBy(() -> urlValidator.checkIfLongUrlIsValid(url))
                .isInstanceOf(UrlValidationException.class)
                .hasMessage("Destination URL must be 2048 characters or fewer");
    }

    @Test
    void refusesAHostWithALabelOver63Characters() {
        assertLongUrlRejected("https://" + "a".repeat(64) + ".bit.ly/x");
        assertLongUrlRejected("https://www." + "a".repeat(64) + ".com/");
    }

    @Test
    void acceptsAHostWithALabelOfExactly63Characters() {
        assertLongUrlAccepted("https://" + "a".repeat(63) + ".example.com/");
    }

    @Test
    void acceptsAHostOfExactly253Characters() {
        final var host = hostOfLength(253);

        assertThat(host).hasSize(253);
        assertLongUrlAccepted("https://" + host + "/login");
        assertLongUrlAccepted("https://" + host + "./login");
    }

    @Test
    void refusesAHostOver253Characters() {
        final var host = hostOfLength(254);

        assertThat(host).hasSize(254);
        assertLongUrlRejected("https://" + host + "/login");
        assertLongUrlRejected("https://" + host.toUpperCase(Locale.ROOT) + ":8443/login");
        verifyNoInteractions(linkCheckService, suspiciousUrlService, safeBrowsingService);
    }

    @Test
    void anIdnHostCountsInItsAsciiForm() {
        final var ascii = hostOfLength(235) + ".xn--okolada-i6a.hr";
        final var unicode = ascii.replace("xn--okolada-i6a", "čokolada");

        assertThat(ascii).hasSize(254);
        assertThat(unicode).hasSize(247);
        assertLongUrlAccepted("https://" + unicode.substring(1) + "/");
        assertLongUrlRejected("https://" + unicode + "/");
    }

    @Test
    void aMissingDestinationIsStillRefusedAsNotAWebAddress() {
        assertLongUrlRejected(null);
    }

    @Test
    void anonymousLinksFollowTheSetting() {
        assertThatCode(() -> urlValidator.checkIfAnonymousUrlCreationEnabled()).doesNotThrowAnyException();

        SettingsServiceFixtures.turnOff(settingsService, SettingKey.ANONYMOUS_LINKS);

        assertThatThrownBy(() -> urlValidator.checkIfAnonymousUrlCreationEnabled())
                .isInstanceOf(ApiException.class)
                .hasMessage("Sign in to create short URLs");
    }

    @Test
    void safeBrowsingCheckOffSkipsTheLookup() {
        final var url = Url.builder().longUrl("https://malware.example/download").build();
        SettingsServiceFixtures.turnOff(settingsService, SettingKey.SAFE_BROWSING_CHECK);

        assertThatCode(() -> urlValidator.checkIfUrlSafe(url)).doesNotThrowAnyException();

        verifyNoInteractions(safeBrowsingService);
    }

    @Test
    void safeBrowsingCheckOnRefusesAFlaggedDestination() {
        final var url = Url.builder().longUrl("https://malware.example/download").build();
        when(safeBrowsingService.checkUrlsForThreats("https://malware.example/download")).thenReturn(List.of("MALWARE"));

        assertThatThrownBy(() -> urlValidator.checkIfUrlSafe(url))
                .isInstanceOf(UrlValidationException.class)
                .hasMessage("This URL was flagged as unsafe and cannot be shortened");
    }

    private void assertLongUrlRejected(final String longUrl) {
        final Url url = Url.builder().longUrl(longUrl).build();
        assertThatThrownBy(() -> urlValidator.checkIfLongUrlIsValid(url))
                .isInstanceOf(UrlValidationException.class)
                .hasMessage("That doesn't look like a valid web address. Check the URL and try again.");
    }

    private static String hostOfLength(final int length) {
        final var host = new StringBuilder();
        while (length - host.length() > 67) {
            host.append("a".repeat(62)).append('.');
        }
        return host.append("b".repeat(length - host.length() - 4)).append(".com").toString();
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