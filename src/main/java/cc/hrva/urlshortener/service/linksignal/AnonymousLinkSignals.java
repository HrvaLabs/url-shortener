package cc.hrva.urlshortener.service.linksignal;

import cc.hrva.urlshortener.dto.LinkVerdict;
import cc.hrva.urlshortener.service.DomainAgeService;
import cc.hrva.urlshortener.service.SettingsService;
import cc.hrva.urlshortener.service.settings.SettingKey;
import cc.hrva.urlshortener.util.DomainNames;
import java.time.Clock;
import java.time.LocalDate;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class AnonymousLinkSignals {

    public static final String RANDOM_LOOKING_DOMAIN = "Random-looking domain";

    private final RandomLookingLabels randomLookingLabels;
    private final DomainAgeService domainAgeService;
    private final SettingsService settingsService;
    private final Clock clock;

    public Signals signalsFor(final String longUrl, final LinkVerdict.Outcome outcome) {
        if (outcome == LinkVerdict.Outcome.ALLOWED || noSignalIsWanted()) {
            return Signals.none();
        }
        return DomainNames.hostOf(longUrl)
                .filter(host -> !DomainNames.isIpLiteral(host))
                .map(this::signalsOfHost)
                .orElseGet(Signals::none);
    }

    private boolean noSignalIsWanted() {
        return !settingsService.isOn(SettingKey.RANDOM_DOMAIN_REVIEW) && !settingsService.isOn(SettingKey.WARNING_PAGE);
    }

    private Signals signalsOfHost(final String host) {
        final var registeredOn = registeredOn(host);
        final var randomLooking = randomLookingLabels.looksRandom(host) && youngOrOfUnknownAge(registeredOn);
        return new Signals(randomLooking, registeredOn, warningPage(randomLooking, registeredOn));
    }

    private Optional<LocalDate> registeredOn(final String host) {
        try {
            return domainAgeService.registeredOn(host);
        } catch (final RuntimeException exception) {
            log.warn("Domain age lookup for the link signals failed exception={}", exception.getClass().getName());
            return Optional.empty();
        }
    }

    private boolean youngOrOfUnknownAge(final Optional<LocalDate> registeredOn) {
        final var oldestCounted = LocalDate.now(clock).minusDays(settingsService.number(SettingKey.RANDOM_DOMAIN_MAX_AGE_DAYS));
        return registeredOn.map(date -> date.isAfter(oldestCounted)).orElse(true);
    }

    private boolean warningPage(final boolean randomLooking, final Optional<LocalDate> registeredOn) {
        if (!settingsService.isOn(SettingKey.WARNING_PAGE)) {
            return false;
        }
        final var oldestCounted = LocalDate.now(clock).minusDays(settingsService.number(SettingKey.WARNING_PAGE_MAX_AGE_DAYS));
        return randomLooking || registeredOn.filter(date -> date.isAfter(oldestCounted)).isPresent();
    }

    public record Signals(boolean randomLooking, Optional<LocalDate> registeredOn, boolean warningPage) {

        public static Signals none() {
            return new Signals(false, Optional.empty(), false);
        }

    }

}
