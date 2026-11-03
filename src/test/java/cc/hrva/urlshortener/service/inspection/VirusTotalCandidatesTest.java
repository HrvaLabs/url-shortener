package cc.hrva.urlshortener.service.inspection;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class VirusTotalCandidatesTest {

    @Test
    void aUrlWithAPathIsLookedUpAsStored() {
        assertThat(VirusTotalCandidates.of("https://findfavoritedate.life/?u=f6yp608", null))
                .containsExactly("https://findfavoritedate.life/?u=f6yp608");
    }

    @Test
    void aUrlWithAnEmptyPathAlsoGetsTheSlashVariant() {
        assertThat(VirusTotalCandidates.of("https://utn.pl", null)).containsExactly("https://utn.pl", "https://utn.pl/");
        assertThat(VirusTotalCandidates.of("https://njuskaio.cam?ref=1#top", null)).containsExactly("https://njuskaio.cam?ref=1#top",
                "https://njuskaio.cam/?ref=1#top");
        assertThat(VirusTotalCandidates.of("http://user@njuskaio.cam:8080", null)).containsExactly("http://user@njuskaio.cam:8080",
                "http://user@njuskaio.cam:8080/");
    }

    @Test
    void theFinalUrlComesAfterTheLongUrlAndItsVariant() {
        assertThat(VirusTotalCandidates.of("https://utn.pl", "https://sso.findfavoritedate.life"))
                .containsExactly("https://utn.pl", "https://utn.pl/", "https://sso.findfavoritedate.life", "https://sso.findfavoritedate.life/");
        assertThat(VirusTotalCandidates.of("https://utn.pl/x", "https://sso.findfavoritedate.life/login"))
                .containsExactly("https://utn.pl/x", "https://sso.findfavoritedate.life/login");
    }

    @Test
    void noCandidateIsLookedUpTwice() {
        assertThat(VirusTotalCandidates.of("https://utn.pl/x", "https://utn.pl/x")).containsExactly("https://utn.pl/x");
        assertThat(VirusTotalCandidates.of("https://utn.pl", "https://utn.pl/")).containsExactly("https://utn.pl", "https://utn.pl/");
        assertThat(VirusTotalCandidates.of("https://utn.pl/x", " ")).containsExactly("https://utn.pl/x");
    }

    @Test
    void aUrlThatDoesNotParseGetsNoVariant() {
        assertThat(VirusTotalCandidates.of("https://bad host", null)).containsExactly("https://bad host");
        assertThat(VirusTotalCandidates.of("mailto:ana@example.com", null)).containsExactly("mailto:ana@example.com");
    }

}
