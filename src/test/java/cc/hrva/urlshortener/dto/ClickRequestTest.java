package cc.hrva.urlshortener.dto;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ClickRequestTest {

    private final ClickRequest click = ClickRequest.of("203.0.113.7", "Mozilla/5.0 secret-ua", "https://t.co/private-path", "HR");

    @Test
    void toStringRevealsNothingAboutTheVisitor() {
        assertThat(click.toString()).doesNotContain("203.0.113.7", "secret-ua", "private-path");
    }

    @Test
    void ofKnowsTheReferrer() {
        assertThat(click.referrerKnown()).isTrue();
    }

    @Test
    void unknownReferrerKeepsEverythingButTheReferer() {
        assertThat(click.withUnknownReferrer())
                .isEqualTo(new ClickRequest("203.0.113.7", "Mozilla/5.0 secret-ua", null, "HR", false, false, false, null));
    }

    @Test
    void theQueryIsKeptThroughEveryChangeAndNeverPrinted() {
        final var forwarded = click.withQuery("utm_source=private-campaign").withQrScan().withUnknownReferrer().asHead();

        assertThat(forwarded.query()).isEqualTo("utm_source=private-campaign");
        assertThat(forwarded.toString()).doesNotContain("private-campaign");
    }

    @Test
    void aClickIsCountedUnlessItIsAHeadRequest() {
        assertThat(click.head()).isFalse();
        assertThat(click.asHead().head()).isTrue();
        assertThat(click.asHead().withQrScan().withUnknownReferrer().head()).isTrue();
    }

    @Test
    void anUnknownReferrerKeepsTheQrScan() {
        final var unlocked = click.withQrScan().withUnknownReferrer();

        assertThat(unlocked.qrScan()).isTrue();
        assertThat(unlocked.referrerKnown()).isFalse();
    }

}
