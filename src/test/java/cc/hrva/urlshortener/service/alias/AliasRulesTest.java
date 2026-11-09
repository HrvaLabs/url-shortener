package cc.hrva.urlshortener.service.alias;

import cc.hrva.urlshortener.configuration.properties.AppProperties;
import cc.hrva.urlshortener.exception.FieldValidationException;
import cc.hrva.urlshortener.exception.ShortUrlAlreadyExistsException;
import cc.hrva.urlshortener.exception.UrlValidationException;
import cc.hrva.urlshortener.repository.UrlRepository;
import cc.hrva.urlshortener.service.impl.DefaultSuspiciousUrlService;
import cc.hrva.urlshortener.service.ReservedAliasService;
import cc.hrva.urlshortener.service.blocklist.HostLists;
import cc.hrva.urlshortener.service.impl.DefaultSettingsService;
import cc.hrva.urlshortener.service.settings.SettingKey;
import cc.hrva.urlshortener.service.settings.SettingsServiceFixtures;
import cc.hrva.urlshortener.validator.UrlValidator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AliasRulesTest {

    private static final Long DOMAIN = 7L;

    private final AppProperties appProperties = new AppProperties();
    private final ReservedAliasService reservedAliasService = mock(ReservedAliasService.class);
    private final UrlRepository urlRepository = mock(UrlRepository.class);
    private final HostLists hostLists = mock(HostLists.class);
    private DefaultSettingsService settingsService;
    private AliasRules aliasRules;

    @BeforeEach
    void setUp() {
        appProperties.setServerUrl("https://hrva.cc");
        settingsService = SettingsServiceFixtures.defaults(appProperties);
        when(reservedAliasService.isReserved(anyString())).thenAnswer(invocation -> "api".equals(invocation.getArgument(0)) || "promo".equals(invocation.getArgument(0)));
        aliasRules = new AliasRules(settingsService, reservedAliasService, new DefaultSuspiciousUrlService(appProperties, hostLists), urlRepository);
    }

    @Test
    void aFreeNameIsAvailable() {
        final var decision = aliasRules.decide("karlo", null, false);

        assertThat(decision).isEqualTo(new AliasDecision(AliasStatus.AVAILABLE, null));
        assertThat(decision.usable()).isTrue();
    }

    @Test
    void aCaseVariantOfAnExistingAliasIsTaken() {
        when(urlRepository.aliasTakenIgnoringCase(isNull(), eq("Karlo-GitHub"))).thenReturn(true);

        final var decision = aliasRules.decide("Karlo-GitHub", null, false);

        assertThat(decision).isEqualTo(new AliasDecision(AliasStatus.TAKEN, UrlValidator.SHORT_URL_TAKEN));
        assertThat(decision.usable()).isFalse();
    }

    @Test
    void aBuiltInNameIsReserved() {
        assertThat(aliasRules.decide("api", null, false))
                .isEqualTo(new AliasDecision(AliasStatus.RESERVED, "This short URL is reserved. Try another."));
    }

    @Test
    void anAdminReservedNameIsReserved() {
        assertThat(aliasRules.decide("promo", DOMAIN, false).status()).isEqualTo(AliasStatus.RESERVED);
    }

    @ParameterizedTest
    @ValueSource(strings = {"wp-admin", "WP-Admin", "favicon.ico"})
    void aBotProbeNameIsReserved(final String alias) {
        assertThat(aliasRules.decideExisting(alias, null, false).status()).isEqualTo(AliasStatus.RESERVED);
    }

    @Test
    void aBotProbeNameInsidePatternIsReservedOnDecide() {
        assertThat(aliasRules.decide("WP-Admin", null, false).status()).isEqualTo(AliasStatus.RESERVED);
    }

    @ParameterizedTest
    @ValueSource(strings = {"paypal-login", "paypa1-login"})
    void aBrandNameIsReservedOnHrvaCc(final String alias) {
        assertThat(aliasRules.decide(alias, null, false))
                .isEqualTo(new AliasDecision(AliasStatus.RESERVED, "This name contains the brand name paypal, so it can't be used on hrva.cc. Try another."));
    }

    @ParameterizedTest
    @ValueSource(strings = {"support", "Admin", "hrva-support", "hrvalabs_news", "HRVA-Login"})
    void aNameThatLooksLikeAnHrvaCcPageIsReservedOnHrvaCc(final String alias) {
        assertThat(aliasRules.decide(alias, null, false))
                .isEqualTo(new AliasDecision(AliasStatus.RESERVED, "This name looks like one of hrva.cc's own pages, so it can't be used. Try another."));
        assertThat(aliasRules.decideExisting(alias, null, false).status()).isEqualTo(AliasStatus.RESERVED);
    }

    @ParameterizedTest
    @ValueSource(strings = {"support", "admin", "hrva-support"})
    void aNameThatLooksLikeAnHrvaCcPageIsAllowedOnACustomDomainAndForAdmins(final String alias) {
        assertThat(aliasRules.decide(alias, DOMAIN, false).usable()).isTrue();
        assertThat(aliasRules.decide(alias, null, true).usable()).isTrue();
    }

    @Test
    void aNameThatOnlyContainsAServiceNameIsNotRefused() {
        assertThat(aliasRules.decide("my-hrva", null, false).usable()).isTrue();
        assertThat(aliasRules.decide("supportive", null, false).usable()).isTrue();
    }

    @Test
    void aBrandNameIsAllowedForBrandExemptCallers() {
        assertThat(aliasRules.decide("paypal-login", null, true).usable()).isTrue();
    }

    @Test
    void aBrandNameIsAllowedOnACustomDomain() {
        assertThat(aliasRules.decide("paypal-login", DOMAIN, false).usable()).isTrue();
    }

    @Test
    void aCommonBrandWordIsReservedToo() {
        assertThat(aliasRules.decide("booking-form", null, false))
                .isEqualTo(new AliasDecision(AliasStatus.RESERVED, "This name contains the brand name booking, so it can't be used on hrva.cc. Try another."));
    }

    @ParameterizedTest
    @ValueSource(strings = {"a b", "a/b", "", "   ", "abcdefghijklmnopqrstuvwxyzabcdefg"})
    void aNameOutsideThePatternIsInvalid(final String alias) {
        assertThat(aliasRules.decide(alias, null, false))
                .isEqualTo(new AliasDecision(AliasStatus.INVALID, "Use 1 to 32 letters, numbers, hyphens or underscores."));
    }

    @Test
    void customAliasesOffDisablesHrvaCcOnly() {
        SettingsServiceFixtures.turnOff(settingsService, SettingKey.CUSTOM_ALIASES);

        assertThat(aliasRules.decide("karlo", null, false))
                .isEqualTo(new AliasDecision(AliasStatus.DISABLED, "Custom aliases are turned off right now."));
        assertThat(aliasRules.decide("karlo", DOMAIN, false).usable()).isTrue();
        assertThat(aliasRules.decide("api", DOMAIN, false).status()).isEqualTo(AliasStatus.RESERVED);
    }

    @Test
    void anExistingNameIsJudgedWithoutTheSwitchOrThePattern() {
        SettingsServiceFixtures.turnOff(settingsService, SettingKey.CUSTOM_ALIASES);
        when(urlRepository.aliasTakenIgnoringCase(isNull(), eq("a.b"))).thenReturn(false);

        assertThat(aliasRules.decideExisting("a.b", null, false).usable()).isTrue();
        assertThat(aliasRules.decideExisting("api", null, false).status()).isEqualTo(AliasStatus.RESERVED);
    }

    @Test
    void requireThrowsWhatCreateThrowsToday() {
        when(urlRepository.aliasTakenIgnoringCase(isNull(), eq("taken"))).thenReturn(true);

        assertThatThrownBy(() -> aliasRules.require("taken", null, false))
                .isInstanceOf(ShortUrlAlreadyExistsException.class).hasMessage(UrlValidator.SHORT_URL_TAKEN);
        assertThatThrownBy(() -> aliasRules.require("api", null, false))
                .isInstanceOf(ShortUrlAlreadyExistsException.class).hasMessage("This short URL is reserved. Try another.");
        assertThatThrownBy(() -> aliasRules.require("a b", null, false)).isInstanceOf(FieldValidationException.class);
        aliasRules.require("free", null, false);
        SettingsServiceFixtures.turnOff(settingsService, SettingKey.CUSTOM_ALIASES);
        assertThatThrownBy(() -> aliasRules.require("free", null, false))
                .isInstanceOf(UrlValidationException.class).hasMessage("Custom aliases are turned off right now.");
    }

}
