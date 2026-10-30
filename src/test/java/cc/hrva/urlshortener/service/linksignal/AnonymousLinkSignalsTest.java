package cc.hrva.urlshortener.service.linksignal;

import cc.hrva.urlshortener.configuration.properties.AppProperties;
import cc.hrva.urlshortener.dto.LinkVerdict.Outcome;
import cc.hrva.urlshortener.service.DomainAgeService;
import cc.hrva.urlshortener.service.impl.DefaultSettingsService;
import cc.hrva.urlshortener.service.linksignal.AnonymousLinkSignals.Signals;
import cc.hrva.urlshortener.service.settings.SettingKey;
import cc.hrva.urlshortener.service.settings.SettingsServiceFixtures;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AnonymousLinkSignalsTest {

    private static final RandomLookingLabels LABELS = new RandomLookingLabels();
    private static final LocalDate TODAY = LocalDate.of(2026, 10, 30);
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-30T10:00:00Z"), ZoneOffset.UTC);
    private static final String RANDOM_LINK = "https://ll.xdvqnxj.com/wqamhcsom/?r=plfN8D";
    private static final String RANDOM_HOST = "ll.xdvqnxj.com";

    private final DefaultSettingsService settingsService = SettingsServiceFixtures.defaults(new AppProperties());

    @Mock
    private DomainAgeService domainAgeService;

    private AnonymousLinkSignals anonymousLinkSignals;

    @BeforeEach
    void setUp() {
        anonymousLinkSignals = new AnonymousLinkSignals(LABELS, domainAgeService, settingsService, CLOCK);
    }

    @Test
    void randomAndYoungIsFlagged() {
        when(domainAgeService.registeredOn(RANDOM_HOST)).thenReturn(Optional.of(TODAY.minusDays(10)));

        assertThat(anonymousLinkSignals.signalsFor(RANDOM_LINK, Outcome.CLEAN)).isEqualTo(new Signals(true, Optional.of(TODAY.minusDays(10)), true));
    }

    @Test
    void randomAndUnknownAgeIsFlagged() {
        when(domainAgeService.registeredOn(RANDOM_HOST)).thenReturn(Optional.empty());

        assertThat(anonymousLinkSignals.signalsFor(RANDOM_LINK, Outcome.FLAG)).isEqualTo(new Signals(true, Optional.empty(), true));
    }

    @Test
    void randomButOldIsNot() {
        when(domainAgeService.registeredOn(RANDOM_HOST)).thenReturn(Optional.of(TODAY.minusDays(400)));

        assertThat(anonymousLinkSignals.signalsFor(RANDOM_LINK, Outcome.CLEAN)).isEqualTo(new Signals(false, Optional.of(TODAY.minusDays(400)), false));
    }

    @Test
    void aDomainExactlyAsOldAsTheMaximumNoLongerCounts() {
        when(domainAgeService.registeredOn(RANDOM_HOST)).thenReturn(Optional.of(TODAY.minusDays(365)), Optional.of(TODAY.minusDays(364)));

        assertThat(anonymousLinkSignals.signalsFor(RANDOM_LINK, Outcome.CLEAN).randomLooking()).isFalse();
        assertThat(anonymousLinkSignals.signalsFor(RANDOM_LINK, Outcome.CLEAN).randomLooking()).isTrue();
    }

    @Test
    void theMaximumAgeComesFromSettings() {
        when(domainAgeService.registeredOn(RANDOM_HOST)).thenReturn(Optional.of(TODAY.minusDays(40)));
        assertThat(anonymousLinkSignals.signalsFor(RANDOM_LINK, Outcome.CLEAN).randomLooking()).isTrue();

        SettingsServiceFixtures.change(settingsService, SettingKey.RANDOM_DOMAIN_MAX_AGE_DAYS, 30);

        assertThat(anonymousLinkSignals.signalsFor(RANDOM_LINK, Outcome.CLEAN).randomLooking()).isFalse();
    }

    @Test
    void theReviewSwitchDoesNotChangeTheSignal() {
        when(domainAgeService.registeredOn(RANDOM_HOST)).thenReturn(Optional.empty());
        SettingsServiceFixtures.turnOff(settingsService, SettingKey.RANDOM_DOMAIN_REVIEW);

        assertThat(anonymousLinkSignals.signalsFor(RANDOM_LINK, Outcome.CLEAN).randomLooking()).isTrue();
    }

    @Test
    void aRealLookingYoungDomainIsNotRandomButKeepsItsAge() {
        when(domainAgeService.registeredOn("www.jutarnji.hr")).thenReturn(Optional.of(TODAY.minusDays(3)));

        assertThat(anonymousLinkSignals.signalsFor("https://www.jutarnji.hr/vijesti", Outcome.CLEAN))
                .isEqualTo(new Signals(false, Optional.of(TODAY.minusDays(3)), true));
    }

    @Test
    void aKnownYoungDomainGetsTheWarningPage() {
        when(domainAgeService.registeredOn("www.jutarnji.hr")).thenReturn(Optional.of(TODAY.minusDays(364)), Optional.of(TODAY.minusDays(365)));

        assertThat(anonymousLinkSignals.signalsFor("https://www.jutarnji.hr/vijesti", Outcome.CLEAN).warningPage()).isTrue();
        assertThat(anonymousLinkSignals.signalsFor("https://www.jutarnji.hr/vijesti", Outcome.CLEAN).warningPage()).isFalse();
    }

    @Test
    void anUnknownAgeAloneGivesNoWarningPage() {
        when(domainAgeService.registeredOn("www.jutarnji.hr")).thenReturn(Optional.empty());

        assertThat(anonymousLinkSignals.signalsFor("https://www.jutarnji.hr/vijesti", Outcome.FLAG))
                .isEqualTo(new Signals(false, Optional.empty(), false));
    }

    @Test
    void aRandomLookingDomainGetsTheWarningPageEvenWithReviewOff() {
        when(domainAgeService.registeredOn(RANDOM_HOST)).thenReturn(Optional.empty());
        SettingsServiceFixtures.turnOff(settingsService, SettingKey.RANDOM_DOMAIN_REVIEW);

        assertThat(anonymousLinkSignals.signalsFor(RANDOM_LINK, Outcome.CLEAN).warningPage()).isTrue();
    }

    @Test
    void theWarningPageMaximumAgeComesFromSettings() {
        when(domainAgeService.registeredOn("www.jutarnji.hr")).thenReturn(Optional.of(TODAY.minusDays(40)));
        assertThat(anonymousLinkSignals.signalsFor("https://www.jutarnji.hr/vijesti", Outcome.CLEAN).warningPage()).isTrue();

        SettingsServiceFixtures.change(settingsService, SettingKey.WARNING_PAGE_MAX_AGE_DAYS, 30);

        assertThat(anonymousLinkSignals.signalsFor("https://www.jutarnji.hr/vijesti", Outcome.CLEAN).warningPage()).isFalse();
    }

    @Test
    void theWarningSwitchOffGivesNoWarningPageButKeepsTheOtherSignals() {
        when(domainAgeService.registeredOn(RANDOM_HOST)).thenReturn(Optional.of(TODAY.minusDays(10)));
        SettingsServiceFixtures.turnOff(settingsService, SettingKey.WARNING_PAGE);

        assertThat(anonymousLinkSignals.signalsFor(RANDOM_LINK, Outcome.CLEAN)).isEqualTo(new Signals(true, Optional.of(TODAY.minusDays(10)), false));
    }

    @Test
    void anAllowedVerdictGivesNoSignals() {
        assertThat(anonymousLinkSignals.signalsFor(RANDOM_LINK, Outcome.ALLOWED)).isEqualTo(Signals.none());
        verifyNoInteractions(domainAgeService);
    }

    @ParameterizedTest
    @EnumSource(value = Outcome.class, names = {"CLEAN", "FLAG"})
    void everyOtherVerdictLooksTheDomainUp(final Outcome outcome) {
        anonymousLinkSignals.signalsFor(RANDOM_LINK, outcome);

        verify(domainAgeService).registeredOn(RANDOM_HOST);
    }

    @Test
    void anIpAddressOrNoHostGivesNoSignals() {
        assertThat(anonymousLinkSignals.signalsFor("http://203.0.113.7/xdvqnxj", Outcome.CLEAN)).isEqualTo(Signals.none());
        assertThat(anonymousLinkSignals.signalsFor("http://[2001:db8::1]/xdvqnxj", Outcome.CLEAN)).isEqualTo(Signals.none());
        assertThat(anonymousLinkSignals.signalsFor("not a url", Outcome.CLEAN)).isEqualTo(Signals.none());
        verifyNoInteractions(domainAgeService);
    }

    @Test
    @ExtendWith(OutputCaptureExtension.class)
    void aFailingAgeLookupCountsAsUnknownAge(final CapturedOutput output) {
        when(domainAgeService.registeredOn(any())).thenThrow(new IllegalStateException("rdap.example down"));

        assertThat(anonymousLinkSignals.signalsFor(RANDOM_LINK, Outcome.CLEAN)).isEqualTo(new Signals(true, Optional.empty(), true));
        assertThat(output).containsOnlyOnce("Domain age lookup for the link signals failed exception=java.lang.IllegalStateException")
                .doesNotContain("rdap.example", "xdvqnxj");
    }

    @Test
    void noSignalsAreNotRandomAndHaveNoAge() {
        assertThat(Signals.none()).isEqualTo(new Signals(false, Optional.empty(), false));
        assertThat(AnonymousLinkSignals.RANDOM_LOOKING_DOMAIN).isEqualTo("Random-looking domain");
    }

}
