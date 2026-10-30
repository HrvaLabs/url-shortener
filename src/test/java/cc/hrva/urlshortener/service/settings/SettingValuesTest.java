package cc.hrva.urlshortener.service.settings;

import cc.hrva.urlshortener.configuration.properties.AppProperties;
import cc.hrva.urlshortener.exception.FieldValidationException;
import java.util.Arrays;
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

    private static void assertRefused(final SettingKey key, final JsonNode value, final String text) {
        assertThatThrownBy(() -> SettingValues.parse(key, value))
                .isInstanceOfSatisfying(FieldValidationException.class, refusal -> assertThat(refusal.getFieldErrors()).isEqualTo(Map.of("value", text)));
    }

}
