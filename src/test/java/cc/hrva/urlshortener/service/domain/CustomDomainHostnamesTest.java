package cc.hrva.urlshortener.service.domain;

import cc.hrva.urlshortener.configuration.properties.AppProperties;
import cc.hrva.urlshortener.exception.FieldValidationException;
import cc.hrva.urlshortener.util.DomainNames;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

class CustomDomainHostnamesTest {

    private static final String BLANK = "Enter a domain, like go.example.com";
    private static final String TOO_LONG = "A domain can be 253 characters at most.";
    private static final String BAD_SHAPE = "Enter a domain like go.example.com: letters, digits and hyphens, with dots between the parts.";
    private static final String APEX = "Use a subdomain, like go.example.com. Root domains like example.com aren't supported yet.";
    private static final String NOT_OWNABLE = "Enter a domain you own, like go.example.com.";
    private static final String RESERVED = "This domain can't be used on hrva.cc.";
    private static final String CYRILLIC_LOOK_ALIKE = "g\u043e.example.com";

    private AppProperties appProperties;
    private CustomDomainHostnames hostnames;

    @BeforeEach
    void setUp() {
        appProperties = new AppProperties();
        appProperties.setServerUrl("https://hrva.cc");
        hostnames = new CustomDomainHostnames(appProperties);
    }

    @Test
    void normalizesCaseTrailingDotAndSpaces() {
        assertThat(hostnames.normalize(" GO.Example.COM. ")).isEqualTo("go.example.com");
    }

    @Test
    void idnBecomesPunycode() {
        final var normalized = hostnames.normalize(CYRILLIC_LOOK_ALIKE);

        assertThat(normalized)
                .startsWith("xn--")
                .isEqualTo("xn--g-1tb.example.com")
                .isEqualTo(DomainNames.toAscii(CYRILLIC_LOOK_ALIKE));
    }

    @Test
    void punycodeInputIsKeptAsItIs() {
        assertThat(hostnames.normalize("XN--G-1TB.example.com")).isEqualTo("xn--g-1tb.example.com");
    }

    @ParameterizedTest
    @ValueSource(strings = {"example.com", "example.co.uk", "Example.COM.", "foo.github.io"})
    void apexIsRefused(final String input) {
        assertRefused(input, APEX);
    }

    @ParameterizedTest
    @ValueSource(strings = {"go.example.co.uk", "a.b.example.com", "go.foo.github.io"})
    void subdomainsPass(final String input) {
        assertThat(hostnames.normalize(input)).isEqualTo(input);
    }

    @ParameterizedTest
    @ValueSource(strings = {"192.168.1.1", "[::1]", "::1", "0x7f.1", "localhost", "x.localhost", "LOCALHOST.", "com", "co.uk", "github.io",
            "intranet", "go.example.invalid", "foo.bar.local"})
    void notOwnableIsRefused(final String input) {
        assertRefused(input, NOT_OWNABLE);
    }

    @ParameterizedTest
    @ValueSource(strings = {"*.example.com", "_dmarc.example.com", "-go.example.com", "go-.example.com", "go..example.com", ".example.com",
            "go.example.com..", "go example.com", "go.example.com/menu", "go.example.com:443", "user@go.example.com"})
    void badShapesAreRefused(final String input) {
        assertRefused(input, BAD_SHAPE);
    }

    @Test
    void aLabelOver63CharactersIsRefused() {
        assertRefused("a".repeat(64) + ".example.com", BAD_SHAPE);
        assertThat(hostnames.normalize("a".repeat(63) + ".example.com")).isEqualTo("a".repeat(63) + ".example.com");
    }

    @Test
    void tooLongIsRefused() {
        final var label = "a".repeat(61);
        final var exactly253 = String.join(".", label, label, label, label.substring(0, 55), "example.com");
        final var exactly254 = "b" + exactly253;

        assertThat(exactly253).hasSize(253);
        assertThat(exactly254).hasSize(254);
        assertThat(hostnames.normalize(exactly253)).isEqualTo(exactly253);
        assertRefused(exactly254, TOO_LONG);
    }

    @Test
    void theLengthCountsThePunycodeForm() {
        final var label = "a".repeat(61);
        final var unicode = String.join(".", label, label, label, "g\u043e" + "a".repeat(50), "example.com");

        assertThat(unicode).hasSizeLessThan(253);
        assertRefused(unicode, TOO_LONG);
    }

    @ParameterizedTest
    @ValueSource(strings = {"hrva.cc", "evil.hrva.cc", "a.b.hrvalabs.net", "HRVA.CC.", "go.HrvaLabs.net"})
    void reservedAndItsSubdomainsAreRefused(final String input) {
        assertRefused(input, RESERVED);
    }

    @ParameterizedTest
    @ValueSource(strings = {"hrva.cc.evil.com", "hrvalabs.net.example.org", "go.nothrva.cc", "go.karlo.cc"})
    void lookAlikesOfReservedNamesPass(final String input) {
        assertThat(hostnames.normalize(input)).isEqualTo(input);
    }

    @Test
    void aNameUnderAnUnlistedTopLevelDomainIsNotOwnableEvenWhenItStartsWithAReservedName() {
        assertThat(hostnames.isReserved("hrva.cc.evil.example")).isFalse();
        assertRefused("hrva.cc.evil.example", NOT_OWNABLE);
    }

    @Test
    void theReservedListComesFromTheSettings() {
        appProperties.getDomains().setReserved(List.of("Karlo.CC."));

        assertRefused("go.karlo.cc", RESERVED);
        assertThat(hostnames.normalize("evil.hrva.cc")).isEqualTo("evil.hrva.cc");
        assertThat(hostnames.isReserved("go.karlo.cc")).isTrue();
        assertThat(hostnames.isReserved("go.example.com")).isFalse();
    }

    @Test
    void isReservedMatchesTheNameAndItsSubdomainsOnly() {
        assertThat(hostnames.isReserved("hrva.cc")).isTrue();
        assertThat(hostnames.isReserved("a.b.hrva.cc")).isTrue();
        assertThat(hostnames.isReserved("HRVA.CC.")).isTrue();
        assertThat(hostnames.isReserved("hrva.cc.evil.example")).isFalse();
        assertThat(hostnames.isReserved("xhrva.cc")).isFalse();
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "\t", " . "})
    void blankIsRefused(final String input) {
        assertRefused(input, input != null && input.contains(".") ? BAD_SHAPE : BLANK);
    }

    @Test
    void primaryHostsIncludeTheServerUrlHost() {
        appProperties.getDomains().setPrimaryHosts(List.of("domains.hrva.cc", "localhost"));

        assertThat(hostnames.isPrimary("hrva.cc")).isTrue();
        assertThat(hostnames.isPrimary("HRVA.CC.")).isTrue();
        assertThat(hostnames.isPrimary("domains.hrva.cc")).isTrue();
        assertThat(hostnames.isPrimary("localhost:18087")).isTrue();
        assertThat(hostnames.isPrimary("hrva.cc:443")).isTrue();
        assertThat(hostnames.isPrimary("go.example.com")).isFalse();
        assertThat(hostnames.isPrimary("evil.hrva.cc")).isFalse();
        assertThat(hostnames.isPrimary("127.0.0.1")).isFalse();
    }

    @Test
    void theDefaultPrimaryHostsAreHrvaAndTheDomainsTarget() {
        appProperties.setServerUrl("http://localhost:18087");

        assertThat(hostnames.isPrimary("hrva.cc")).isTrue();
        assertThat(hostnames.isPrimary("domains.hrva.cc")).isTrue();
        assertThat(hostnames.isPrimary("localhost")).isTrue();
        assertThat(hostnames.isPrimary("test.karlo.cc")).isFalse();
    }

    @Test
    void primaryHostsCompareBracketedAddressesWithTheirPort() {
        appProperties.setServerUrl("http://[::1]:18087");
        appProperties.getDomains().setPrimaryHosts(List.of("127.0.0.1"));

        assertThat(hostnames.isPrimary("[::1]:18087")).isTrue();
        assertThat(hostnames.isPrimary("[::1]")).isTrue();
        assertThat(hostnames.isPrimary("127.0.0.1:18087")).isTrue();
        assertThat(hostnames.isPrimary("[::2]:18087")).isFalse();
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "go..example.com"})
    void anUnreadableHostIsNotPrimary(final String host) {
        assertThat(hostnames.isPrimary(host)).isFalse();
    }

    @Test
    void hostnameKeyLowercasesStripsOneTrailingDotAndConvertsToPunycode() {
        assertThat(CustomDomainHostnames.hostnameKey(" Go.Example.COM. ")).contains("go.example.com");
        assertThat(CustomDomainHostnames.hostnameKey(CYRILLIC_LOOK_ALIKE.toUpperCase(Locale.ROOT))).contains("xn--g-1tb.example.com");
        assertThat(CustomDomainHostnames.hostnameKey("go.example.com..")).contains("go.example.com.");
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "go..example.com"})
    void hostnameKeyIsEmptyForWhatCannotBeRead(final String host) {
        assertThat(CustomDomainHostnames.hostnameKey(host)).isEmpty();
    }

    @Test
    void normalizeAgreesWithTheHostnameKey() {
        assertThat(hostnames.normalize(" GO.Example.COM. ")).isEqualTo(CustomDomainHostnames.hostnameKey(" GO.Example.COM. ").orElseThrow());
        assertThat(hostnames.normalize(CYRILLIC_LOOK_ALIKE)).isEqualTo(CustomDomainHostnames.hostnameKey(CYRILLIC_LOOK_ALIKE).orElseThrow());
    }

    private void assertRefused(final String input, final String text) {
        final var refusal = catchThrowableOfType(FieldValidationException.class, () -> hostnames.normalize(input));

        assertThat(refusal).as("refusal of %s", input).isNotNull();
        assertThat(refusal.getFieldErrors()).isEqualTo(Map.of("hostname", text));
    }

}
