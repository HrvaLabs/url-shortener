package cc.hrva.urlshortener.service.linksignal;

import cc.hrva.urlshortener.util.DomainNames;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.core.io.ClassPathResource;

import static org.assertj.core.api.Assertions.assertThat;

class RandomLookingLabelsTest {

    private static final RandomLookingLabels LABELS = new RandomLookingLabels();
    private static final List<String> TRAINING = lines("moderation/common-domain-labels.txt");
    private static final List<String> CAMPAIGN_DOMAINS = lines("moderation/campaign-domains.txt");
    private static final List<String> REAL_DOMAINS = lines("moderation/real-domains.txt");

    @Test
    void atLeast30Of39CampaignDomainsLookRandom() {
        final var flagged = CAMPAIGN_DOMAINS.stream().filter(LABELS::looksRandom).toList();

        assertThat(CAMPAIGN_DOMAINS).hasSize(39).doesNotContain("bgp37.sbs");
        assertThat(flagged).hasSizeGreaterThanOrEqualTo(30);
        assertThat(flagged).hasSize(33);
    }

    @Test
    void atMostOnePercentOfRealDomainsLookRandom() {
        final var falsePositives = REAL_DOMAINS.stream().filter(LABELS::looksRandom).toList();

        assertThat(REAL_DOMAINS).hasSizeGreaterThanOrEqualTo(500).doesNotHaveDuplicates();
        assertThat(falsePositives).as("false positives %s", falsePositives).hasSizeLessThanOrEqualTo(REAL_DOMAINS.size() / 100);
        assertThat(falsePositives).containsExactlyInAnyOrder("xiaomi.com", "airbnb.com");
    }

    @Test
    void theRealDomainsHoldAtLeast200FirstLabelsTheModelNeverSaw() {
        final var training = Set.copyOf(TRAINING);
        final var unseen = REAL_DOMAINS.stream()
                .map(domain -> DomainNames.registrableDomain(domain).split("\\.")[0])
                .filter(label -> !training.contains(label))
                .collect(Collectors.toSet());

        assertThat(unseen).hasSizeGreaterThanOrEqualTo(200);
    }

    @Test
    void theModelIsTrainedOnAtLeast2000RealLabels() {
        assertThat(TRAINING).hasSizeGreaterThanOrEqualTo(2000).doesNotHaveDuplicates().allMatch(label -> label.matches("[a-z0-9-]+"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"ibm", "dhl", "bgp37", "xqzvk"})
    void labelsUnder6CharactersAreNeverScored(final String label) {
        assertThat(LABELS.score(label)).isEmpty();
        assertThat(LABELS.looksRandom(label + ".com")).isFalse();
        assertThat(LABELS.looksRandom(label + ".example.com")).isFalse();
    }

    @Test
    void bgp37IsNotFlagged() {
        assertThat(LABELS.looksRandom("bgp37.sbs")).isFalse();
    }

    @Test
    void punycodeIsNotScored() {
        assertThat(LABELS.score("xn--qxqzvbtwk")).isEmpty();
        assertThat(LABELS.looksRandom("xn--qxqzvbtwk.com")).isFalse();
        assertThat(LABELS.looksRandom("xn--qxqzvbtwk.example.com")).isFalse();
    }

    @Test
    void aLabelWithOtherCharactersIsNotScored() {
        assertThat(LABELS.score("xdv_qnxj")).isEmpty();
        assertThat(LABELS.looksRandom("xdv_qnxj.example.com")).isFalse();
    }

    @Test
    void theLeftmostSubdomainCounts() {
        assertThat(LABELS.looksRandom("xdvqnxj.example.com")).isTrue();
        assertThat(LABELS.looksRandom("ll.xdvqnxj.com")).isTrue();
        assertThat(LABELS.looksRandom("www.example.com")).isFalse();
        assertThat(LABELS.looksRandom("www.xdvqnxj.example.com")).isFalse();
        assertThat(LABELS.looksRandom("xdvqnxj.github.io")).isTrue();
    }

    @Test
    void aRandomLabelScoresBelowTheThresholdAndARealOneAbove() {
        assertThat(LABELS.score("xdvqnxj").orElseThrow()).isLessThan(RandomLookingLabels.THRESHOLD);
        assertThat(LABELS.score("jutarnji").orElseThrow()).isGreaterThan(RandomLookingLabels.THRESHOLD);
        assertThat(LABELS.score("google").orElseThrow()).isGreaterThan(RandomLookingLabels.THRESHOLD);
    }

    @Test
    void digitsAreOneClass() {
        assertThat(LABELS.score("web123")).isEqualTo(LABELS.score("web987"));
    }

    @Test
    void theHostIsReadInAnyCaseAndWithATrailingDot() {
        assertThat(LABELS.looksRandom("XDVQNXJ.com.")).isTrue();
        assertThat(LABELS.looksRandom("Google.com.")).isFalse();
    }

    @Test
    void nothingToScoreIsNotRandom() {
        assertThat(LABELS.looksRandom(null)).isFalse();
        assertThat(LABELS.looksRandom("")).isFalse();
        assertThat(LABELS.looksRandom("203.0.113.7")).isFalse();
        assertThat(LABELS.looksRandom("2001:db8::1")).isFalse();
    }

    private static List<String> lines(final String path) {
        try {
            return new ClassPathResource(path).getContentAsString(StandardCharsets.UTF_8).lines()
                    .map(String::strip)
                    .filter(line -> !line.isEmpty() && !line.startsWith("#"))
                    .toList();
        } catch (final IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

}
