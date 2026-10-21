package cc.hrva.urlshortener.service;

import cc.hrva.urlshortener.configuration.properties.AppProperties;
import cc.hrva.urlshortener.service.impl.DefaultSuspiciousUrlService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

class DefaultSuspiciousUrlServiceTest {

    private SuspiciousUrlService suspiciousUrlService;

    @BeforeEach
    void setUp() {
        final var appProperties = new AppProperties();
        appProperties.setServerUrl("https://hrva.cc");
        suspiciousUrlService = new DefaultSuspiciousUrlService(appProperties);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "https://gableci.hrva.cc",
            "https://www.youtube.com/watch?v=3zGiNpmrMKM",
            "https://www.njuskalo.hr/auti",
            "https://www.jofogas.hu/budapest",
            "https://www.google.co.uk/search?q=currahee",
            "https://drive.proton.me/urls/ABC",
            "https://www.jutarnji.hr/life/zdravlje/svaka-deseta-zena",
            "https://cloud.hrvalabs.net/s/N6TBS5yZ69FRxxy",
            "https://maps.app.goo.gl/Ux7Qm1aBcD",
            "https://photos.app.goo.gl/q2WzXk"
    })
    void leavesOrdinaryLinksAlone(final String longUrl) {
        assertThat(suspiciousUrlService.findReasons(longUrl)).isEmpty();
    }

    @Test
    void flagsALookalikeOfAKnownBrand() {
        assertThat(suspiciousUrlService.findReasons("https://njuskaio.cam/a/MAUB3vtf3O04TEep")).contains("imitates njuskalo");
    }

    @Test
    void flagsABrandNameOnSomeoneElsesDomain() {
        assertThat(suspiciousUrlService.findReasons("https://jofogas.cards/r/49134101")).contains("imitates jofogas");
        assertThat(suspiciousUrlService.findReasons("https://jofogas.info-auth.com/a/mCuyFSXkNHWYOl3t")).contains("imitates jofogas");
    }

    @ParameterizedTest
    @CsvSource({
            "https://dhl.pages.dev/track, imitates dhl",
            "https://olx.github.io/, imitates olx",
            "https://amazon.blogspot.com/, imitates amazon",
            "https://google.web.app/, imitates google",
            "https://vinted.netlify.app/login, imitates vinted",
            "https://vinted.netlify.app/login, login or verification lure"
    })
    void flagsABrandNameOnASharedHostingSite(final String longUrl, final String reason) {
        assertThat(suspiciousUrlService.findReasons(longUrl)).contains(reason);
    }

    @ParameterizedTest
    @ValueSource(strings = {"https://www.amazon.de/dp/B0CX23V2ZK", "https://www.ebay.co.uk/itm/1234", "https://www.olx.pl/oferta"})
    void trustsABrandThatAcceptsAnyEndingUnderEachCountryEnding(final String longUrl) {
        assertThat(suspiciousUrlService.findReasons(longUrl)).isEmpty();
    }

    @Test
    void flagsLoginAndVerificationLures() {
        assertThat(suspiciousUrlService.findReasons("https://info-auth.com/a/-0mdxVa5XM47j8ns")).contains("login or verification lure");
        assertThat(suspiciousUrlService.findReasons("https://app-onlinebanking.de/NobleCU/index.php?00")).contains("login or verification lure");
        assertThat(suspiciousUrlService.findReasons("https://zcpfmdl.com/?secure")).contains("login or verification lure");
    }

    @Test
    void flagsHighRiskDomainEndings() {
        assertThat(suspiciousUrlService.findReasons("https://findfavoritedate.life/?u=f6yp608&o=mctw1k1&t=_free")).contains("high-risk domain ending .life");
    }

    @Test
    void flagsFreeDynamicDnsHosts() {
        assertThat(suspiciousUrlService.findReasons("https://tradicsummer.work.gd/?apps")).contains("free dynamic DNS host");
    }

    @Test
    void flagsLinksThatHideTheirDestinationBehindAnotherShortener() {
        assertThat(suspiciousUrlService.findReasons("https://t9y.me/_zPx")).contains("hides its destination behind another shortener");
        assertThat(suspiciousUrlService.findReasons("https://l.linklyhq.com/l/1jjds")).contains("hides its destination behind another shortener");
        assertThat(suspiciousUrlService.findReasons("https://hrva.cc/Pb1mG")).contains("hides its destination behind another shortener");
    }

    @Test
    void stillFlagsPlatformShareShortenersForReview() {
        assertThat(suspiciousUrlService.findReasons("https://t.co/AbC123")).contains("hides its destination behind another shortener");
        assertThat(suspiciousUrlService.findReasons("https://lnkd.in/dX9yZ")).contains("hides its destination behind another shortener");
    }

    @Test
    void flagsRawIpAddressesAndPunycode() {
        assertThat(suspiciousUrlService.findReasons("http://185.12.4.9/login")).contains("IP address instead of a domain");
        assertThat(suspiciousUrlService.findReasons("https://xn--pypal-4ve.com/")).contains("punycode domain");
    }

    @ParameterizedTest
    @ValueSource(strings = {"https://bit.ly/3abc", "https://www.tinyurl.com/x", "https://kaywa.me/E7RVW", "https://hrva.cc/sd", "https://HRVA.CC"})
    void recognisesOtherShorteners(final String longUrl) {
        assertThat(suspiciousUrlService.isShortener(longUrl)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"https://app.hrva.cc/", "https://www.youtube.com/watch?v=1", "not a url",
            "https://maps.app.goo.gl/Ux7Qm1aBcD", "https://t.co/AbC123", "https://lnkd.in/dX9yZ"})
    void doesNotRefuseOrdinarySitesOrPlatformShareLinks(final String longUrl) {
        assertThat(suspiciousUrlService.isShortener(longUrl)).isFalse();
    }

}
