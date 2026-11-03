package cc.hrva.urlshortener.util;

import cc.hrva.urlshortener.client.RedirectHop;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RedirectChainsTest {

    @Test
    void joinAndSplitRoundTrip() {
        final var hops = List.of(
                new RedirectHop("https://utn.pl/beata4", "https://utn.pl/beata4"),
                new RedirectHop("https://radarkobiet.pl/link/1", "https://radarkobiet.pl/link/1"),
                new RedirectHop("https://banghiswife.com/landing2", "https://banghiswife.com/landing2"));

        final var stored = RedirectChains.join(hops);

        assertThat(stored).isEqualTo("https://utn.pl/beata4\nhttps://radarkobiet.pl/link/1\nhttps://banghiswife.com/landing2");
        assertThat(RedirectChains.split(stored)).containsExactly("https://utn.pl/beata4", "https://radarkobiet.pl/link/1",
                "https://banghiswife.com/landing2");
    }

    @Test
    void theOriginalFormOfAHopIsKeptNotTheNormalizedOne() {
        final var hops = List.of(new RedirectHop("https://čevapi.example/menu", "https://xn--evapi-gya.example/menu"));

        assertThat(RedirectChains.join(hops)).isEqualTo("https://čevapi.example/menu");
    }

    @Test
    void aLongHopIsKeptWhole() {
        final var longHop = "https://shop.example/" + "b".repeat(3000);
        final var hops = List.of(new RedirectHop("https://a.example/", "https://a.example/"), new RedirectHop(longHop, longHop));

        final var stored = RedirectChains.join(hops);

        assertThat(stored).hasSize("https://a.example/".length() + 1 + longHop.length());
        assertThat(RedirectChains.split(stored)).containsExactly("https://a.example/", longHop);
    }

    @Test
    void nothingStoredSplitsToNoHops() {
        assertThat(RedirectChains.split(null)).isEmpty();
        assertThat(RedirectChains.split("")).isEmpty();
        assertThat(RedirectChains.split("  \n ")).isEmpty();
    }

    @Test
    void noHopsJoinToAnEmptyChain() {
        assertThat(RedirectChains.join(List.of())).isEmpty();
    }

}
