package cc.hrva.urlshortener.service.domain;

import cc.hrva.urlshortener.exception.CustomDomainConflictException;
import cc.hrva.urlshortener.exception.CustomDomainNotFoundException;
import cc.hrva.urlshortener.model.CustomDomain;
import cc.hrva.urlshortener.model.enums.CustomDomainStatus;
import cc.hrva.urlshortener.model.enums.DomainDisableReason;
import cc.hrva.urlshortener.repository.CustomDomainRepository;
import cc.hrva.urlshortener.service.alert.AdminAlertEvent.DomainDisabled;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionOperations;

@Slf4j
@Component
@RequiredArgsConstructor
public class CustomDomainModeration {

    private static final Set<CustomDomainStatus> DISABLEABLE = EnumSet.of(CustomDomainStatus.ACTIVE, CustomDomainStatus.AWAITING_DNS,
            CustomDomainStatus.APPROVED_PROVISIONING, CustomDomainStatus.ERROR);
    private static final String NOT_FOUND = "Custom domain not found";
    private static final String CANT_TURN_OFF = "This domain can't be turned off now.";
    private static final String NOT_TURNED_OFF = "This domain isn't turned off.";
    private static final String ACCOUNT_BLOCKED = "Account blocked";
    private static final String RULE_NOTE_SEPARATOR = ": ";
    private static final int MAX_NOTE_LENGTH = 500;

    private final CustomDomainRepository customDomainRepository;
    private final CustomDomainLifecycle customDomainLifecycle;
    private final ApplicationEventPublisher eventPublisher;
    private final TransactionOperations transactionOperations;
    private final Clock clock;

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public int disableMatching(final String ruleDomain, final String note) {
        final var ruleNote = StringUtils.abbreviate(StringUtils.isBlank(note) ? ruleDomain : ruleDomain + RULE_NOTE_SEPARATOR + note.strip(),
                MAX_NOTE_LENGTH);
        final var disabled = (int) read(() -> customDomainRepository.findByStatusIn(DISABLEABLE)).stream()
                .filter(domain -> covers(domain, ruleDomain))
                .filter(domain -> safely(domain.getId(), () -> disableIfStillOn(domain.getId(), DomainDisableReason.DOMAIN_RULE, ruleNote)))
                .count();
        if (disabled > 0) {
            log.info("Custom domains turned off by a domain rule ruleDomain={} disabled={}", ruleDomain, disabled);
        }
        return disabled;
    }

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public int disableForUser(final long userId, final String performedBy) {
        var disabled = 0;
        var rejected = 0;
        for (final var domain : read(() -> customDomainRepository.findByOwnerIdOrderByCreatedAtDescIdDesc(userId))) {
            final var id = domain.getId();
            if (DISABLEABLE.contains(domain.getStatus()) && safely(id, () -> disableIfStillOn(id, DomainDisableReason.USER_BLOCKED, null))) {
                disabled++;
            } else if (domain.getStatus() == CustomDomainStatus.PENDING_REVIEW && safely(id, () -> reject(id, performedBy))) {
                rejected++;
            }
        }
        if (disabled + rejected > 0) {
            log.info("Custom domains of a blocked account turned off userId={} disabled={} rejected={}", userId, disabled, rejected);
        }
        return disabled;
    }

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public int enableForUser(final long userId) {
        final var enabled = (int) read(() -> customDomainRepository.findByOwnerIdOrderByCreatedAtDescIdDesc(userId)).stream()
                .filter(domain -> domain.getStatus() == CustomDomainStatus.DISABLED && domain.getDisabledReason() == DomainDisableReason.USER_BLOCKED)
                .filter(domain -> safely(domain.getId(), () -> enableIfStillOffFor(domain.getId(), DomainDisableReason.USER_BLOCKED)))
                .count();
        if (enabled > 0) {
            log.info("Custom domains of an unblocked account turned on userId={} enabled={}", userId, enabled);
        }
        return enabled;
    }

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public void disable(final long id, final DomainDisableReason reason, final String note) {
        transactionOperations.executeWithoutResult(status -> {
            final var domain = locked(id);
            if (!DISABLEABLE.contains(domain.getStatus())) {
                log.info("Custom domain not turned off, its status doesn't allow it domainId={} status={}", id, domain.getStatus());
                throw new CustomDomainConflictException(CANT_TURN_OFF);
            }
            turnOff(domain, reason, note);
        });
    }

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public void enable(final long id) {
        final var enabledAs = transactionOperations.execute(status -> {
            final var domain = locked(id);
            if (domain.getStatus() != CustomDomainStatus.DISABLED) {
                log.info("Custom domain not turned on, it isn't turned off domainId={} status={}", id, domain.getStatus());
                throw new CustomDomainConflictException(NOT_TURNED_OFF);
            }
            return turnOn(domain);
        });
        checkSoon(id, enabledAs);
    }

    private boolean disableIfStillOn(final long id, final DomainDisableReason reason, final String note) {
        return Boolean.TRUE.equals(transactionOperations.execute(status -> customDomainRepository.findByIdForUpdate(id)
                .filter(domain -> DISABLEABLE.contains(domain.getStatus()))
                .map(domain -> turnOff(domain, reason, note))
                .orElse(false)));
    }

    private boolean enableIfStillOffFor(final long id, final DomainDisableReason reason) {
        final var enabledAs = transactionOperations.execute(status -> customDomainRepository.findByIdForUpdate(id)
                .filter(domain -> domain.getStatus() == CustomDomainStatus.DISABLED && domain.getDisabledReason() == reason)
                .map(this::turnOn)
                .orElse(null));
        checkSoon(id, enabledAs);
        return enabledAs != null;
    }

    private boolean reject(final long id, final String performedBy) {
        return Boolean.TRUE.equals(transactionOperations.execute(status -> {
            if (customDomainRepository.markRejected(id, performedBy, ACCOUNT_BLOCKED, now()) != 1) {
                return false;
            }
            eventPublisher.publishEvent(new CustomDomainChangedEvent(id));
            return true;
        }));
    }

    private boolean turnOff(final CustomDomain domain, final DomainDisableReason reason, final String note) {
        final var id = domain.getId();
        final var hostname = domain.getHostname();
        if (customDomainRepository.markDisabled(id, domain.getStatus(), reason, note, now()) != 1) {
            log.info("Custom domain changed before it could be turned off domainId={}", id);
            return false;
        }
        eventPublisher.publishEvent(new CustomDomainChangedEvent(id));
        eventPublisher.publishEvent(new DomainDisabled(id, hostname, reason, note));
        log.info("Custom domain turned off domainId={} from={} reason={}", id, domain.getStatus(), reason);
        return true;
    }

    private CustomDomainStatus turnOn(final CustomDomain domain) {
        final var id = domain.getId();
        final var withoutHostname = domain.getCloudflareId() == null;
        final var updated = withoutHostname
                ? customDomainRepository.markEnabledWithoutHostname(id, now())
                : customDomainRepository.markEnabled(id, now());
        if (updated != 1) {
            log.info("Custom domain changed before it could be turned on domainId={}", id);
            return null;
        }
        eventPublisher.publishEvent(new CustomDomainChangedEvent(id));
        final var enabledAs = withoutHostname ? CustomDomainStatus.ERROR : CustomDomainStatus.AWAITING_DNS;
        log.info("Custom domain turned on domainId={} to={}", id, enabledAs);
        return enabledAs;
    }

    private void checkSoon(final long id, final CustomDomainStatus enabledAs) {
        if (enabledAs != CustomDomainStatus.AWAITING_DNS) {
            return;
        }
        try {
            customDomainLifecycle.checkNow(id);
        } catch (final RuntimeException exception) {
            log.warn("Custom domain check after turning it on failed, the next run checks it domainId={} exception={}", id,
                    exception.getClass().getName());
        }
    }

    private static boolean safely(final long id, final BooleanSupplier action) {
        try {
            return action.getAsBoolean();
        } catch (final RuntimeException exception) {
            log.warn("Moderating a custom domain failed, continuing with the next one domainId={} exception={}", id, exception.getClass().getName());
            return false;
        }
    }

    private CustomDomain locked(final long id) {
        return customDomainRepository.findByIdForUpdate(id).orElseThrow(() -> new CustomDomainNotFoundException(NOT_FOUND));
    }

    private <T> List<T> read(final Supplier<List<T>> query) {
        return Objects.requireNonNull(transactionOperations.execute(status -> query.get()));
    }

    private static boolean covers(final CustomDomain domain, final String ruleDomain) {
        final var hostname = Objects.requireNonNullElse(domain.getHostnameKey(), domain.getHostname());
        return hostname.equals(ruleDomain) || hostname.endsWith("." + ruleDomain);
    }

    private LocalDateTime now() {
        return LocalDateTime.now(clock);
    }

}
