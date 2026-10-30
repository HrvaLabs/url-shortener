package cc.hrva.urlshortener.service.settings;

import cc.hrva.urlshortener.configuration.properties.AppProperties;
import cc.hrva.urlshortener.exception.FieldValidationException;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.MissingNode;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SettingValuesTest {

    private static final JsonNodeFactory NODES = JsonNodeFactory.instance;
    private static final String CAMPAIGN_RANGE = "Use a number from 2 to 50.";

    @ParameterizedTest
    @ValueSource(ints = {1, 51})
    void aNumberOutsideItsRangeNamesTheRange(final int links) {
        assertRefused(SettingKey.CAMPAIGN_LINKS, NODES.numberNode(links), CAMPAIGN_RANGE);
    }

    @Test
    void theRangeTextGroupsThousands() {
        assertRefused(SettingKey.TRAFFIC_LINK_CLICKS, NODES.numberNode(4), "Use a number from 5 to 100,000.");
    }

    @Test
    void aMissingNullTextOrFractionalNumberNamesTheRange() {
        assertRefused(SettingKey.CAMPAIGN_LINKS, null, CAMPAIGN_RANGE);
        assertRefused(SettingKey.CAMPAIGN_LINKS, MissingNode.getInstance(), CAMPAIGN_RANGE);
        assertRefused(SettingKey.CAMPAIGN_LINKS, NODES.nullNode(), CAMPAIGN_RANGE);
        assertRefused(SettingKey.CAMPAIGN_LINKS, NODES.stringNode("x"), CAMPAIGN_RANGE);
        assertRefused(SettingKey.CAMPAIGN_LINKS, NODES.stringNode("5"), CAMPAIGN_RANGE);
        assertRefused(SettingKey.CAMPAIGN_LINKS, NODES.numberNode(2.5), CAMPAIGN_RANGE);
        assertRefused(SettingKey.CAMPAIGN_LINKS, NODES.numberNode(10_000_000_000L), CAMPAIGN_RANGE);
    }

    @Test
    void aSwitchTakesOnlyABoolean() {
        assertRefused(SettingKey.ANONYMOUS_LINKS, NODES.stringNode("yes"), "Choose on or off.");
        assertRefused(SettingKey.ANONYMOUS_LINKS, NODES.numberNode(1), "Choose on or off.");
        assertRefused(SettingKey.ANONYMOUS_LINKS, NODES.nullNode(), "Choose on or off.");
    }

    @Test
    void validValuesParse() {
        assertThat(SettingValues.parse(SettingKey.ANONYMOUS_LINKS, NODES.booleanNode(false))).isEqualTo(false);
        assertThat(SettingValues.parse(SettingKey.CAMPAIGN_LINKS, NODES.numberNode(2))).isEqualTo(2);
        assertThat(SettingValues.parse(SettingKey.CAMPAIGN_LINKS, NODES.numberNode(50))).isEqualTo(50);
        assertThat(SettingValues.parse(SettingKey.TRAFFIC_LINK_CLICKS, NODES.numberNode(100_000))).isEqualTo(100_000);
    }

    @Test
    void valuesPrintAsOnOffAndGroupedNumbers() {
        assertThat(SettingValues.format(SettingKey.ANONYMOUS_LINKS, true)).isEqualTo("On");
        assertThat(SettingValues.format(SettingKey.ANONYMOUS_LINKS, false)).isEqualTo("Off");
        assertThat(SettingValues.format(SettingKey.TRAFFIC_LINK_CLICKS, 25_000)).isEqualTo("25,000");
    }

    @Test
    void storedTextRoundTripsWithoutGrouping() {
        assertThat(SettingValues.stored(25_000)).isEqualTo("25000");
        assertThat(SettingValues.stored(true)).isEqualTo("true");
        assertThat(SettingValues.fromStored(SettingKey.TRAFFIC_LINK_CLICKS, "25000")).isEqualTo(25_000);
        assertThat(SettingValues.fromStored(SettingKey.ANONYMOUS_LINKS, "false")).isEqualTo(false);
    }

    @ParameterizedTest
    @ValueSource(strings = {"99", "1", "abc", "2.5", "true", "\"5\"", ""})
    void storedTextThatNoLongerFitsIsRefused(final String stored) {
        assertThatThrownBy(() -> SettingValues.fromStored(SettingKey.CAMPAIGN_LINKS, stored)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aMissingStoredValueIsRefusedForAKeyThatCantBeNone() {
        assertThatThrownBy(() -> SettingValues.fromStored(SettingKey.CAMPAIGN_LINKS, null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SettingValues.fromStored(SettingKey.ANONYMOUS_LINKS, "yes")).isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @EnumSource(SettingKey.class)
    void everyKeyHasACatalogueRow(final SettingKey key) {
        assertThat(key.getGroup()).isNotNull();
        assertThat(key.getType()).isNotNull();
        assertThat(key.getLabel()).isNotBlank().doesNotEndWith(".");
        assertThat(key.getDescription()).isNotBlank().endsWith(".");
        Optional.ofNullable(key.getParent()).ifPresent(parent -> {
            assertThat(parent.getType()).isEqualTo(SettingType.SWITCH);
            assertThat(parent.ordinal()).isLessThan(key.ordinal());
        });
        if (key.getType() == SettingType.NUMBER) {
            assertThat(key.getMin()).isNotNull();
            assertThat(key.getMax()).isGreaterThan(key.getMin());
            assertThat(key.getUnit()).isNotBlank();
        } else {
            assertThat(key.getMin()).isNull();
            assertThat(key.getMax()).isNull();
        }
        if (key.getType() == SettingType.TEXT) {
            assertThat(key.getMinLength()).isPositive();
            assertThat(key.getMaxLength()).isGreaterThanOrEqualTo(key.getMinLength());
        } else {
            assertThat(key.getMinLength()).isNull();
            assertThat(key.getMaxLength()).isNull();
        }
        if (key.getType() == SettingType.ADDRESSES) {
            assertThat(key.getMinItems()).isNotNegative();
            assertThat(key.getMaxItems()).isGreaterThanOrEqualTo(key.getMinItems()).isPositive();
        } else {
            assertThat(key.getMinItems()).isNull();
            assertThat(key.getMaxItems()).isNull();
        }
        assertThat(key.isNullable()).isEqualTo(key.getNoneLabel() != null);
        final var defaults = new SettingDefaults(new AppProperties());
        final var value = defaults.defaultOf(key);
        assertThat(SettingValues.fromStored(key, SettingValues.stored(value))).isEqualTo(value);
        assertThat(defaults.unavailableReason(key)).isEmpty();
    }

    @Test
    void theLinkCheckSwitchesNameEveryPlaceTheyReach() {
        assertThat(SettingKey.SAFE_BROWSING_CHECK.getDescription())
                .isEqualTo("New links, links turned back on and changed destinations are checked with Google Safe Browsing.");
        assertThat(SettingKey.BLOCKLIST_CHECK.getDescription()).isEqualTo("The enabled blocklists and Spamhaus, on new links, clicks, the nightly "
                + "recheck, redirect hops, Allow-rule restores, custom-domain applications and the nightly custom-domain check. Domain rules always apply.");
        assertThat(SettingKey.SUSPICIOUS_LINK_CHECK.getDescription()).isEqualTo("Lookalike brands, risky endings, login lures and young domains, "
                + "on new links, the nightly recheck, redirect hops, Allow-rule restores, custom-domain applications and the nightly custom-domain check.");
    }

    @Test
    void customAliasesSaysCustomDomainsKeepTheirAliases() {
        assertThat(SettingKey.CUSTOM_ALIASES.getDescription())
                .isEqualTo("Signed-in users choose their own short link on the web, the API and MCP. Links on a custom domain keep their aliases.");
    }

    @Test
    void theNightlySafeBrowsingSwitchSaysTheRedirectHopLookupsStillRun() {
        assertThat(SettingKey.NIGHTLY_SAFE_BROWSING.getDescription()).isEqualTo("The Safe Browsing part of the 04:30 recheck. The same run's "
                + "Safe Browsing lookups on redirect hops still happen, and the other rechecks follow their own switches.");
    }

    @Test
    void theSameShapeAlertIsASafetySwitchThatAlsoStopsHolds() {
        assertThat(SettingKey.SAME_SHAPE_ALERT.isSafety()).isTrue();
        assertThat(SettingKey.SAME_SHAPE_ALERT.getDescription()).isEqualTo("A Discord alert and a Campaigns row when links without an account share "
                + "one URL shape across several domains. Off also stops holds.");
    }

    @Test
    void theCampaignHoldRows() {
        assertThat(SettingKey.CAMPAIGN_HOLD).extracting(SettingKey::getGroup, SettingKey::getType, SettingKey::getLabel, SettingKey::getDescription,
                        SettingKey::getParent, SettingKey::isSafety)
                .containsExactly(SettingGroup.ALERTS, SettingType.SWITCH, "Hold links with a campaign URL shape",
                        "Holds the links of a same-shape alert. Off still sends the alert and lists the campaign.", SettingKey.SAME_SHAPE_ALERT, true);
        assertThat(SettingKey.CAMPAIGN_HOLD_DAYS).extracting(SettingKey::getGroup, SettingKey::getType, SettingKey::getLabel, SettingKey::getDescription,
                        SettingKey::getParent, SettingKey::isSafety, SettingKey::getMin, SettingKey::getMax, SettingKey::getUnit)
                .containsExactly(SettingGroup.ALERTS, SettingType.NUMBER, "Same-shape alert: hold",
                        "New matching links are held for this many days after the last match.", SettingKey.CAMPAIGN_HOLD, false, 1, 90, "days");
        assertThat(Arrays.asList(SettingKey.values()).subList(SettingKey.SAME_SHAPE_ALERT.ordinal(), SettingKey.SAME_SHAPE_ALERT.ordinal() + 6))
                .containsExactly(SettingKey.SAME_SHAPE_ALERT, SettingKey.CAMPAIGN_LINKS, SettingKey.CAMPAIGN_DOMAINS, SettingKey.CAMPAIGN_WINDOW_HOURS,
                        SettingKey.CAMPAIGN_HOLD, SettingKey.CAMPAIGN_HOLD_DAYS);
        final var defaults = new SettingDefaults(new AppProperties());
        assertThat(defaults.defaultOf(SettingKey.CAMPAIGN_HOLD)).isEqualTo(true);
        assertThat(defaults.defaultOf(SettingKey.CAMPAIGN_HOLD_DAYS)).isEqualTo(7);
    }

    @Test
    void failClosedSafeBrowsingIsNotASafetySwitch() {
        assertThat(SettingKey.SAFE_BROWSING_FAIL_CLOSED.isSafety()).isFalse();
        assertThat(SettingKey.SAFE_BROWSING_FAIL_CLOSED.getParent()).isEqualTo(SettingKey.SAFE_BROWSING_CHECK);
    }

    @Test
    void groupsAreLabelledInOrderAndLabelsAreUnique() {
        final var keys = Arrays.asList(SettingKey.values());
        assertThat(keys).extracting(SettingKey::getLabel).doesNotHaveDuplicates();
        assertThat(Arrays.stream(SettingGroup.values()).map(SettingGroup::getLabel))
                .containsExactly("Access", "Links", "Safety", "Alerts", "Digest", "Emails", "Abuse reports");
    }

    @Test
    void theAbuseReportRows() {
        assertThat(SettingKey.ABUSE_REPORTER_NAME).extracting(SettingKey::getGroup, SettingKey::getType, SettingKey::getLabel, SettingKey::getDescription,
                        SettingKey::getParent, SettingKey::getMinLength, SettingKey::getMaxLength)
                .containsExactly(SettingGroup.ABUSE_REPORTS, SettingType.TEXT, "Abuse reports: your name", "Signs the report emails.", null, 1, 100);
        assertThat(SettingKey.ABUSE_REPORTER_ADDRESS).extracting(SettingKey::getGroup, SettingKey::getType, SettingKey::getLabel, SettingKey::getDescription,
                        SettingKey::getMinItems, SettingKey::getMaxItems)
                .containsExactly(SettingGroup.ABUSE_REPORTS, SettingType.ADDRESSES, "Abuse reports: your address", "Under your name in the report emails.", 1, 1);
        assertThat(SettingKey.ABUSE_CC).extracting(SettingKey::getGroup, SettingKey::getType, SettingKey::getLabel, SettingKey::getDescription,
                        SettingKey::getMinItems, SettingKey::getMaxItems)
                .containsExactly(SettingGroup.ABUSE_REPORTS, SettingType.ADDRESSES, "Abuse reports: Cc", "Copied on every report email.", 0, 5);
        assertThat(SettingKey.ABUSE_RECHECK_DAYS).extracting(SettingKey::getGroup, SettingKey::getType, SettingKey::getLabel, SettingKey::getDescription,
                        SettingKey::getMin, SettingKey::getMax, SettingKey::getUnit)
                .containsExactly(SettingGroup.ABUSE_REPORTS, SettingType.NUMBER, "Abuse reports: recheck for",
                        "Reported domains are looked up every day for this many days.", 1, 90, "days");
        final var defaults = new SettingDefaults(new AppProperties());
        assertThat(defaults.defaultOf(SettingKey.ABUSE_REPORTER_NAME)).isEqualTo(new String(new int[] {'K', 'a', 'r', 'l', 'o', ' ', 'H', 'r', 'v', 'a', 0x10D, 'i', 0x107}, 0, 13));
        assertThat(defaults.defaultOf(SettingKey.ABUSE_REPORTER_ADDRESS)).isEqualTo(List.of("abuse@hrva.cc"));
        assertThat(defaults.defaultOf(SettingKey.ABUSE_CC)).isEqualTo(List.of("reportphishing@apwg.org"));
        assertThat(defaults.defaultOf(SettingKey.ABUSE_RECHECK_DAYS)).isEqualTo(30);
    }

    @Test
    void aTextIsStrippedAndHoldsOneTo100Characters() {
        assertThat(SettingValues.parse(SettingKey.ABUSE_REPORTER_NAME, NODES.stringNode("  Karlo  "))).isEqualTo("Karlo");
        assertThat(SettingValues.parse(SettingKey.ABUSE_REPORTER_NAME, NODES.stringNode("x".repeat(100)))).isEqualTo("x".repeat(100));
        assertThat(SettingValues.parse(SettingKey.ABUSE_REPORTER_NAME, NODES.stringNode(Character.toString(0x1F600).repeat(100))))
                .isEqualTo(Character.toString(0x1F600).repeat(100));
        for (final var refused : List.<JsonNode>of(NODES.stringNode(""), NODES.stringNode("   "), NODES.stringNode("x".repeat(101)), NODES.numberNode(5),
                NODES.nullNode(), MissingNode.getInstance(), NODES.arrayNode().add("Karlo"))) {
            assertRefused(SettingKey.ABUSE_REPORTER_NAME, refused, "Use 1 to 100 characters.");
        }
    }

    @Test
    void theReporterAddressIsExactlyOneEmailAddress() {
        assertThat(SettingValues.parse(SettingKey.ABUSE_REPORTER_ADDRESS, addresses(" abuse@hrva.cc "))).isEqualTo(List.of("abuse@hrva.cc"));
        for (final var refused : List.<JsonNode>of(addresses(), addresses("a@hrva.cc", "b@hrva.cc"), addresses("not-an-address"), NODES.stringNode("abuse@hrva.cc"),
                NODES.nullNode(), MissingNode.getInstance(), NODES.arrayNode().add(5))) {
            assertRefused(SettingKey.ABUSE_REPORTER_ADDRESS, refused, "Enter one email address.");
        }
    }

    @Test
    void theCcTakesUpToFiveEmailAddressesAndNamesTheFirstBadOne() {
        assertThat(SettingValues.parse(SettingKey.ABUSE_CC, addresses())).isEqualTo(List.of());
        assertThat(SettingValues.parse(SettingKey.ABUSE_CC, addresses("a@apwg.org", "b@cert.pt", "c@incibe-cert.es", "d@x.hr", "e@y.hr")))
                .isEqualTo(List.of("a@apwg.org", "b@cert.pt", "c@incibe-cert.es", "d@x.hr", "e@y.hr"));
        assertRefused(SettingKey.ABUSE_CC, addresses("a@x.hr", "b@x.hr", "c@x.hr", "d@x.hr", "e@x.hr", "f@x.hr"), "Enter up to 5 email addresses.");
        assertRefused(SettingKey.ABUSE_CC, NODES.stringNode("a@x.hr"), "Enter up to 5 email addresses.");
        assertRefused(SettingKey.ABUSE_CC, NODES.nullNode(), "Enter up to 5 email addresses.");
        assertRefused(SettingKey.ABUSE_CC, addresses("a@x.hr", "abuse at x.hr", "nope"), "abuse at x.hr isn't an email address.");
        for (final var bad : List.of("a@x", "@x.hr", "a@", "a b@x.hr", "a@x..hr", "a,b@x.hr", "<a@x.hr>", "a@-x.hr")) {
            assertRefused(SettingKey.ABUSE_CC, addresses(bad), bad + " isn't an email address.");
        }
        assertRefused(SettingKey.ABUSE_CC, addresses(""), " isn't an email address.");
    }

    @Test
    void addressesRoundTripAndPrintJoined() {
        assertThat(SettingValues.stored(List.of("a@x.hr", "b@x.hr"))).isEqualTo("[\"a@x.hr\",\"b@x.hr\"]");
        assertThat(SettingValues.fromStored(SettingKey.ABUSE_CC, "[\"a@x.hr\",\"b@x.hr\"]")).isEqualTo(List.of("a@x.hr", "b@x.hr"));
        assertThat(SettingValues.fromStored(SettingKey.ABUSE_REPORTER_NAME, "\"Karlo\"")).isEqualTo("Karlo");
        assertThat(SettingValues.format(SettingKey.ABUSE_CC, List.of("a@x.hr", "b@x.hr"))).isEqualTo("a@x.hr, b@x.hr");
        assertThat(SettingValues.format(SettingKey.ABUSE_CC, List.of())).isEqualTo("");
        assertThatThrownBy(() -> SettingValues.fromStored(SettingKey.ABUSE_REPORTER_ADDRESS, "[]")).isInstanceOf(IllegalArgumentException.class);
    }

    private static JsonNode addresses(final String... values) {
        final var array = NODES.arrayNode();
        for (final var value : values) {
            array.add(value);
        }
        return array;
    }

    private static void assertRefused(final SettingKey key, final JsonNode value, final String text) {
        assertThatThrownBy(() -> SettingValues.parse(key, value))
                .isInstanceOfSatisfying(FieldValidationException.class, refusal -> assertThat(refusal.getFieldErrors()).isEqualTo(Map.of("value", text)));
    }

}
