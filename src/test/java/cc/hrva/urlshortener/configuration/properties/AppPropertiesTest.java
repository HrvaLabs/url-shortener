package cc.hrva.urlshortener.configuration.properties;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import java.io.IOException;
import java.time.Duration;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.context.properties.bind.BindException;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.bind.validation.BindValidationException;
import org.springframework.boot.context.properties.bind.validation.ValidationBindHandler;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.validation.beanvalidation.SpringValidatorAdapter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AppPropertiesTest {

    private static final String KEY_MATERIAL = "private-key-material";

    @Test
    void toStringNeverContainsTheMcpSigningKey() {
        final var appProperties = new AppProperties();
        appProperties.getMcp().setIssuer("https://hrva.cc");
        appProperties.getMcp().setSigningKey("{\"kty\":\"RSA\",\"d\":\"" + KEY_MATERIAL + "\"}");

        assertThat(appProperties.getMcp().toString())
                .contains("https://hrva.cc")
                .doesNotContain(KEY_MATERIAL);
        assertThat(appProperties.toString()).doesNotContain(KEY_MATERIAL);
    }

    @Test
    void toStringNeverContainsTheSpamhausDqsKey() {
        final var appProperties = new AppProperties();
        appProperties.setSpamhausDqsKey(KEY_MATERIAL);

        assertThat(appProperties.toString()).doesNotContain(KEY_MATERIAL);
    }

    @Test
    void toStringNeverContainsTheDiscordWebhookUrl() {
        final var appProperties = new AppProperties();
        appProperties.setDiscordWebhookUrl("https://discord.com/api/webhooks/1234567890/" + KEY_MATERIAL);

        assertThat(appProperties.toString()).doesNotContain(KEY_MATERIAL).doesNotContain("discord.com");
    }

    @Test
    void analyticsZoneDefaultsToZagreb() {
        assertThat(new AppProperties().getAnalytics().getZone()).isEqualTo(ZoneId.of("Europe/Zagreb"));
    }

    @Test
    void passkeysBindFromCommaSeparatedOrigins() {
        final var binder = new Binder(new MapConfigurationPropertySource(Map.of(
                "app.passkeys.rp-id", "hrva.cc",
                "app.passkeys.allowed-origins", "https://app.hrva.cc,http://localhost:3000")));

        final var passkeys = binder.bind("app.passkeys", AppProperties.Passkeys.class).get();

        assertThat(passkeys.getRpId()).isEqualTo("hrva.cc");
        assertThat(passkeys.getAllowedOrigins()).containsExactly("https://app.hrva.cc", "http://localhost:3000");
    }

    @Test
    void passkeysBindFromTheEnvironment() {
        final var environment = new SystemEnvironmentPropertySource("environment", Map.<String, Object>of(
                "APP_PASSKEYS_RP_ID", "hrva.cc",
                "APP_PASSKEYS_ALLOWED_ORIGINS", "https://app.hrva.cc,https://hrva.cc"));
        final var binder = new Binder(ConfigurationPropertySources.from(environment));

        final var passkeys = binder.bind("app.passkeys", AppProperties.Passkeys.class).get();

        assertThat(passkeys.getRpId()).isEqualTo("hrva.cc");
        assertThat(passkeys.getAllowedOrigins()).containsExactly("https://app.hrva.cc", "https://hrva.cc");
    }

    @Test
    void weeklyDigestIsOnByDefault() {
        final var weekly = new AppProperties().getDigest().getWeekly();

        assertThat(weekly.isEnabled()).isTrue();
        assertThat(weekly.isEmail()).isTrue();
    }

    @Test
    void weeklyDigestSwitchesBindSeparately() {
        final var discordOff = new Binder(new MapConfigurationPropertySource(Map.of("app.digest.weekly.enabled", "false")))
                .bind("app.digest", AppProperties.Digest.class).get().getWeekly();
        final var emailOff = new Binder(new MapConfigurationPropertySource(Map.of("app.digest.weekly.email", "false")))
                .bind("app.digest", AppProperties.Digest.class).get().getWeekly();

        assertThat(discordOff.isEnabled()).isFalse();
        assertThat(discordOff.isEmail()).isTrue();
        assertThat(emailOff.isEnabled()).isTrue();
        assertThat(emailOff.isEmail()).isFalse();
    }

    @Test
    void weeklyDigestSwitchesBindFromTheEnvironment() {
        final var environment = new SystemEnvironmentPropertySource(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME, Map.<String, Object>of(
                "APP_DIGEST_WEEKLY_ENABLED", "false",
                "APP_DIGEST_WEEKLY_EMAIL", "true"));

        final var weekly = new Binder(ConfigurationPropertySources.from(environment)).bind("app.digest", AppProperties.Digest.class).get().getWeekly();

        assertThat(weekly.isEnabled()).isFalse();
        assertThat(weekly.isEmail()).isTrue();
    }

    @Test
    void theApplicationYamlTurnsBothDigestSwitchesOn() throws IOException {
        final var yaml = new YamlPropertySourceLoader().load("application", new ClassPathResource("application.yaml"));

        assertThat(yaml).hasSize(1);
        assertThat(yaml.getFirst().getProperty("app.digest.weekly.enabled")).isEqualTo(true);
        assertThat(yaml.getFirst().getProperty("app.digest.weekly.email")).isEqualTo(true);
    }

    @Test
    void emailLogsAreKeptNinetyDaysAndTheAuditLogTwelveMonthsByDefault() throws IOException {
        final var retention = new AppProperties().getRetention();
        final var yaml = new YamlPropertySourceLoader().load("application", new ClassPathResource("application.yaml"));

        assertThat(retention.getEmailLogDays()).isEqualTo(90);
        assertThat(retention.getAuditLogMonths()).isEqualTo(12);
        assertThat(yaml.getFirst().getProperty("app.retention.email-log-days")).isEqualTo(90);
        assertThat(yaml.getFirst().getProperty("app.retention.audit-log-months")).isEqualTo(12);
    }

    @Test
    void theRetentionPeriodsBindFromTheEnvironment() {
        final var environment = new SystemEnvironmentPropertySource(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME, Map.<String, Object>of(
                "APP_RETENTION_EMAIL_LOG_DAYS", "30",
                "APP_RETENTION_AUDIT_LOG_MONTHS", "24"));

        final var retention = new Binder(ConfigurationPropertySources.from(environment)).bind("app.retention", AppProperties.Retention.class).get();

        assertThat(retention.getEmailLogDays()).isEqualTo(30);
        assertThat(retention.getAuditLogMonths()).isEqualTo(24);
    }

    @Test
    void aRetentionPeriodBelowOneIsRefused() {
        final var validator = Validation.buildDefaultValidatorFactory().getValidator();
        final var none = new AppProperties.Retention();
        none.setEmailLogDays(0);
        none.setAuditLogMonths(0);

        assertThat(validator.validate(none)).extracting(violation -> violation.getPropertyPath().toString())
                .containsExactlyInAnyOrder("emailLogDays", "auditLogMonths");
        assertThat(validator.validate(new AppProperties.Retention())).isEmpty();
    }

    @Test
    void theSameShapeAlertDefaultsToThreeLinksOnThreeDomainsInADay() throws IOException {
        final var campaign = new AppProperties().getCampaign();
        final var yaml = new YamlPropertySourceLoader().load("application", new ClassPathResource("application.yaml"));

        assertThat(campaign.isEnabled()).isTrue();
        assertThat(campaign.getLinks()).isEqualTo(3);
        assertThat(campaign.getDomains()).isEqualTo(3);
        assertThat(campaign.getWindow()).isEqualTo(Duration.ofHours(24));
        assertThat(yaml.getFirst().getProperty("app.campaign.enabled")).isEqualTo("${APP_CAMPAIGN_ENABLED:true}");
        assertThat(yaml.getFirst().getProperty("app.campaign.links")).isEqualTo("${APP_CAMPAIGN_LINKS:3}");
        assertThat(yaml.getFirst().getProperty("app.campaign.domains")).isEqualTo("${APP_CAMPAIGN_DOMAINS:3}");
        assertThat(yaml.getFirst().getProperty("app.campaign.window")).isEqualTo("${APP_CAMPAIGN_WINDOW:24h}");
    }

    @Test
    void theSameShapeAlertBindsFromTheEnvironment() {
        final var environment = new SystemEnvironmentPropertySource(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME, Map.<String, Object>of(
                "APP_CAMPAIGN_ENABLED", "false",
                "APP_CAMPAIGN_LINKS", "4",
                "APP_CAMPAIGN_DOMAINS", "2",
                "APP_CAMPAIGN_WINDOW", "6h"));

        final var campaign = new Binder(ConfigurationPropertySources.from(environment)).bind("app.campaign", AppProperties.Campaign.class).get();

        assertThat(campaign.isEnabled()).isFalse();
        assertThat(campaign.getLinks()).isEqualTo(4);
        assertThat(campaign.getDomains()).isEqualTo(2);
        assertThat(campaign.getWindow()).isEqualTo(Duration.ofHours(6));
    }

    @Test
    void sameShapeThresholdsBelowTheirMinimumsAreRefused() {
        final var validator = Validation.buildDefaultValidatorFactory().getValidator();
        final var tooLow = new AppProperties.Campaign();
        tooLow.setLinks(1);
        tooLow.setDomains(1);
        tooLow.setWindow(Duration.ofMinutes(59));

        assertThat(validator.validate(tooLow)).extracting(violation -> violation.getPropertyPath().toString())
                .containsExactlyInAnyOrder("links", "domains", "window");
        assertThat(validator.validate(new AppProperties.Campaign())).isEmpty();
    }

    @Test
    void trafficAlertsDefaultTo25ClicksAndHalfOfTheDayFrom100Clicks() throws IOException {
        final var trafficAlerts = new AppProperties().getTrafficAlerts();
        final var yaml = new YamlPropertySourceLoader().load("application", new ClassPathResource("application.yaml"));

        assertThat(trafficAlerts.isEnabled()).isTrue();
        assertThat(trafficAlerts.getLinkClicks()).isEqualTo(25);
        assertThat(trafficAlerts.getSharePercent()).isEqualTo(50);
        assertThat(trafficAlerts.getMinAnonymousClicks()).isEqualTo(100);
        assertThat(yaml.getFirst().getProperty("app.traffic-alerts.enabled")).isEqualTo("${APP_TRAFFIC_ALERTS_ENABLED:true}");
        assertThat(yaml.getFirst().getProperty("app.traffic-alerts.link-clicks")).isEqualTo(25);
        assertThat(yaml.getFirst().getProperty("app.traffic-alerts.share-percent")).isEqualTo(50);
        assertThat(yaml.getFirst().getProperty("app.traffic-alerts.min-anonymous-clicks")).isEqualTo(100);
    }

    @Test
    void trafficAlertsTurnOffFromTheEnvironment() {
        final var environment = new SystemEnvironmentPropertySource(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME, Map.<String, Object>of(
                "APP_TRAFFIC_ALERTS_ENABLED", "false"));

        final var trafficAlerts = new Binder(ConfigurationPropertySources.from(environment))
                .bind("app.traffic-alerts", AppProperties.TrafficAlerts.class).get();

        assertThat(trafficAlerts.isEnabled()).isFalse();
    }

    @Test
    void trafficAlertThresholdsOutsideTheirBoundsAreRefused() {
        final var validator = Validation.buildDefaultValidatorFactory().getValidator();
        final var tooLow = new AppProperties.TrafficAlerts();
        tooLow.setLinkClicks(4);
        tooLow.setSharePercent(9);
        tooLow.setMinAnonymousClicks(9);
        final var tooHigh = new AppProperties.TrafficAlerts();
        tooHigh.setSharePercent(100);

        assertThat(validator.validate(tooLow)).extracting(violation -> violation.getPropertyPath().toString())
                .containsExactlyInAnyOrder("linkClicks", "sharePercent", "minAnonymousClicks");
        assertThat(validator.validate(tooHigh)).extracting(violation -> violation.getPropertyPath().toString()).containsExactly("sharePercent");
        assertThat(validator.validate(new AppProperties.TrafficAlerts())).isEmpty();
    }

    @Test
    void burstDefaultsToFiveLinksInAnHour() {
        final var burst = new AppProperties().getBurst();

        assertThat(burst.getLinks()).isEqualTo(5);
        assertThat(burst.getWindow()).isEqualTo(Duration.ofHours(1));
    }

    @Test
    void aBurstThresholdBelowTwoIsRefused() {
        final var validator = Validation.buildDefaultValidatorFactory().getValidator();
        final var one = new AppProperties.Burst();
        one.setLinks(1);
        final var two = new AppProperties.Burst();
        two.setLinks(2);
        final var noWindow = new AppProperties.Burst();
        noWindow.setWindow(null);

        assertThat(validator.validate(one)).extracting(violation -> violation.getPropertyPath().toString()).containsExactly("links");
        assertThat(validator.validate(two)).isEmpty();
        assertThat(validator.validate(noWindow)).extracting(violation -> violation.getPropertyPath().toString()).containsExactly("window");
    }

    @ParameterizedTest
    @ValueSource(strings = {"PT0S", "PT-1S", "PT-1H", "PT0.0005S", "PT0.5S", "PT0.999S"})
    void aBurstWindowUnderOneSecondIsRefused(final String window) {
        final var burst = new AppProperties.Burst();
        burst.setWindow(Duration.parse(window));

        assertThat(Validation.buildDefaultValidatorFactory().getValidator().validate(burst))
                .extracting(violation -> violation.getPropertyPath() + " " + violation.getMessage())
                .containsExactly("window must be at least 1 second");
    }

    @Test
    void aBurstWindowOfOneSecondIsAllowed() {
        final var burst = new AppProperties.Burst();
        burst.setWindow(Duration.ofSeconds(1));

        assertThat(Validation.buildDefaultValidatorFactory().getValidator().validate(burst)).isEmpty();
    }

    @Test
    void aZeroBurstWindowStopsTheBindingWithAClearMessage() {
        final var source = new MapConfigurationPropertySource(Map.of("app.burst.links", "5", "app.burst.window", "0s"));
        final var validator = new SpringValidatorAdapter(Validation.buildDefaultValidatorFactory().getValidator());

        assertThatThrownBy(() -> new Binder(source).bind("app.burst", Bindable.of(AppProperties.Burst.class), new ValidationBindHandler(validator)))
                .isInstanceOf(BindException.class)
                .rootCause()
                .isInstanceOf(BindValidationException.class)
                .hasMessageContaining("app.burst.window")
                .hasMessageContaining("must be at least 1 second");
    }

    @Test
    void theBurstSettingsAreValidatedWithTheRestOfTheApp() {
        final var appProperties = new AppProperties();
        appProperties.getBurst().setLinks(1);
        appProperties.getBurst().setWindow(Duration.ZERO);

        assertThat(Validation.buildDefaultValidatorFactory().getValidator().validate(appProperties))
                .extracting(ConstraintViolation::getPropertyPath).map(Object::toString).contains("burst.links", "burst.window");
    }

    @Test
    void burstSettingsBindFromTheEnvironment() {
        final var environment = new SystemEnvironmentPropertySource(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME, Map.<String, Object>of(
                "APP_BURST_LINKS", "8",
                "APP_BURST_WINDOW", "30m"));

        final var burst = new Binder(ConfigurationPropertySources.from(environment)).bind("app.burst", AppProperties.Burst.class).get();

        assertThat(burst.getLinks()).isEqualTo(8);
        assertThat(burst.getWindow()).isEqualTo(Duration.ofMinutes(30));
    }

    @Test
    void theApplicationYamlSetsFiveLinksInAnHour() throws IOException {
        final var yaml = new YamlPropertySourceLoader().load("application", new ClassPathResource("application.yaml"));

        assertThat(yaml.getFirst().getProperty("app.burst.links")).isEqualTo(5);
        assertThat(yaml.getFirst().getProperty("app.burst.window")).isEqualTo("1h");
    }

    @Test
    void analyticsZoneBindsFromText() {
        final var binder = new Binder(new MapConfigurationPropertySource(Map.of("app.analytics.zone", "UTC")));

        assertThat(binder.bind("app.analytics", AppProperties.Analytics.class).get().getZone()).isEqualTo(ZoneId.of("UTC"));
    }

    @Test
    void cloudflareTokenIsNotInToString() {
        final var appProperties = new AppProperties();
        appProperties.getCloudflare().setApiToken(KEY_MATERIAL);
        appProperties.getCloudflare().setZoneId("zone-for-the-test");

        assertThat(appProperties.getCloudflare().toString()).contains("zone-for-the-test").doesNotContain(KEY_MATERIAL);
        assertThat(appProperties.toString()).doesNotContain(KEY_MATERIAL);
    }

    @Test
    void customDomainSettingsHaveTheirDefaults() {
        final var domains = new AppProperties().getDomains();
        final var cloudflare = new AppProperties().getCloudflare();

        assertThat(domains.isEnabled()).isTrue();
        assertThat(domains.getMaxActive()).isEqualTo(90);
        assertThat(domains.getFreeAllowance()).isEqualTo(100);
        assertThat(domains.getPerUser()).isEqualTo(1);
        assertThat(domains.getReserved()).containsExactly("hrva.cc", "hrvalabs.net");
        assertThat(domains.getPrimaryHosts()).containsExactly("hrva.cc", "domains.hrva.cc");
        assertThat(domains.getIgnoredHostnames()).containsExactly("test.karlo.cc");
        assertThat(domains.getCnameTarget()).isEqualTo("domains.hrva.cc");
        assertThat(cloudflare.getApiToken()).isNull();
        assertThat(cloudflare.getZoneId()).isNull();
        assertThat(cloudflare.getApiBase()).isEqualTo("https://api.cloudflare.com/client/v4");
    }

    @Test
    void theApplicationYamlReadsTheCustomDomainSettingsFromTheEnvironment() throws IOException {
        final var yaml = new YamlPropertySourceLoader().load("application", new ClassPathResource("application.yaml")).getFirst();

        assertThat(yaml.getProperty("app.domains.enabled")).isEqualTo("${APP_DOMAINS_ENABLED:true}");
        assertThat(yaml.getProperty("app.domains.max-active")).isEqualTo("${APP_DOMAINS_MAX_ACTIVE:90}");
        assertThat(yaml.getProperty("app.domains.free-allowance")).isEqualTo("${APP_DOMAINS_FREE_ALLOWANCE:100}");
        assertThat(yaml.getProperty("app.domains.per-user")).isEqualTo("${APP_DOMAINS_PER_USER:1}");
        assertThat(yaml.getProperty("app.domains.reserved")).isEqualTo("${APP_DOMAINS_RESERVED:hrva.cc,hrvalabs.net}");
        assertThat(yaml.getProperty("app.domains.primary-hosts")).isEqualTo("${APP_DOMAINS_PRIMARY_HOSTS:hrva.cc,domains.hrva.cc}");
        assertThat(yaml.getProperty("app.domains.ignored-hostnames")).isEqualTo("${APP_DOMAINS_IGNORED_HOSTNAMES:test.karlo.cc}");
        assertThat(yaml.getProperty("app.domains.cname-target")).isEqualTo("${APP_DOMAINS_CNAME_TARGET:domains.hrva.cc}");
        assertThat(yaml.getProperty("app.cloudflare.api-token")).isEqualTo("${CLOUDFLARE_API_TOKEN:}");
        assertThat(yaml.getProperty("app.cloudflare.zone-id")).isEqualTo("${CLOUDFLARE_ZONE_ID:}");
        assertThat(yaml.getProperty("app.cloudflare.api-base")).isEqualTo("${APP_CLOUDFLARE_API_BASE:https://api.cloudflare.com/client/v4}");
    }

    @Test
    void customDomainListsBindFromTheEnvironment() {
        final var environment = new SystemEnvironmentPropertySource(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME, Map.<String, Object>of(
                "APP_DOMAINS_PRIMARY_HOSTS", "hrva.cc,domains.hrva.cc,www.hrva.cc",
                "APP_DOMAINS_IGNORED_HOSTNAMES", "test.karlo.cc,old.karlo.cc",
                "APP_DOMAINS_RESERVED", "hrva.cc,hrvalabs.net,karlo.cc",
                "APP_DOMAINS_MAX_ACTIVE", "40"));

        final var domains = new Binder(ConfigurationPropertySources.from(environment)).bind("app.domains", AppProperties.Domains.class).get();

        assertThat(domains.getPrimaryHosts()).containsExactly("hrva.cc", "domains.hrva.cc", "www.hrva.cc");
        assertThat(domains.getIgnoredHostnames()).containsExactly("test.karlo.cc", "old.karlo.cc");
        assertThat(domains.getReserved()).containsExactly("hrva.cc", "hrvalabs.net", "karlo.cc");
        assertThat(domains.getMaxActive()).isEqualTo(40);
    }

    @Test
    void anEmptyIgnoredListBindsAsEmpty() {
        final var domains = new Binder(new MapConfigurationPropertySource(Map.of("app.domains.ignored-hostnames", "")))
                .bind("app.domains", AppProperties.Domains.class).get();

        assertThat(domains.getIgnoredHostnames()).isEmpty();
    }

    @Test
    void aCustomDomainNumberBelowOneIsRefused() {
        final var validator = Validation.buildDefaultValidatorFactory().getValidator();
        final var none = new AppProperties.Domains();
        none.setMaxActive(0);
        none.setFreeAllowance(0);
        none.setPerUser(0);

        assertThat(validator.validate(none)).extracting(violation -> violation.getPropertyPath().toString())
                .containsExactlyInAnyOrder("maxActive", "freeAllowance", "perUser");
        assertThat(validator.validate(new AppProperties.Domains())).isEmpty();
        assertThat(validator.validate(new AppProperties())).extracting(violation -> violation.getPropertyPath().toString())
                .noneMatch(path -> path.startsWith("domains") || path.startsWith("cloudflare"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " "})
    void aBlankCnameTargetOrCloudflareApiBaseIsRefused(final String blank) {
        final var validator = Validation.buildDefaultValidatorFactory().getValidator();
        final var domains = new AppProperties.Domains();
        domains.setCnameTarget(blank);
        final var cloudflare = new AppProperties.Cloudflare();
        cloudflare.setApiBase(blank);

        assertThat(validator.validate(domains)).extracting(violation -> violation.getPropertyPath().toString()).containsExactly("cnameTarget");
        assertThat(validator.validate(cloudflare)).extracting(violation -> violation.getPropertyPath().toString()).containsExactly("apiBase");
        assertThat(validator.validate(new AppProperties.Cloudflare())).isEmpty();
    }

    @Test
    void abuseReportsDefaultToTheOwnerApwgAndThirtyDays() {
        final var abuseReports = new AppProperties().getAbuseReports();

        assertThat(abuseReports.getReporterName()).isEqualTo(new String(new int[] {'K', 'a', 'r', 'l', 'o', ' ', 'H', 'r', 'v', 'a', 0x10D, 'i', 0x107}, 0, 13));
        assertThat(abuseReports.getReporterAddress()).isEqualTo("abuse@hrva.cc");
        assertThat(abuseReports.getCc()).containsExactly("reportphishing@apwg.org");
        assertThat(abuseReports.getRecheckDays()).isEqualTo(30);
    }

    @Test
    void abuseReportDefaultsOutsideTheirSettingLimitsAreRefused() {
        final var validator = Validation.buildDefaultValidatorFactory().getValidator();
        final var abuseReports = new AppProperties.AbuseReports();
        abuseReports.setReporterName(" ");
        abuseReports.setReporterAddress("");
        abuseReports.setCc(List.of("a@x.hr", "b@x.hr", "c@x.hr", "d@x.hr", "e@x.hr", "f@x.hr"));
        abuseReports.setRecheckDays(91);

        assertThat(validator.validate(abuseReports)).extracting(violation -> violation.getPropertyPath().toString())
                .containsExactlyInAnyOrder("reporterName", "reporterAddress", "cc", "recheckDays");
        assertThat(validator.validate(new AppProperties.AbuseReports())).isEmpty();
    }


}
