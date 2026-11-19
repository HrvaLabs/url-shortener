package cc.hrva.urlshortener.service.dnsfilter;

import static org.assertj.core.api.Assertions.assertThat;

import cc.hrva.urlshortener.configuration.properties.AppProperties;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class DnsFilterClassifierTest {

    private static final DnsReply NXDOMAIN = new DnsReply(3, List.of());

    private static AppProperties.DnsFilters.Filter filter(final DnsFilterProvider provider, final String... blockAnswers) {
        final var filter = new AppProperties.DnsFilters.Filter();
        filter.setProvider(provider);
        filter.setAddress("192.0.2.1");
        filter.setBlockAnswers(List.of(blockAnswers));
        return filter;
    }

    private static DnsReply reply(final String address) {
        return new DnsReply(0, List.of(address));
    }

    @Test
    void quad9NxdomainWhileTheReferenceResolvesIsABlock() {
        final var answer = DnsFilterClassifier.classify(filter(DnsFilterProvider.QUAD9), Optional.of(NXDOMAIN), true);

        assertThat(answer).isEqualTo(new DnsFilterAnswer(DnsFilterProvider.QUAD9, DnsFilterStatus.BLOCKED, "NXDOMAIN"));
    }

    @Test
    void cloudflareSinkholeIsABlock() {
        final var answer = DnsFilterClassifier.classify(filter(DnsFilterProvider.CLOUDFLARE, "0.0.0.0"),
                Optional.of(reply("0.0.0.0")), true);

        assertThat(answer).isEqualTo(new DnsFilterAnswer(DnsFilterProvider.CLOUDFLARE, DnsFilterStatus.BLOCKED, "0.0.0.0"));
    }

    @Test
    void aDifferentRealAddressIsNotABlock() {
        final var answer = DnsFilterClassifier.classify(filter(DnsFilterProvider.CONTROL_D, "0.0.0.0"),
                Optional.of(reply("188.114.96.8")), true);

        assertThat(answer).isEqualTo(new DnsFilterAnswer(DnsFilterProvider.CONTROL_D, DnsFilterStatus.NOT_BLOCKED, "188.114.96.8"));
    }

    @Test
    void anEmptyNoErrorAnswerWhileTheReferenceResolvesIsABlock() {
        final var answer = DnsFilterClassifier.classify(filter(DnsFilterProvider.QUAD9),
                Optional.of(new DnsReply(0, List.of())), true);

        assertThat(answer).isEqualTo(new DnsFilterAnswer(DnsFilterProvider.QUAD9, DnsFilterStatus.BLOCKED, "no address"));
    }

    @Test
    void aMissingDomainIsNeverBlocked() {
        final var answer = DnsFilterClassifier.classify(filter(DnsFilterProvider.QUAD9), Optional.of(NXDOMAIN), false);

        assertThat(answer.status()).isEqualTo(DnsFilterStatus.NOT_BLOCKED);
    }

    @Test
    void noReplyIsNoAnswer() {
        final var answer = DnsFilterClassifier.classify(filter(DnsFilterProvider.QUAD9), Optional.empty(), true);

        assertThat(answer).isEqualTo(new DnsFilterAnswer(DnsFilterProvider.QUAD9, DnsFilterStatus.NO_ANSWER, "no answer"));
    }

    @Test
    void existsNeedsOneReferenceWithAnAddress() {
        assertThat(DnsFilterClassifier.exists(List.of(Optional.empty(), Optional.of(reply("1.2.3.4"))))).isTrue();
        assertThat(DnsFilterClassifier.exists(List.of(Optional.of(NXDOMAIN), Optional.empty()))).isFalse();
    }

    @Test
    void poshmarkOf2026_11_19() {
        final var answers = List.of(
                DnsFilterClassifier.classify(filter(DnsFilterProvider.QUAD9), Optional.of(NXDOMAIN), true),
                DnsFilterClassifier.classify(filter(DnsFilterProvider.CLOUDFLARE, "0.0.0.0"), Optional.of(reply("0.0.0.0")), true),
                DnsFilterClassifier.classify(filter(DnsFilterProvider.CLEANBROWSING), Optional.of(reply("188.114.97.11")), true),
                DnsFilterClassifier.classify(filter(DnsFilterProvider.CONTROL_D, "0.0.0.0"), Optional.of(reply("188.114.96.8")), true));

        final var report = new DnsFilterReport("poshmark.com", true, answers);

        assertThat(report.blocking()).containsExactly(DnsFilterProvider.QUAD9, DnsFilterProvider.CLOUDFLARE);
        assertThat(report.anyUnanswered()).isFalse();
    }

}
