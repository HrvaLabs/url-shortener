package cc.hrva.urlshortener.service;

import cc.hrva.urlshortener.configuration.properties.AppProperties;
import cc.hrva.urlshortener.service.impl.DefaultSuspiciousUrlService;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

class DefaultSuspiciousUrlServiceTest {

    private static final String CYRILLIC_A = codePoints(0x0430);
    private static final String CYRILLIC_IE = codePoints(0x0435);
    private static final String CYRILLIC_CAPITAL_A = codePoints(0x0410);
    private static final String GREEK_CAPITAL_RHO = codePoints(0x03A1);
    private static final String ZERO_WIDTH_JOINER = codePoints(0x200D);
    private static final String ZERO_WIDTH_NON_JOINER = codePoints(0x200C);
    private static final String MIXES_LATIN_AND_CYRILLIC = "mixes Latin and Cyrillic letters";
    private static final String LURE = "login or verification lure";

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


    @ParameterizedTest
    @MethodSource("plainTexts")
    void plainTextHasNoReasons(final String text) {
        assertThat(suspiciousUrlService.findTextReasons(text)).isEmpty();
        assertThat(suspiciousUrlService.findImitatedBrand(text)).isEmpty();
        assertThat(suspiciousUrlService.findNamedBrand(text)).isEmpty();
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   ", "-- __ !!"})
    void textWithoutWordsHasNoReasons(final String text) {
        assertThat(suspiciousUrlService.findTextReasons(text)).isEmpty();
        assertThat(suspiciousUrlService.findImitatedBrand(text)).isEmpty();
        assertThat(suspiciousUrlService.findNamedBrand(text)).isEmpty();
    }

    @Test
    void aMixedWordIsFlagged() {
        assertThat(suspiciousUrlService.findTextReasons("p" + CYRILLIC_A + "ypal support")).containsExactly(MIXES_LATIN_AND_CYRILLIC);
    }

    @Test
    void eachMixIsNamedOnce() {
        final var greekOmicron = codePoints(0x03BF);

        assertThat(suspiciousUrlService.findTextReasons("p" + CYRILLIC_A + "ypal and r" + CYRILLIC_IE + "volut and g" + greekOmicron + "ogle"))
                .containsExactly(MIXES_LATIN_AND_CYRILLIC, "mixes Latin and Greek letters");
    }

    @Test
    void lureWordsAreFlaggedThroughLookalikes() {
        assertThat(suspiciousUrlService.findTextReasons("Verify your wallet")).containsExactly(LURE);
        assertThat(suspiciousUrlService.findTextReasons("v" + CYRILLIC_IE + "rify")).containsExactly(MIXES_LATIN_AND_CYRILLIC, LURE);
        assertThat(suspiciousUrlService.findTextReasons("LOGIN HERE")).containsExactly(LURE);
        assertThat(suspiciousUrlService.findTextReasons("V" + codePoints(0x00E9) + "rification")).containsExactly(LURE);
    }

    @Test
    void wordsThatOnlyContainALureAreNotLures() {
        assertThat(suspiciousUrlService.findTextReasons("Unverified photos, securely blogged")).isEmpty();
    }

    @Test
    void aJoinerInsideAWordDoesNotHideIt() {
        assertThat(suspiciousUrlService.findTextReasons("Ver" + ZERO_WIDTH_JOINER + "ify now")).containsExactly(LURE);
        assertThat(suspiciousUrlService.findTextReasons("log" + ZERO_WIDTH_NON_JOINER + "in")).containsExactly(LURE);
        assertThat(suspiciousUrlService.findImitatedBrand("Pay" + ZERO_WIDTH_JOINER + "Pal")).contains("paypal");
        assertThat(suspiciousUrlService.findNamedBrand("Pay" + ZERO_WIDTH_JOINER + "Pal")).contains("paypal");
    }

    @Test
    void brandsAreFoundThroughLookalikes() {
        assertThat(suspiciousUrlService.findImitatedBrand("PayPal Help")).contains("paypal");
        assertThat(suspiciousUrlService.findImitatedBrand("paypa1")).contains("paypal");
        assertThat(suspiciousUrlService.findImitatedBrand("revoiut")).contains("revolut");
    }

    @ParameterizedTest
    @ValueSource(strings = {"paypal", "paypa1", "revoiut", "revolutt", "dh1", "dhl", "netflixx", "amazon", "booking", "ana"})
    void aWordImitatesTheBrandItsHostWouldImitate(final String word) {
        final var asHost = suspiciousUrlService.findReasons("https://" + word + ".example.org/").stream()
                .filter(reason -> reason.startsWith(SuspiciousUrlService.IMITATES_PREFIX))
                .map(reason -> reason.substring(SuspiciousUrlService.IMITATES_PREFIX.length()))
                .findFirst();

        assertThat(suspiciousUrlService.findImitatedBrand(word)).isEqualTo(asHost);
    }

    @Test
    void brandsAreFoundInEveryWordAndHyphenPart() {
        assertThat(suspiciousUrlService.findImitatedBrand("paypal-help")).contains("paypal");
        assertThat(suspiciousUrlService.findImitatedBrand("official_netflix_shop")).contains("netflix");
        assertThat(suspiciousUrlService.findImitatedBrand("Ana from Booking")).contains("booking");
    }

    @Test
    void brandsAreFoundThroughOtherAlphabetsAndAccents() {
        assertThat(suspiciousUrlService.findImitatedBrand(CYRILLIC_CAPITAL_A + "pple Support")).contains("apple");
        assertThat(suspiciousUrlService.findImitatedBrand("P" + CYRILLIC_A + "yPal")).contains("paypal");
        assertThat(suspiciousUrlService.findImitatedBrand(codePoints(0x00C1) + "pple")).contains("apple");
        assertThat(suspiciousUrlService.findImitatedBrand("DH" + codePoints(0x0141))).contains("dhl");
    }

    @Test
    void shortBrandsNeedAnExactMatch() {
        assertThat(suspiciousUrlService.findImitatedBrand("dh1 olxx ebayy")).isEmpty();
        assertThat(suspiciousUrlService.findImitatedBrand("eBay finds")).contains("ebay");
    }

    @ParameterizedTest
    @CsvSource({
            "paypalhelp, paypal",
            "paypal2024, paypal",
            "thepaypal, paypal",
            "pay-pal, paypal",
            "my_netflix_shop, netflix",
            "netflixx, netflix",
            "revolutt, revolut",
            "real-instagram4you, instagram",
            "freeamazongifts, amazon"
    })
    void aHandleThatContainsABrandOfSixOrMoreLettersImitatesIt(final String handle, final String brand) {
        assertThat(suspiciousUrlService.findBrandInHandle(handle)).contains(brand);
    }

    @ParameterizedTest
    @ValueSource(strings = {"applepie", "gmailer", "ebayfinds", "dhlexpress", "olxshop", "ana-horvat", "paypa"})
    void aHandleThatOnlyContainsAShorterBrandImitatesNothing(final String handle) {
        assertThat(suspiciousUrlService.findBrandInHandle(handle)).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"bookings", "positiveoutlook", "salonbooking", "my-outlookblog", "rebooking_now"})
    void aHandleThatOnlyContainsACommonWordBrandImitatesNothing(final String handle) {
        assertThat(suspiciousUrlService.findBrandInHandle(handle)).isEmpty();
    }

    @ParameterizedTest
    @CsvSource({"booking, booking", "outlook, outlook", "bookimg, booking", "0utlook, outlook", "booking-help, booking", "my_outlook, outlook"})
    void aHandleThatIsACommonWordBrandOrOneLetterFromItStillImitatesIt(final String handle, final String brand) {
        assertThat(suspiciousUrlService.findBrandInHandle(handle)).contains(brand);
        assertThat(suspiciousUrlService.findBrandInHandle(handle)).isEqualTo(suspiciousUrlService.findImitatedBrand(handle));
    }

    @ParameterizedTest
    @CsvSource({"paypal, paypal", "paypa1, paypal", "revoiut, revolut", "apple-pie, apple", "dhl, dhl", "the_ebay_shop, ebay"})
    void aHandleKeepsTheLinkChecksVerdictOnItsParts(final String handle, final String brand) {
        assertThat(suspiciousUrlService.findBrandInHandle(handle)).contains(brand);
        assertThat(suspiciousUrlService.findBrandInHandle(handle)).isEqualTo(suspiciousUrlService.findImitatedBrand(handle));
    }

    @ParameterizedTest
    @NullAndEmptySource
    void aMissingHandleImitatesNothing(final String handle) {
        assertThat(suspiciousUrlService.findBrandInHandle(handle)).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"Cooking with Ana", "Looking good", "Finance tips", "Minted Studio", "Revolution", "paypa1", "revoiut", "netflixx"})
    void aNamedBrandIsOnlyAnExactName(final String text) {
        assertThat(suspiciousUrlService.findNamedBrand(text)).isEmpty();
    }

    @Test
    void namedBrandsAreFoundThroughOtherAlphabetsAndAccents() {
        assertThat(suspiciousUrlService.findNamedBrand("PayPal")).contains("paypal");
        assertThat(suspiciousUrlService.findNamedBrand("p" + CYRILLIC_A + "ypal")).contains("paypal");
        assertThat(suspiciousUrlService.findNamedBrand(GREEK_CAPITAL_RHO + "ayPal")).contains("paypal");
        assertThat(suspiciousUrlService.findNamedBrand(CYRILLIC_CAPITAL_A + "pple Support")).contains("apple");
        assertThat(suspiciousUrlService.findNamedBrand("DH" + codePoints(0x0141))).contains("dhl");
        assertThat(suspiciousUrlService.findNamedBrand("Ana from Booking")).contains("booking");
        assertThat(suspiciousUrlService.findNamedBrand("official_netflix_shop")).contains("netflix");
    }

    private static Stream<String> plainTexts() {
        return Stream.of(
                "Ana Horvat",
                codePoints(0x0424, 0x043E, 0x0442, 0x043E, 0x20, 0x0438, 0x0437, 0x20, 0x0417, 0x0430, 0x0433, 0x0440, 0x0435, 0x0431, 0x0430),
                codePoints(0x010D) + "okolada i kava",
                codePoints(0x65E5, 0x672C, 0x306E, 0x5199, 0x771F),
                "I " + codePoints(0x2764, 0xFE0F) + " cats",
                codePoints(0x0391, 0x03B8, 0x03AE, 0x03BD, 0x03B1) + " 2026",
                codePoints(0x0645, 0x06CC, 0x200C, 0x062E, 0x0648, 0x0627, 0x0647, 0x0645),
                "Photos from Zagreb, 2026 - say hi!");
    }

    private static String codePoints(final int... codePoints) {
        return new String(codePoints, 0, codePoints.length);
    }

}
