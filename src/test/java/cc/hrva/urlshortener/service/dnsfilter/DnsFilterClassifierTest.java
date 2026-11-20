package cc.hrva.urlshortener.service.dnsfilter;

import static org.assertj.core.api.Assertions.assertThat;

import cc.hrva.urlshortener.configuration.properties.AppProperties;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class DnsFilterClassifierTest {

    private static final String SINKHOLE = "0.0.0.0";
    private static final String GTLD_SOA = "a.gtld-servers.net";
    private static final String CLEANBROWSING_RPZ_SOA = "cleanbrowsing.rpz.noc.org";
    private static final DnsReply QUAD9_BLOCK = nxdomain(0, null);
    private static final DnsReply CLEANBROWSING_BLOCK = nxdomain(1, CLEANBROWSING_RPZ_SOA);
    private static final DnsReply NONEXISTENT = nxdomain(1, GTLD_SOA);
    private static final List<Optional<DnsReply>> REFERENCES = List.of(Optional.of(reply("208.94.116.246")), Optional.of(reply("208.94.116.246")));
    private static final List<Optional<DnsReply>> NO_REFERENCE_ADDRESS = List.of(Optional.of(NONEXISTENT), Optional.of(NONEXISTENT));

    private static AppProperties.DnsFilters.Filter quad9() {
        final var filter = filter(DnsFilterProvider.QUAD9);
        filter.setNxdomainWithoutAuthority(true);
        return filter;
    }

    private static AppProperties.DnsFilters.Filter cleanBrowsing() {
        final var filter = filter(DnsFilterProvider.CLEANBROWSING);
        filter.setNxdomainAuthoritySuffix("rpz.noc.org");
        return filter;
    }

    private static AppProperties.DnsFilters.Filter cloudflare() {
        return filter(DnsFilterProvider.CLOUDFLARE, SINKHOLE);
    }

    private static AppProperties.DnsFilters.Filter controlD() {
        return filter(DnsFilterProvider.CONTROL_D, SINKHOLE);
    }

    private static AppProperties.DnsFilters.Filter filter(final DnsFilterProvider provider, final String... blockAnswers) {
        final var filter = new AppProperties.DnsFilters.Filter();
        filter.setProvider(provider);
        filter.setAddress("192.0.2.1");
        filter.setBlockAnswers(List.of(blockAnswers));
        return filter;
    }

    private static DnsReply reply(final String... addresses) {
        return new DnsReply(0, List.of(addresses), 0, null);
    }

    private static DnsReply nxdomain(final int authorityCount, final String authorityMname) {
        return new DnsReply(3, List.of(), authorityCount, authorityMname);
    }

    @Test
    void quad9NxdomainWithoutAuthorityIsABlock() {
        final var answer = DnsFilterClassifier.classify(quad9(), Optional.of(QUAD9_BLOCK), REFERENCES);

        assertThat(answer).isEqualTo(new DnsFilterAnswer(DnsFilterProvider.QUAD9, DnsFilterStatus.BLOCKED, "NXDOMAIN"));
    }

    @Test
    void quad9NxdomainWithAnAuthorityRecordIsNotABlock() {
        final var answer = DnsFilterClassifier.classify(quad9(), Optional.of(NONEXISTENT), REFERENCES);

        assertThat(answer).isEqualTo(new DnsFilterAnswer(DnsFilterProvider.QUAD9, DnsFilterStatus.NOT_BLOCKED, "NXDOMAIN"));
    }

    @Test
    void cleanBrowsingRpzSoaIsABlock() {
        final var answer = DnsFilterClassifier.classify(cleanBrowsing(), Optional.of(CLEANBROWSING_BLOCK), REFERENCES);

        assertThat(answer).isEqualTo(new DnsFilterAnswer(DnsFilterProvider.CLEANBROWSING, DnsFilterStatus.BLOCKED, "NXDOMAIN"));
    }

    @Test
    void cleanBrowsingNxdomainWithAnOrdinarySoaIsNotABlock() {
        final var answer = DnsFilterClassifier.classify(cleanBrowsing(), Optional.of(NONEXISTENT), REFERENCES);

        assertThat(answer).isEqualTo(new DnsFilterAnswer(DnsFilterProvider.CLEANBROWSING, DnsFilterStatus.NOT_BLOCKED, "NXDOMAIN"));
    }

    @Test
    void theAuthoritySuffixMatchesWholeLabelsInAnyCase() {
        assertThat(DnsFilterClassifier.classify(cleanBrowsing(), Optional.of(nxdomain(1, "CleanBrowsing.RPZ.noc.org")), REFERENCES).status())
                .isEqualTo(DnsFilterStatus.BLOCKED);
        assertThat(DnsFilterClassifier.classify(cleanBrowsing(), Optional.of(nxdomain(1, "rpz.noc.org")), REFERENCES).status())
                .isEqualTo(DnsFilterStatus.BLOCKED);
        assertThat(DnsFilterClassifier.classify(cleanBrowsing(), Optional.of(nxdomain(1, "notrpz.noc.org")), REFERENCES).status())
                .isEqualTo(DnsFilterStatus.NOT_BLOCKED);
        assertThat(DnsFilterClassifier.classify(cleanBrowsing(), Optional.of(QUAD9_BLOCK), REFERENCES).status())
                .isEqualTo(DnsFilterStatus.NOT_BLOCKED);
    }

    @Test
    void nxdomainFromAFilterWithoutThatSignatureIsNotABlock() {
        final var answer = DnsFilterClassifier.classify(cloudflare(), Optional.of(QUAD9_BLOCK), REFERENCES);

        assertThat(answer).isEqualTo(new DnsFilterAnswer(DnsFilterProvider.CLOUDFLARE, DnsFilterStatus.NOT_BLOCKED, "NXDOMAIN"));
    }

    @Test
    void anEmptyNoErrorAnswerIsNotABlock() {
        final var answer = DnsFilterClassifier.classify(quad9(), Optional.of(reply()), REFERENCES);

        assertThat(answer).isEqualTo(new DnsFilterAnswer(DnsFilterProvider.QUAD9, DnsFilterStatus.NOT_BLOCKED, "no address"));
    }

    @Test
    void cloudflareSinkholeIsABlock() {
        final var answer = DnsFilterClassifier.classify(cloudflare(), Optional.of(reply(SINKHOLE)), REFERENCES);

        assertThat(answer).isEqualTo(new DnsFilterAnswer(DnsFilterProvider.CLOUDFLARE, DnsFilterStatus.BLOCKED, SINKHOLE));
    }

    @Test
    void aSinkholeAddressTheReferencesAlsoReturnIsNotABlock() {
        final var references = List.of(Optional.of(reply(SINKHOLE)), Optional.<DnsReply>empty());

        final var answer = DnsFilterClassifier.classify(cloudflare(), Optional.of(reply(SINKHOLE)), references);

        assertThat(answer).isEqualTo(new DnsFilterAnswer(DnsFilterProvider.CLOUDFLARE, DnsFilterStatus.NOT_BLOCKED, SINKHOLE));
    }

    @Test
    void aBlockAnswerFromAReferenceIsNotExistence() {
        assertThat(DnsFilterClassifier.exists(List.of(Optional.of(reply(SINKHOLE)), Optional.of(NONEXISTENT)), List.of(SINKHOLE))).isFalse();
        assertThat(DnsFilterClassifier.exists(List.of(Optional.of(reply(SINKHOLE, "1.2.3.4"))), List.of(SINKHOLE))).isTrue();
    }

    @Test
    void aDifferentRealAddressIsNotABlock() {
        final var answer = DnsFilterClassifier.classify(controlD(), Optional.of(reply("188.114.96.8")), List.of(Optional.of(reply("104.21.0.118"))));

        assertThat(answer).isEqualTo(new DnsFilterAnswer(DnsFilterProvider.CONTROL_D, DnsFilterStatus.NOT_BLOCKED, "188.114.96.8"));
    }

    @Test
    void servfailIsNoAnswer() {
        final var answer = DnsFilterClassifier.classify(quad9(), Optional.of(new DnsReply(2, List.of(), 0, null)), REFERENCES);

        assertThat(answer).isEqualTo(new DnsFilterAnswer(DnsFilterProvider.QUAD9, DnsFilterStatus.NO_ANSWER, "SERVFAIL"));
    }

    @Test
    void refusedIsNoAnswer() {
        final var answer = DnsFilterClassifier.classify(quad9(), Optional.of(new DnsReply(5, List.of(), 0, null)), REFERENCES);

        assertThat(answer).isEqualTo(new DnsFilterAnswer(DnsFilterProvider.QUAD9, DnsFilterStatus.NO_ANSWER, "REFUSED"));
    }

    @Test
    void aMissingDomainIsNeverBlocked() {
        final var answers = List.of(
                DnsFilterClassifier.classify(quad9(), Optional.of(NONEXISTENT), NO_REFERENCE_ADDRESS),
                DnsFilterClassifier.classify(cloudflare(), Optional.of(NONEXISTENT), NO_REFERENCE_ADDRESS),
                DnsFilterClassifier.classify(cleanBrowsing(), Optional.of(NONEXISTENT), NO_REFERENCE_ADDRESS),
                DnsFilterClassifier.classify(controlD(), Optional.of(NONEXISTENT), NO_REFERENCE_ADDRESS));

        assertThat(DnsFilterClassifier.exists(NO_REFERENCE_ADDRESS, List.of(SINKHOLE))).isFalse();
        assertThat(answers).extracting(DnsFilterAnswer::status).containsOnly(DnsFilterStatus.NOT_BLOCKED);
    }

    @Test
    void noReplyIsNoAnswer() {
        final var answer = DnsFilterClassifier.classify(quad9(), Optional.empty(), REFERENCES);

        assertThat(answer).isEqualTo(new DnsFilterAnswer(DnsFilterProvider.QUAD9, DnsFilterStatus.NO_ANSWER, "no answer"));
    }

    @Test
    void existsNeedsOneReferenceWithAnAddress() {
        assertThat(DnsFilterClassifier.exists(List.of(Optional.empty(), Optional.of(reply("1.2.3.4"))), List.of(SINKHOLE))).isTrue();
        assertThat(DnsFilterClassifier.exists(List.of(Optional.of(NONEXISTENT), Optional.empty()), List.of(SINKHOLE))).isFalse();
    }

    @Test
    void poshmarkOf2026_11_19() {
        final var references = List.of(Optional.of(reply("104.21.0.118")), Optional.of(reply("104.21.0.118")));
        final var answers = List.of(
                DnsFilterClassifier.classify(quad9(), Optional.of(QUAD9_BLOCK), references),
                DnsFilterClassifier.classify(cloudflare(), Optional.of(reply(SINKHOLE)), references),
                DnsFilterClassifier.classify(cleanBrowsing(), Optional.of(reply("188.114.97.11")), references),
                DnsFilterClassifier.classify(controlD(), Optional.of(reply("188.114.96.8")), references));

        final var report = new DnsFilterReport("poshmark.id653655.shop", true, answers);

        assertThat(report.blocking()).containsExactly(DnsFilterProvider.QUAD9, DnsFilterProvider.CLOUDFLARE);
        assertThat(report.anyUnanswered()).isFalse();
    }

}
