package cc.hrva.urlshortener.service;

import cc.hrva.urlshortener.configuration.properties.AppProperties;
import cc.hrva.urlshortener.dto.settings.PublicSettingsResponse;
import cc.hrva.urlshortener.dto.settings.SettingResponse;
import cc.hrva.urlshortener.exception.FieldValidationException;
import cc.hrva.urlshortener.exception.SettingNotFoundException;
import cc.hrva.urlshortener.model.AppSetting;
import cc.hrva.urlshortener.model.enums.AuditAction;
import cc.hrva.urlshortener.repository.AppSettingRepository;
import cc.hrva.urlshortener.service.alert.AdminAlertEvent.SettingChanged;
import cc.hrva.urlshortener.service.impl.DefaultSettingsService;
import cc.hrva.urlshortener.service.notification.MutableClock;
import cc.hrva.urlshortener.service.settings.EmailKind;
import cc.hrva.urlshortener.service.settings.SettingDefaults;
import cc.hrva.urlshortener.service.settings.SettingKey;
import cc.hrva.urlshortener.service.settings.SettingType;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionOperations;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.JsonNodeFactory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith({MockitoExtension.class, OutputCaptureExtension.class})
class DefaultSettingsServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-30T09:15:30Z");
    private static final LocalDateTime EARLIER = LocalDateTime.of(2026, 10, 29, 8, 0, 0);
    private static final String ADMIN = "karlo@hrva.cc";
    private static final String OTHER_ADMIN = "ana@hrva.cc";
    private static final JsonNodeFactory NODES = JsonNodeFactory.instance;

    @Mock
    private AppSettingRepository appSettingRepository;

    @Mock
    private AuditLogService auditLogService;

    @Mock
    private ApplicationEventPublisher eventPublisher;

    private final AppProperties appProperties = new AppProperties();
    private final MutableClock clock = new MutableClock(NOW);
    private final Map<String, AppSetting> rows = new LinkedHashMap<>();
    private final List<String> steps = new ArrayList<>();
    private boolean inTransaction;
    private DefaultSettingsService settingsService;

    @BeforeEach
    void setUp() {
        lenient().when(appSettingRepository.findAll()).thenAnswer(invocation -> List.copyOf(rows.values()));
        lenient().when(appSettingRepository.findById(anyString())).thenAnswer(invocation -> Optional.ofNullable(rows.get(invocation.<String>getArgument(0))));
        lenient().when(appSettingRepository.save(any(AppSetting.class))).thenAnswer(invocation -> {
            final AppSetting row = invocation.getArgument(0);
            steps.add("save " + row.getSettingKey() + (inTransaction ? " in a transaction" : " outside a transaction"));
            rows.put(row.getSettingKey(), row);
            return row;
        });
        lenient().doAnswer(invocation -> {
            steps.add("delete " + invocation.getArgument(0) + (inTransaction ? " in a transaction" : " outside a transaction"));
            rows.remove(invocation.<String>getArgument(0));
            return null;
        }).when(appSettingRepository).deleteById(anyString());
        lenient().doAnswer(invocation -> steps.add("audit" + (inTransaction ? " in a transaction" : ""))).when(auditLogService)
                .log(anyString(), any(AuditAction.class), anyString(), anyString(), anyString());
        lenient().doAnswer(invocation -> steps.add("publish" + (inTransaction ? " in a transaction" : ""))).when(eventPublisher).publishEvent(any(Object.class));
        settingsService = new DefaultSettingsService(appSettingRepository, new SettingDefaults(appProperties), auditLogService, eventPublisher,
                recordingTransactions(), clock);
    }

    @Test
    void defaultsComeFromConfiguration() {
        appProperties.setAnonymousUrlCreationEnabled(false);
        appProperties.getCampaign().setWindow(Duration.ofHours(12));

        assertThat(settingsService.isOn(SettingKey.ANONYMOUS_LINKS)).isFalse();
        assertThat(settingsService.number(SettingKey.CAMPAIGN_WINDOW_HOURS)).isEqualTo(12);
        assertThat(settingsService.number(SettingKey.CAMPAIGN_LINKS)).isEqualTo(3);
        assertThat(settingsService.number(SettingKey.ANONYMOUS_LINKS_PER_HOUR)).isEqualTo(10);
        assertThat(settingsService.isOn(SettingKey.SEND_EMAILS)).isTrue();
        assertThat(settingsService.isOn(SettingKey.EMAIL_CUSTOM_DOMAIN)).isTrue();
    }

    @Test
    void thePublicSettingsFollowTheThreeSwitches() {
        assertThat(settingsService.publicSettings()).isEqualTo(new PublicSettingsResponse(true, true, true));

        rows.put("ANONYMOUS_LINKS", row(SettingKey.ANONYMOUS_LINKS, "false"));
        rows.put("CUSTOM_ALIASES", row(SettingKey.CUSTOM_ALIASES, "false"));
        settingsService.refresh();

        assertThat(settingsService.publicSettings()).isEqualTo(new PublicSettingsResponse(false, true, false));
    }

    @Test
    void theCampaignWindowDefaultIsClampedToItsRange() {
        appProperties.getCampaign().setWindow(Duration.ofHours(200));
        assertThat(settingsService.number(SettingKey.CAMPAIGN_WINDOW_HOURS)).isEqualTo(168);

        appProperties.getCampaign().setWindow(Duration.ofMinutes(90));
        assertThat(settingsService.number(SettingKey.CAMPAIGN_WINDOW_HOURS)).isEqualTo(1);
    }

    @Test
    void aRowOverridesTheDefault() {
        rows.put("ANONYMOUS_LINKS", row(SettingKey.ANONYMOUS_LINKS, "false"));
        rows.put("CAMPAIGN_LINKS", row(SettingKey.CAMPAIGN_LINKS, "5"));

        assertThat(settingsService.isOn(SettingKey.ANONYMOUS_LINKS)).isFalse();
        assertThat(settingsService.number(SettingKey.CAMPAIGN_LINKS)).isEqualTo(5);
        assertThat(response(SettingKey.CAMPAIGN_LINKS)).satisfies(setting -> {
            assertThat(setting.value()).isEqualTo(5);
            assertThat(setting.defaultValue()).isEqualTo(3);
            assertThat(setting.changed()).isTrue();
            assertThat(setting.changedBy()).isEqualTo(OTHER_ADMIN);
            assertThat(setting.changedAt()).isEqualTo(EARLIER);
        });
    }

    @Test
    void aReadBeforeTheFirstLoadLoadsOnce() {
        rows.put("CAMPAIGN_LINKS", row(SettingKey.CAMPAIGN_LINKS, "5"));

        assertThat(settingsService.number(SettingKey.CAMPAIGN_LINKS)).isEqualTo(5);
        assertThat(settingsService.number(SettingKey.CAMPAIGN_LINKS)).isEqualTo(5);

        verify(appSettingRepository, times(1)).findAll();
    }

    @Test
    void changeWritesARowAuditsAndPostsOneDiscordLine() {
        final var changed = settingsService.change(SettingKey.ANONYMOUS_LINKS, NODES.booleanNode(false), ADMIN);

        assertThat(rows.get("ANONYMOUS_LINKS")).satisfies(row -> {
            assertThat(row.getSettingValue()).isEqualTo("false");
            assertThat(row.getChangedBy()).isEqualTo(ADMIN);
            assertThat(row.getChangedAt()).isEqualTo(LocalDateTime.of(2026, 10, 30, 9, 15, 30));
        });
        assertThat(steps).containsExactly("begin", "save ANONYMOUS_LINKS in a transaction", "commit", "audit", "publish");
        verify(auditLogService).log(ADMIN, AuditAction.CHANGE_SETTING, "Setting", "ANONYMOUS_LINKS", "from: On, to: Off");
        verify(eventPublisher).publishEvent(new SettingChanged("Links without an account", "turned off", ADMIN));
        assertThat(settingsService.isOn(SettingKey.ANONYMOUS_LINKS)).isFalse();
        assertThat(changed.value()).isEqualTo(false);
        assertThat(changed.defaultValue()).isEqualTo(true);
        assertThat(changed.changed()).isTrue();
        assertThat(changed.changedBy()).isEqualTo(ADMIN);
    }

    @Test
    void aChangedNumberSaysBothValues() {
        settingsService.change(SettingKey.TRAFFIC_LINK_CLICKS, NODES.numberNode(1500), ADMIN);

        assertThat(rows.get("TRAFFIC_LINK_CLICKS").getSettingValue()).isEqualTo("1500");
        verify(auditLogService).log(ADMIN, AuditAction.CHANGE_SETTING, "Setting", "TRAFFIC_LINK_CLICKS", "from: 25, to: 1,500");
        verify(eventPublisher).publishEvent(new SettingChanged("Traffic alerts: clicks per link per day", "changed from 25 to 1,500", ADMIN));
        assertThat(settingsService.number(SettingKey.TRAFFIC_LINK_CLICKS)).isEqualTo(1500);
    }

    @Test
    void aChangedCcIsPostedWithItsAddressesAndReadBack() {
        settingsService.change(SettingKey.ABUSE_CC, NODES.arrayNode().add("cert@cert.pt").add("reportphishing@apwg.org"), ADMIN);

        assertThat(rows.get("ABUSE_CC").getSettingValue()).isEqualTo("[\"cert@cert.pt\",\"reportphishing@apwg.org\"]");
        verify(auditLogService).log(ADMIN, AuditAction.CHANGE_SETTING, "Setting", "ABUSE_CC",
                "from: reportphishing@apwg.org, to: cert@cert.pt, reportphishing@apwg.org");
        verify(eventPublisher).publishEvent(new SettingChanged("Abuse reports: Cc",
                "changed from reportphishing@apwg.org to cert@cert.pt, reportphishing@apwg.org", ADMIN, true));
        assertThat(settingsService.addresses(SettingKey.ABUSE_CC)).containsExactly("cert@cert.pt", "reportphishing@apwg.org");
    }

    @Test
    void aChangedReporterNameIsReadBackAndPostedAsPlainText() {
        settingsService.change(SettingKey.ABUSE_REPORTER_NAME, NODES.stringNode(" Karlo "), ADMIN);

        assertThat(rows.get("ABUSE_REPORTER_NAME").getSettingValue()).isEqualTo("\"Karlo\"");
        verify(eventPublisher).publishEvent(new SettingChanged("Abuse reports: your name",
                "changed from " + appProperties.getAbuseReports().getReporterName() + " to Karlo", ADMIN, false));
        assertThat(settingsService.text(SettingKey.ABUSE_REPORTER_NAME)).isEqualTo("Karlo");
        assertThat(settingsService.addresses(SettingKey.ABUSE_REPORTER_ADDRESS)).containsExactly("abuse@hrva.cc");
    }

    @Test
    void aValueEqualToTheDefaultRemovesTheRow() {
        rows.put("CAMPAIGN_LINKS", row(SettingKey.CAMPAIGN_LINKS, "5"));

        final var changed = settingsService.change(SettingKey.CAMPAIGN_LINKS, NODES.numberNode(3), ADMIN);

        assertThat(rows).doesNotContainKey("CAMPAIGN_LINKS");
        assertThat(steps).containsExactly("begin", "delete CAMPAIGN_LINKS in a transaction", "commit", "audit", "publish");
        verify(appSettingRepository, never()).save(any());
        verify(auditLogService).log(ADMIN, AuditAction.CHANGE_SETTING, "Setting", "CAMPAIGN_LINKS", "from: 5, to: 3");
        verify(eventPublisher).publishEvent(new SettingChanged("Same-shape alert: links", "changed from 5 to 3", ADMIN));
        assertThat(changed.value()).isEqualTo(3);
        assertThat(changed.changed()).isFalse();
        assertThat(changed.changedBy()).isNull();
        assertThat(changed.changedAt()).isNull();
    }

    @Test
    void aValueEqualToTheDefaultStillRemovesAnUnreadableRow(final CapturedOutput output) {
        rows.put("CAMPAIGN_LINKS", row(SettingKey.CAMPAIGN_LINKS, "99"));
        settingsService.refresh();

        final var changed = settingsService.change(SettingKey.CAMPAIGN_LINKS, NODES.numberNode(3), ADMIN);
        settingsService.refresh();

        assertThat(rows).doesNotContainKey("CAMPAIGN_LINKS");
        assertThat(steps).containsExactly("begin", "delete CAMPAIGN_LINKS in a transaction", "commit", "audit", "publish");
        verify(auditLogService).log(ADMIN, AuditAction.RESET_SETTING, "Setting", "CAMPAIGN_LINKS", "from: 3, to: 3");
        verify(eventPublisher).publishEvent(new SettingChanged("Same-shape alert: links", "reset to its default (3)", ADMIN));
        assertThat(changed.value()).isEqualTo(3);
        assertThat(changed.changed()).isFalse();
        assertThat(output.getOut().lines().filter(line -> line.contains("key=CAMPAIGN_LINKS"))).hasSizeLessThanOrEqualTo(2);
        final var warningsSoFar = output.getOut().lines().filter(line -> line.contains("key=CAMPAIGN_LINKS")).count();
        settingsService.refresh();
        assertThat(output.getOut().lines().filter(line -> line.contains("key=CAMPAIGN_LINKS"))).hasSize((int) warningsSoFar);
    }

    @Test
    void anUnchangedValueDoesNothing() {
        settingsService.change(SettingKey.CAMPAIGN_LINKS, NODES.numberNode(3), ADMIN);
        rows.put("TRAFFIC_ALERTS", row(SettingKey.TRAFFIC_ALERTS, "false"));
        settingsService.refresh();
        final var unchanged = settingsService.change(SettingKey.TRAFFIC_ALERTS, NODES.booleanNode(false), ADMIN);

        assertThat(rows).containsOnlyKeys("TRAFFIC_ALERTS");
        assertThat(rows.get("TRAFFIC_ALERTS").getChangedBy()).isEqualTo(OTHER_ADMIN);
        verify(appSettingRepository, never()).save(any());
        verify(appSettingRepository, never()).deleteById(anyString());
        verifyNoInteractions(auditLogService, eventPublisher);
        assertThat(unchanged.changedBy()).isEqualTo(OTHER_ADMIN);
    }

    @Test
    void anInvalidValueIsRefusedBeforeAnythingIsWritten() {
        assertThatThrownBy(() -> settingsService.change(SettingKey.CAMPAIGN_LINKS, NODES.numberNode(99), ADMIN))
                .isInstanceOfSatisfying(FieldValidationException.class,
                        refusal -> assertThat(refusal.getFieldErrors()).isEqualTo(Map.of("value", "Use a number from 2 to 50.")));

        assertThat(steps).isEmpty();
        verifyNoInteractions(auditLogService, eventPublisher);
    }

    @Test
    void resetDeletesTheRowAndSaysTheDefault() {
        appProperties.setAnonymousUrlCreationEnabled(false);
        rows.put("ANONYMOUS_LINKS", row(SettingKey.ANONYMOUS_LINKS, "true"));

        final var reset = settingsService.reset(SettingKey.ANONYMOUS_LINKS, ADMIN);

        assertThat(rows).isEmpty();
        assertThat(steps).containsExactly("begin", "delete ANONYMOUS_LINKS in a transaction", "commit", "audit", "publish");
        verify(auditLogService).log(ADMIN, AuditAction.RESET_SETTING, "Setting", "ANONYMOUS_LINKS", "from: On, to: Off");
        verify(eventPublisher).publishEvent(new SettingChanged("Links without an account", "reset to its default (Off)", ADMIN));
        assertThat(settingsService.isOn(SettingKey.ANONYMOUS_LINKS)).isFalse();
        assertThat(reset.value()).isEqualTo(false);
        assertThat(reset.changed()).isFalse();
    }

    @Test
    void resetWithoutARowDoesNothing() {
        final var reset = settingsService.reset(SettingKey.ANONYMOUS_LINKS, ADMIN);

        verify(appSettingRepository, never()).deleteById(anyString());
        verifyNoInteractions(auditLogService, eventPublisher);
        assertThat(reset.value()).isEqualTo(true);
    }

    @ParameterizedTest
    @ValueSource(strings = {"99", "abc", "2.5", "true"})
    void aStoredValueThatNoLongerFitsReadsAsTheDefaultAndShowsAsChangedSoItCanBeReset(final String stored, final CapturedOutput output) {
        rows.put("CAMPAIGN_LINKS", row(SettingKey.CAMPAIGN_LINKS, stored));

        settingsService.refresh();
        settingsService.refresh();

        assertThat(settingsService.number(SettingKey.CAMPAIGN_LINKS)).isEqualTo(3);
        final var setting = response(SettingKey.CAMPAIGN_LINKS);
        assertThat(setting.value()).isEqualTo(3);
        assertThat(setting.changed()).isTrue();
        assertThat(setting.changedBy()).isEqualTo(OTHER_ADMIN);
        assertThat(setting.changedAt()).isEqualTo(EARLIER);
        assertThat(output.getOut().lines().filter(line -> line.contains("WARN") && line.contains("key=CAMPAIGN_LINKS"))).hasSize(2);
        assertThat(output.getOut()).doesNotContain("\tat ");
    }

    @Test
    void aFailedRefreshKeepsTheLastValues(final CapturedOutput output) {
        rows.put("CAMPAIGN_LINKS", row(SettingKey.CAMPAIGN_LINKS, "5"));
        settingsService.refresh();
        when(appSettingRepository.findAll()).thenThrow(new DataAccessResourceFailureException("database down"));

        assertThatCode(settingsService::refresh).doesNotThrowAnyException();

        assertThat(settingsService.number(SettingKey.CAMPAIGN_LINKS)).isEqualTo(5);
        assertThat(output.getOut().lines().filter(line -> line.contains("WARN") && line.contains("Could not load the settings"))).hasSize(1);
    }

    @Test
    void theFirstLoadAtStartupCatchesAndLogsOneWarning(final CapturedOutput output) throws NoSuchMethodException {
        rows.put("CAMPAIGN_LINKS", row(SettingKey.CAMPAIGN_LINKS, "5"));
        when(appSettingRepository.findAll()).thenThrow(new DataAccessResourceFailureException("Table \"APP_SETTING\" not found"));

        assertThatCode(settingsService::loadAtStartup).doesNotThrowAnyException();

        assertThat(DefaultSettingsService.class.getMethod("loadAtStartup").getAnnotation(EventListener.class).value())
                .containsExactly(ApplicationReadyEvent.class);
        assertThat(output.getOut().lines().filter(line -> line.contains("WARN"))).hasSize(1)
                .allSatisfy(line -> assertThat(line).contains("Could not load the settings").contains("DataAccessResourceFailureException"));
        assertThat(output.getOut()).doesNotContain("\tat ");
        assertThat(settingsService.number(SettingKey.CAMPAIGN_LINKS)).isEqualTo(3);
        assertThat(settingsService.isOn(SettingKey.ANONYMOUS_LINKS)).isTrue();
        verify(appSettingRepository, times(1)).findAll();
    }

    @Test
    void aRowThatFailsToReadWarnsForItsKeyAndTheOthersStillLoad(final CapturedOutput output) {
        final var broken = mock(AppSetting.class);
        when(broken.getSettingKey()).thenReturn("CAMPAIGN_LINKS");
        when(broken.getSettingValue()).thenThrow(new IllegalStateException("No setting of type TEXT can be changed yet"));
        rows.put("CAMPAIGN_LINKS", broken);
        rows.put("TRAFFIC_LINK_CLICKS", row(SettingKey.TRAFFIC_LINK_CLICKS, "30"));

        settingsService.refresh();

        assertThat(settingsService.number(SettingKey.TRAFFIC_LINK_CLICKS)).isEqualTo(30);
        assertThat(settingsService.number(SettingKey.CAMPAIGN_LINKS)).isEqualTo(3);
        assertThat(output.getOut().lines().filter(line -> line.contains("WARN"))).hasSize(1)
                .allSatisfy(line -> assertThat(line).contains("key=CAMPAIGN_LINKS"));
    }

    @Test
    void theSettingsAreReloadedEvery30SecondsAfterTheStartupLoad() throws NoSuchMethodException {
        final var scheduled = DefaultSettingsService.class.getMethod("refresh").getAnnotation(Scheduled.class);

        assertThat(scheduled.fixedDelay()).isEqualTo(30_000);
        assertThat(scheduled.initialDelay()).isEqualTo(30_000);
    }

    @Test
    void emailSwitchOffNamesSendEmailsFirst() {
        rows.put("SEND_EMAILS", row(SettingKey.SEND_EMAILS, "false"));
        rows.put("EMAIL_LINK_BLOCKED", row(SettingKey.EMAIL_LINK_BLOCKED, "false"));
        settingsService.refresh();

        assertThat(settingsService.emailSwitchOff(EmailKind.LINK_BLOCKED)).contains(SettingKey.SEND_EMAILS);
        assertThat(settingsService.emailSwitchOff(EmailKind.MALWARE_FOUND)).contains(SettingKey.SEND_EMAILS);
        assertThat(settingsService.emailSwitchOff(EmailKind.ADMIN_WEEKLY_DIGEST)).contains(SettingKey.SEND_EMAILS);

        rows.remove("SEND_EMAILS");
        appProperties.getDigest().getWeekly().setEmail(false);
        settingsService.refresh();

        assertThat(settingsService.emailSwitchOff(EmailKind.LINK_BLOCKED)).contains(SettingKey.EMAIL_LINK_BLOCKED);
        assertThat(settingsService.emailSwitchOff(EmailKind.MALWARE_FOUND)).isEmpty();
        assertThat(settingsService.emailSwitchOff(EmailKind.ADMIN_WEEKLY_DIGEST)).contains(SettingKey.WEEKLY_DIGEST_EMAIL);
    }

    @Test
    void everyEmailKindHasItsOwnSwitch() {
        Arrays.stream(SettingKey.values()).filter(key -> key.name().startsWith("EMAIL_"))
                .forEach(key -> rows.put(key.name(), row(key, "false")));
        rows.put("WEEKLY_DIGEST_EMAIL", row(SettingKey.WEEKLY_DIGEST_EMAIL, "false"));
        settingsService.refresh();

        assertThat(Arrays.stream(EmailKind.values()).map(settingsService::emailSwitchOff).map(Optional::orElseThrow)).containsExactly(
                SettingKey.EMAIL_LINK_BLOCKED, SettingKey.EMAIL_MALWARE_FOUND, SettingKey.EMAIL_DEAD_LINKS, SettingKey.EMAIL_LINK_EXPIRING,
                SettingKey.EMAIL_WEEKLY_SUMMARY, SettingKey.EMAIL_ADMIN_DAILY, SettingKey.EMAIL_NEW_USER_NOTICE, SettingKey.WEEKLY_DIGEST_EMAIL,
                SettingKey.EMAIL_CUSTOM_DOMAIN);
    }

    @Test
    void safetySwitchesOffListsOnlySafetySwitches() {
        rows.put("ANONYMOUS_LINKS", row(SettingKey.ANONYMOUS_LINKS, "false"));
        rows.put("SEND_EMAILS", row(SettingKey.SEND_EMAILS, "false"));
        rows.put("NIGHTLY_SAFE_BROWSING", row(SettingKey.NIGHTLY_SAFE_BROWSING, "false"));
        rows.put("BLOCKLIST_CHECK", row(SettingKey.BLOCKLIST_CHECK, "false"));

        assertThat(settingsService.isOn(SettingKey.SAFE_BROWSING_FAIL_CLOSED)).isFalse();
        assertThat(settingsService.safetySwitchesOff()).containsExactly(SettingKey.BLOCKLIST_CHECK, SettingKey.NIGHTLY_SAFE_BROWSING);
    }

    @Test
    void theCampaignHoldIsListedWhileTheSameShapeAlertIsOn() {
        rows.put("CAMPAIGN_HOLD", row(SettingKey.CAMPAIGN_HOLD, "false"));
        settingsService.refresh();

        assertThat(settingsService.safetySwitchesOff()).containsExactly(SettingKey.CAMPAIGN_HOLD);

        rows.put("SAME_SHAPE_ALERT", row(SettingKey.SAME_SHAPE_ALERT, "false"));
        settingsService.refresh();

        assertThat(settingsService.safetySwitchesOff()).containsExactly(SettingKey.SAME_SHAPE_ALERT);
    }

    @Test
    void aSafetySwitchUnderASwitchThatIsOffIsNotListed() {
        Arrays.stream(SettingKey.values()).filter(key -> key.getType() == SettingType.SWITCH).forEach(key -> rows.put(key.name(), row(key, "false")));
        settingsService.refresh();

        assertThat(settingsService.safetySwitchesOff())
                .containsExactly(SettingKey.SAFE_BROWSING_CHECK, SettingKey.BLOCKLIST_CHECK, SettingKey.SUSPICIOUS_LINK_CHECK,
                        SettingKey.NIGHTLY_SAFE_BROWSING, SettingKey.NIGHTLY_DESTINATION_CHECK, SettingKey.SAME_SHAPE_ALERT)
                .allSatisfy(key -> assertThat(key.getParent() == null || settingsService.isOn(key.getParent())).isTrue());
    }

    @Test
    void listFollowsTheCatalogueAndTheContract() {
        final var settings = settingsService.list();

        assertThat(settings).extracting(SettingResponse::key).containsExactly(SettingKey.values());
        final var json = JsonMapper.builder().build().valueToTree(response(SettingKey.CAMPAIGN_LINKS));
        assertThat(json).isEqualTo(JsonMapper.builder().build().readTree("""
                {"key":"CAMPAIGN_LINKS","group":"ALERTS","type":"NUMBER","label":"Same-shape alert: links",
                 "description":"Links without an account that share one URL shape.","parent":"SAME_SHAPE_ALERT","safety":false,
                 "value":3,"defaultValue":3,"changed":false,"changedBy":null,"changedAt":null,
                 "min":2,"max":50,"nullable":false,"noneLabel":null,"unit":"links","available":true,"unavailableReason":null}
                """));
        assertThat(response(SettingKey.BLOCKLIST_CHECK)).satisfies(setting -> {
            assertThat(setting.safety()).isTrue();
            assertThat(setting.parent()).isNull();
            assertThat(setting.min()).isNull();
            assertThat(setting.unit()).isNull();
        });
    }

    @Test
    void aKeyIsFoundOnlyByItsExactName() {
        assertThat(settingsService.settingKey("CAMPAIGN_LINKS")).isEqualTo(SettingKey.CAMPAIGN_LINKS);
        assertThatThrownBy(() -> settingsService.settingKey("NOPE")).isInstanceOf(SettingNotFoundException.class)
                .hasMessage("There is no setting NOPE.");
        assertThatThrownBy(() -> settingsService.settingKey("campaign_links")).isInstanceOf(SettingNotFoundException.class)
                .hasMessage("There is no setting campaign_links.");
    }

    @Test
    void aReaderOfTheWrongTypeIsAProgrammingError() {
        assertThatThrownBy(() -> settingsService.isOn(SettingKey.CAMPAIGN_LINKS)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> settingsService.number(SettingKey.ANONYMOUS_LINKS)).isInstanceOf(IllegalArgumentException.class);
    }

    private SettingResponse response(final SettingKey key) {
        return settingsService.list().stream().filter(setting -> setting.key() == key).findFirst().orElseThrow();
    }

    private static AppSetting row(final SettingKey key, final String value) {
        return AppSetting.builder().settingKey(key.name()).settingValue(value).changedBy(OTHER_ADMIN).changedAt(EARLIER).build();
    }

    private TransactionOperations recordingTransactions() {
        return new TransactionOperations() {
            @Override
            public <T> T execute(final TransactionCallback<T> action) {
                steps.add("begin");
                inTransaction = true;
                try {
                    return action.doInTransaction(new SimpleTransactionStatus());
                } finally {
                    inTransaction = false;
                    steps.add("commit");
                }
            }
        };
    }

}
