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
            "https://photos.app.goo.gl/q2WzXk",
            "https://apple.com/"
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
    @CsvSource({
            "https://amazon.evil.unknowntld/login, imitates amazon",
            "https://amazon.evil.unknowntld/login, login or verification lure",
            "https://amazon.unknowntld, imitates amazon"
    })
    void flagsABrandNameUnderAnUnknownDomainEnding(final String longUrl, final String reason) {
        assertThat(suspiciousUrlService.findReasons(longUrl)).contains(reason);
    }

    @ParameterizedTest
    @ValueSource(strings = {"https://login.amazon.de/", "https://amazon.co.uk/"})
    void trustsABrandsOwnDomainUnderAKnownEnding(final String longUrl) {
        assertThat(suspiciousUrlService.findReasons(longUrl)).isEmpty();
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

    @ParameterizedTest
    @ValueSource(strings = {"http://185.12.4.9/login", "http://[2001:db8::1]/login", "https://[::1]:8443/", "http://0x7f.0.0.1/",
            "http://0x7f000001/login", "http://0177.0.0.1/", "http://2130706433/", "http://127.1/", "http://01.2.3.4/"})
    void flagsRawIpAddresses(final String longUrl) {
        assertThat(suspiciousUrlService.findReasons(longUrl)).containsExactly("IP address instead of a domain");
    }

    @ParameterizedTest
    @ValueSource(strings = {"https://p\u0430ypal.com/", "https://xn--pypal-4ve.com/", "https://P\u0410YPAL.com/"})
    void flagsALabelThatMixesLatinAndCyrillicInEitherSpelling(final String longUrl) {
        assertThat(suspiciousUrlService.findReasons(longUrl)).containsExactly("mixes Latin and Cyrillic letters", "imitates paypal");
    }

    @ParameterizedTest
    @ValueSource(strings = {"https://g\u03bfogle.com/", "https://xn--gogle-rce.com/"})
    void flagsALabelThatMixesLatinAndGreek(final String longUrl) {
        assertThat(suspiciousUrlService.findReasons(longUrl)).containsExactly("mixes Latin and Greek letters", "imitates google");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "https://\u0430\u0440\u0440\u04cf\u0435.com/",
            "https://xn--80ak6aa92e.com/",
            "https://\u0410\u0420\u0420\u04c0\u0415.com/"
    })
    void flagsAWholeCyrillicLabelThatSpellsABrand(final String longUrl) {
        assertThat(suspiciousUrlService.findReasons(longUrl)).containsExactly("punycode domain", "imitates apple");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "https://čokolada.hr/",
            "https://xn--okolada-i6a.hr/",
            "https://žabica.hr/",
            "https://xn--abica-tib.hr/",
            "https://müller.de/",
            "https://xn--mller-kva.de/",
            "https://www.đakovo.hr/grad",
            "https://www.xn--akovo-4ya.hr/grad",
            "https://hôtel-paris.fr/",
            "https://xn--htel-paris-rbb.fr/",
            "https://straße.de/",
            "https://login.amazon.de/"
    })
    void leavesLatinLettersWithDiacriticsAloneInEitherSpelling(final String longUrl) {
        assertThat(suspiciousUrlService.findReasons(longUrl)).isEmpty();
    }

    @ParameterizedTest
    @CsvSource({
            "https://ápple.com/, imitates apple",
            "https://xn--pple-4na.com/, imitates apple",
            "https://gmaíl.com/, imitates gmail",
            "https://xn--gmal-xpa.com/, imitates gmail",
            "https://ébay.com/, imitates ebay",
            "https://xn--bay-9la.com/, imitates ebay",
            "https://ólx.hr/, imitates olx",
            "https://xn--lx-4ja.hr/, imitates olx",
            "https://dhł.de/, imitates dhl",
            "https://xn--dh-yqa.de/, imitates dhl",
            "https://pâypál.com/, imitates paypal",
            "https://xn--pypl-7nad.com/, imitates paypal",
            "https://ámázon.com/, imitates amazon",
            "https://xn--mzon-4nab.com/, imitates amazon",
            "https://ƿayƿal.com/, imitates paypal",
            "https://xn--ayal-ukbc.com/, imitates paypal",
            "https://gmaɩl.com/, imitates gmail",
            "https://xn--gmal-78b.com/, imitates gmail",
            "https://vérify.evil.com/, login or verification lure",
            "https://xn--vrify-bsa.evil.com/, login or verification lure",
            "https://lógin.evil.com/, login or verification lure",
            "https://xn--lgin-qqa.evil.com/, login or verification lure"
    })
    void foldsDiacriticsBeforeLookingForABrandOrALureInEitherSpelling(final String longUrl, final String reason) {
        assertThat(suspiciousUrlService.findReasons(longUrl)).containsExactly(reason);
    }

    @ParameterizedTest
    @ValueSource(strings = {"https://p\u0430\u0301ypal.com/", "https://xn--pypal-6ed43s.com/"})
    void foldsAMarkOnACyrillicLookalikeBeforeLookingForABrand(final String longUrl) {
        assertThat(suspiciousUrlService.findReasons(longUrl)).containsExactly("mixes Latin and Cyrillic letters", "imitates paypal");
    }

    @ParameterizedTest
    @ValueSource(strings = {"https://col\u00b7legi.cat/", "https://xn--collegi-xma.cat/"})
    void leavesACatalanMiddleDotBetweenTwoLsAloneInEitherSpelling(final String longUrl) {
        assertThat(suspiciousUrlService.findReasons(longUrl)).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"https://co\u00b7legi.cat/", "https://\u00b7llegi.cat/", "https://coll\u00b7.cat/"})
    void keepsFlaggingAMiddleDotThatIsNotBetweenTwoLs(final String longUrl) {
        assertThat(suspiciousUrlService.findReasons(longUrl)).containsExactly("punycode domain");
    }

    @ParameterizedTest
    @ValueSource(strings = {"https://pay\u0378pal.com/", "https://xn--paypal-dme.com/"})
    void namesAnUnassignedCharacterInPlainWords(final String longUrl) {
        assertThat(suspiciousUrlService.findReasons(longUrl)).containsExactly("mixes Latin letters with unusual characters");
    }

    @ParameterizedTest
    @ValueSource(strings = {"https://p\u0430ypal.com/login", "https://xn--pypal-4ve.com/login"})
    void flagsTheCyrillicPaypalLoginWithEveryReasonInEitherSpelling(final String longUrl) {
        assertThat(suspiciousUrlService.findReasons(longUrl))
                .containsExactly("mixes Latin and Cyrillic letters", "imitates paypal", "login or verification lure");
    }

    @ParameterizedTest
    @ValueSource(strings = {"https://\u043f\u0440\u0438\u043c\u0435\u0440.\u0440\u0444/", "https://xn--e1afmkfd.xn--p1ai/"})
    void keepsFlaggingAWholeNonLatinNameThatImitatesNoBrandAsPunycode(final String longUrl) {
        assertThat(suspiciousUrlService.findReasons(longUrl)).containsExactly("punycode domain");
    }

    @ParameterizedTest
    @ValueSource(strings = {"https://i♥ny.com/", "https://xn--iny-zx5a.com/", "https://日本の.jp/", "https://xn--u9ju32nb2a.jp/"})
    void keepsFlaggingASymbolOrAUsualNonLatinMixAsPunycode(final String longUrl) {
        assertThat(suspiciousUrlService.findReasons(longUrl)).containsExactly("punycode domain");
    }

    @Test
    void keepsFlaggingPunycodeThatDoesNotDecode() {
        assertThat(suspiciousUrlService.findReasons("https://xn--zz.com/")).containsExactly("punycode domain");
    }

    @ParameterizedTest
    @ValueSource(strings = {"https://ñetflix.com/", "https://xn--etflix-vwa.com/"})
    void stillFlagsALatinLookalikeOfABrandWithADiacritic(final String longUrl) {
        assertThat(suspiciousUrlService.findReasons(longUrl)).containsExactly("imitates netflix");
    }

    @ParameterizedTest
    @ValueSource(strings = {"https://čokolada.top/login", "https://xn--okolada-i6a.top/login"})
    void readsAUnicodeHostInsteadOfSkippingEveryCheck(final String longUrl) {
        assertThat(suspiciousUrlService.findReasons(longUrl)).containsExactly("login or verification lure", "high-risk domain ending .top");
    }

    @Test
    void readsAHostWithAnUnderscoreInsteadOfSkippingEveryCheck() {
        assertThat(suspiciousUrlService.findReasons("https://my_shop.duckdns.org/")).containsExactly("free dynamic DNS host");
    }

    @Test
    void readsAFullWidthShortenerLikeABrowser() {
        assertThat(suspiciousUrlService.findReasons("https://\uff42\uff49\uff54\uff0e\uff4c\uff59/3abc"))
                .containsExactly("hides its destination behind another shortener");
    }

    @ParameterizedTest
    @ValueSource(strings = {"https://bit.ly/3abc", "https://www.tinyurl.com/x", "https://kaywa.me/E7RVW", "https://hrva.cc/sd", "https://HRVA.CC",
            "https://\uff42\uff49\uff54\uff0e\uff4c\uff59/3abc", "https://\uff48\uff52\uff56\uff41.cc/sd", "https://bit.ly./3abc"})
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
