package cc.hrva.urlshortener.service.bio;

import cc.hrva.urlshortener.configuration.properties.AppProperties;
import cc.hrva.urlshortener.service.impl.DefaultSuspiciousUrlService;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class BioPageTextSignalsTest {

    private static final String CYRILLIC_A = codePoints(0x0430);
    private static final String CYRILLIC_IE = codePoints(0x0435);

    private BioPageTextSignals bioPageTextSignals;

    @BeforeEach
    void setUp() {
        final var appProperties = new AppProperties();
        appProperties.setServerUrl("https://hrva.cc");
        bioPageTextSignals = new BioPageTextSignals(new DefaultSuspiciousUrlService(appProperties));
    }

    @Test
    void cleanTextHasNoReasons() {
        assertThat(bioPageTextSignals.reasonsFor("ana", "Ana Horvat", "Photos from Zagreb", List.of("My blog", "Shop"))).isEmpty();
    }

    @Test
    void brandsOnlyCountInTheHandleAndName() {
        assertThat(bioPageTextSignals.reasonsFor("paypal-help", "Ana", "", List.of())).containsExactly("handle imitates paypal");
        assertThat(bioPageTextSignals.reasonsFor("ana", "PayPal Support", "", List.of())).containsExactly("name imitates paypal");
        assertThat(bioPageTextSignals.reasonsFor("ana", "Ana", "Follow me on Instagram", List.of("Instagram", "Netflix picks"))).isEmpty();
    }

    @Test
    void everyFieldIsLabelled() {
        assertThat(bioPageTextSignals.reasonsFor("ana", "Ana", "Verify your wallet here", List.of()))
                .containsExactly("bio has a login or verification lure");
        assertThat(bioPageTextSignals.reasonsFor("ana", "Login help", "", List.of()))
                .containsExactly("name has a login or verification lure");
        assertThat(bioPageTextSignals.reasonsFor("ana", "An" + CYRILLIC_A, "", List.of()))
                .containsExactly("name mixes Latin and Cyrillic letters");
        assertThat(bioPageTextSignals.reasonsFor("ana", "Ana", "", List.of("Unlock the gift")))
                .containsExactly("a button title has a login or verification lure");
    }

    @Test
    void buttonTitleReasonsAppearOnce() {
        assertThat(bioPageTextSignals.reasonsFor("ana", "Ana", "", List.of("Sh" + CYRILLIC_IE + "p", "Bl" + CYRILLIC_A + "g", "Plain")))
                .containsExactly("a button title mixes Latin and Cyrillic letters");
    }

    @Test
    void reasonsComeInTheirOrder() {
        final var reasons = bioPageTextSignals.reasonsFor(
                "revoiut",
                "P" + CYRILLIC_A + "yPal secure",
                "v" + CYRILLIC_IE + "rify",
                List.of("Login", "B" + CYRILLIC_A + "nk"));

        assertThat(reasons).containsExactly(
                "handle imitates revolut",
                "name imitates paypal",
                "name mixes Latin and Cyrillic letters",
                "name has a login or verification lure",
                "bio mixes Latin and Cyrillic letters",
                "bio has a login or verification lure",
                "a button title has a login or verification lure",
                "a button title mixes Latin and Cyrillic letters");
    }

    @Test
    void aPageWithoutAHandleIsCheckedOnItsText() {
        assertThat(bioPageTextSignals.reasonsFor(null, "Netflix Help", "", List.of())).containsExactly("name imitates netflix");
    }

    private static String codePoints(final int... codePoints) {
        return new String(codePoints, 0, codePoints.length);
    }

}
