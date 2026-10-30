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
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

class CustomDomainHostnamesTest {

    private static final String BLANK = "Enter a domain, like go.example.com";
    private static final String TOO_LONG = "A domain can be 253 characters at most.";
    private static final String BAD_SHAPE = "Enter a domain like go.example.com: letters, digits and hyphens, with dots between the parts.";
    private static final String APEX = "Use a subdomain, like go.example.com. Root domains like example.com aren't supported yet.";
    private static final String NOT_OWNABLE = "Enter a domain you own, like go.example.com.";
    private static final String RESERVED = "This domain can't be used on hrva.cc.";
    private static final String SYMBOLS = "Emoji and symbol domains can't be used. Use a domain made of letters and digits.";
    private static final String MIXED_SCRIPT_HRVA = "go.hrv\u0430.cc";
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
    @ValueSource(strings = {"2130706433", "0177.0.0.1", "0x7f000001", "0x7f.0.0.1", "2001:0db8:0000:0000:0000:0000:0000:0001",
            "[2001:0db8:0000:0000:0000:0000:0000:0001]", "[2001:db8::1]"})
    void everyWayOfWritingAnAddressIsNotOwnable(final String input) {
        assertRefused(input, NOT_OWNABLE);
    }

    @ParameterizedTest
    @ValueSource(strings = {"xn--zzzzzz.example.com", "go.xn--.com", "xn--a.example.com", "go.xn--abc-.example.com"})
    void bogusPunycodeIsRefused(final String input) {
        assertRefused(input, BAD_SHAPE);
    }

    @Test
    void realPunycodeLabelsPass() {
        assertThat(hostnames.normalize("xn--g-1tb.example.com")).isEqualTo("xn--g-1tb.example.com");
        assertThat(hostnames.normalize("go.xn--hrv-8cd.cc")).isEqualTo("go.xn--hrv-8cd.cc");
        assertThat(hostnames.normalize("xn--zca.example.com")).isEqualTo("xn--zca.example.com");
        assertThat(hostnames.normalize("go.xn--strae-oqa.de")).isEqualTo("go.xn--strae-oqa.de");
        assertThat(hostnames.normalize("xn--pxac2aodamd.example.com")).isEqualTo("xn--pxac2aodamd.example.com");
        assertThat(hostnames.normalize("xn--mgba3gch5960b.example.com")).isEqualTo("xn--mgba3gch5960b.example.com");
    }

    @ParameterizedTest
    @ValueSource(strings = {"xn--w6ja155uba.example.com", "xn--nbd4itbt1dwfyc.example.com", "xn--f9dt7l.example.com"})
    void idna2008LabelsOutsideTheLetterAndDigitClassesPass(final String input) {
        assertThat(hostnames.normalize(input)).isEqualTo(input);
        assertThat(CustomDomainHostnames.hostnameKey(input)).contains(input);
    }

    @Test
    void theIdeographicZeroThatTheKeyProducesPasses() {
        final var key = CustomDomainHostnames.hostnameKey("go\u3007\u3007.example.com").orElseThrow();

        assertThat(key).startsWith("xn--");
        assertThat(hostnames.normalize(key)).isEqualTo(key);
    }

    @ParameterizedTest
    @ValueSource(strings = {"xn--wca.example.com", "xn--e-xbb.example.com", "xn--hqd2n7h.example.com", "xn--a-wbb.example.com"})
    void uppercaseUnnormalizedAndMarkFirstLabelsAreRefused(final String input) {
        assertRefused(input, BAD_SHAPE);
    }

    @ParameterizedTest
    @ValueSource(strings = {"xn--ls8h.example.com", "go.xn--qei.example.com"})
    void emojiAndSymbolLabelsGetTheirOwnText(final String input) {
        assertRefused(input, SYMBOLS);
    }

    @ParameterizedTest
    @ValueSource(strings = {"xn--a.example.com", "xn--abc.example.com", "xn--zzzzzz.example.com"})
    void punycodeOfSomethingThatIsNotADomainLabelIsRefused(final String input) {
        assertRefused(input, BAD_SHAPE);
    }

    @Test
    void configurationEntriesInPunycodeThatTheJdkWouldChangeAreAccepted() {
        appProperties.getDomains().setReserved(List.of("xn--zca.example.com", "*.go.xn--strae-oqa.de"));
        appProperties.getDomains().setPrimaryHosts(List.of("xn--pxac2aodamd.example.com"));
        appProperties.getDomains().setIgnoredHostnames(List.of("xn--mgba3gch5960b.example.com"));

        assertThatCode(hostnames::checkSettings).doesNotThrowAnyException();
        assertRefused("a.go.xn--strae-oqa.de", RESERVED);
        assertRefused("xn--pxac2aodamd.example.com", RESERVED);
    }

    @Test
    void blankListEntriesFromTheEnvironmentAreIgnored() {
        appProperties.getDomains().setReserved(List.of("hrva.cc", "", " ", "hrvalabs.net"));
        appProperties.getDomains().setPrimaryHosts(List.of("", "domains.hrva.cc", " "));
        appProperties.getDomains().setIgnoredHostnames(List.of("test.karlo.cc", ""));

        assertThatCode(hostnames::checkSettings).doesNotThrowAnyException();
        assertRefused("evil.hrva.cc", RESERVED);
        assertRefused("go.hrvalabs.net", RESERVED);
        assertThat(hostnames.isPrimary("domains.hrva.cc")).isTrue();
        assertThat(hostnames.isPrimary("")).isFalse();
        assertThat(hostnames.normalize("go.example.com")).isEqualTo("go.example.com");
    }

    @Test
    void aBlankCnameTargetStillStopsTheStart() {
        appProperties.getDomains().setCnameTarget(" ");

        assertThatThrownBy(hostnames::checkSettings).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("app.domains.cname-target");
    }

    @Test
    void aMixedScriptLookAlikeOfAReservedNamePassesAsTheDifferentDomainItIs() {
        final var normalized = hostnames.normalize(MIXED_SCRIPT_HRVA);

        assertThat(normalized).isEqualTo("go.xn--hrv-8cd.cc").isEqualTo(DomainNames.toAscii(MIXED_SCRIPT_HRVA));
        assertThat(hostnames.isReserved(MIXED_SCRIPT_HRVA)).isFalse();
    }

    @Test
    void aPrimaryHostIsRefusedAsReserved() {
        appProperties.getDomains().setPrimaryHosts(List.of("short.karlo.cc"));

        assertRefused("short.karlo.cc", RESERVED);
        assertRefused("SHORT.karlo.cc.", RESERVED);
        assertThat(hostnames.normalize("go.karlo.cc")).isEqualTo("go.karlo.cc");
    }

    @Test
    void theCnameTargetIsRefusedAsReserved() {
        appProperties.getDomains().setCnameTarget("cname.karlo.cc");
        appProperties.getDomains().setPrimaryHosts(List.of());

        assertRefused("cname.karlo.cc", RESERVED);
        assertThat(hostnames.normalize("go.karlo.cc")).isEqualTo("go.karlo.cc");
    }

    @Test
    void anIgnoredHostnameCanStillBeAppliedFor() {
        appProperties.getDomains().setIgnoredHostnames(List.of("test.karlo.cc"));

        assertThat(hostnames.normalize("test.karlo.cc")).isEqualTo("test.karlo.cc");
    }

    @ParameterizedTest
    @ValueSource(strings = {".hrva.cc", "*.hrva.cc", "*.HRVA.cc.", "hrva.cc"})
    void suffixConventionsInTheReservedListStillReserve(final String entry) {
        appProperties.getDomains().setReserved(List.of(entry));

        assertThatCode(hostnames::checkSettings).doesNotThrowAnyException();
        assertRefused("evil.hrva.cc", RESERVED);
        assertRefused("hrva.cc", RESERVED);
        assertThat(hostnames.normalize("go.hrvalabs.net")).isEqualTo("go.hrvalabs.net");
    }

    @Test
    void theDefaultSettingsPassTheCheck() {
        assertThatCode(hostnames::checkSettings).doesNotThrowAnyException();
    }

    @Test
    void primaryHostsMayBeAddressesOrCarryAPort() {
        appProperties.getDomains().setPrimaryHosts(List.of("localhost:18087", "127.0.0.1", "[::1]"));

        assertThatCode(hostnames::checkSettings).doesNotThrowAnyException();
    }

    @ParameterizedTest
    @ValueSource(strings = {"**.hrva.cc", "hrva..cc", "_hrva.cc", "my_host", "https://hrva.cc", "hrva.cc/", "-hrva.cc", "xn--zzzzzz.cc", "xn--a.cc"})
    void anUnreadableReservedEntryStopsTheStart(final String entry) {
        appProperties.getDomains().setReserved(List.of("hrvalabs.net", entry));

        assertThatThrownBy(hostnames::checkSettings)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("app.domains.reserved");
    }

    @Test
    void anUnreadablePrimaryHostStopsTheStart() {
        appProperties.getDomains().setPrimaryHosts(List.of("hrva.cc", "*.hrva.cc"));

        assertThatThrownBy(hostnames::checkSettings).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("app.domains.primary-hosts").hasMessageContaining("*.hrva.cc");
    }

    @Test
    void anUnreadableIgnoredHostnameStopsTheStart() {
        appProperties.getDomains().setIgnoredHostnames(List.of("test..karlo.cc"));

        assertThatThrownBy(hostnames::checkSettings).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("app.domains.ignored-hostnames");
    }

    @Test
    void anUnreadableCnameTargetStopsTheStart() {
        appProperties.getDomains().setCnameTarget("domains_hrva.cc");

        assertThatThrownBy(hostnames::checkSettings).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("app.domains.cname-target");
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
        assertThat(CustomDomainHostnames.hostnameKey("go.example.com\u3002")).contains("go.example.com");
        assertThat(CustomDomainHostnames.hostnameKey("127.0.0.1.")).contains("127.0.0.1");
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "go..example.com", "go.example.com..", "."})
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
