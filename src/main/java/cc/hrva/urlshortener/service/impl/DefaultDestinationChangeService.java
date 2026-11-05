package cc.hrva.urlshortener.service.impl;

import cc.hrva.urlshortener.dto.AccountStanding;
import cc.hrva.urlshortener.dto.DataExportDto.DestinationChangeExport;
import cc.hrva.urlshortener.dto.DestinationChangeResponse;
import cc.hrva.urlshortener.dto.DestinationChangeStanding;
import cc.hrva.urlshortener.dto.LinkDestinationChangeResponse;
import cc.hrva.urlshortener.dto.MissingRequirement;
import cc.hrva.urlshortener.exception.DestinationChangeRefusedException;
import cc.hrva.urlshortener.exception.RateLimitExceededException;
import cc.hrva.urlshortener.exception.ServiceBusyException;
import cc.hrva.urlshortener.exception.UserNotFoundException;
import cc.hrva.urlshortener.model.LinkDestinationChange;
import cc.hrva.urlshortener.model.Url;
import cc.hrva.urlshortener.model.User;
import cc.hrva.urlshortener.model.enums.AuditAction;
import cc.hrva.urlshortener.model.enums.BlockReason;
import cc.hrva.urlshortener.model.enums.CustomDomainStatus;
import cc.hrva.urlshortener.model.enums.DestinationChangeOverride;
import cc.hrva.urlshortener.model.enums.RequirementCode;
import cc.hrva.urlshortener.repository.LinkDestinationChangeRepository;
import cc.hrva.urlshortener.repository.LinkReviewRepository;
import cc.hrva.urlshortener.repository.UserRepository;
import cc.hrva.urlshortener.repository.specification.LinkDestinationChangeSpecification;
import cc.hrva.urlshortener.service.AccountStandingService;
import cc.hrva.urlshortener.service.AuditLogService;
import cc.hrva.urlshortener.service.DestinationChangeService;
import cc.hrva.urlshortener.service.LinkEditor;
import cc.hrva.urlshortener.service.SettingsService;
import cc.hrva.urlshortener.service.domain.UrlCache;
import cc.hrva.urlshortener.service.domain.UrlCacheKeys;
import cc.hrva.urlshortener.service.ratelimit.RateLimiter;
import cc.hrva.urlshortener.service.ratelimit.RateLimiterUnavailableException;
import cc.hrva.urlshortener.service.settings.SettingKey;
import cc.hrva.urlshortener.util.SortWhitelist;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
public class DefaultDestinationChangeService implements DestinationChangeService {

    public static final int CHANGES_PER_DAY = 5;
    private static final Duration DAY = Duration.ofDays(1);
    private static final Duration UNAVAILABLE_RETRY = Duration.ofMinutes(1);
    private static final String LIMIT_KEY = "destination-change:";
    private static final String TARGET_URL = "Url";
    private static final String CHANGED_AT = "changedAt";
    private static final SortWhitelist HISTORY_SORTS = SortWhitelist.of(CHANGED_AT, "changedBy", "via")
            .byDefault(Sort.by(Sort.Order.desc(CHANGED_AT)))
            .thenBy(Sort.Order.desc(CHANGED_AT), Sort.Order.desc("id"));
    private static final DateTimeFormatter MOMENT = DateTimeFormatter.ofPattern("d MMMM yyyy 'at' HH:mm 'UTC'", Locale.ENGLISH);
    private static final Set<RequirementCode> STANDALONE = Set.of(RequirementCode.CHANGES_TURNED_ON, RequirementCode.LINK_WITH_ACCOUNT,
            RequirementCode.ACCOUNT_TURNED_OFF);
    private static final String REFUSED = "You can't change where this link points yet. %s.";
    private static final String NO_ACCOUNT = "Links made without an account can't change where they point";
    private static final String TURNED_OFF = "Changing where links point is turned off right now";
    private static final String ACCOUNT_TURNED_OFF = "An admin turned off destination changes for your account";
    private static final String TARGET_USER = "User";
    private static final String DOMAIN_NOT_ACTIVE = "This link's domain isn't active";
    private static final String LINK_BLOCKED = "This link is blocked";
    private static final String LINK_BEING_CHECKED = "This link is being checked";
    private static final String NO_CHANGES_LEFT = "This link's destination can change " + CHANGES_PER_DAY + " times a day. Available from %s";
    private static final String LIMIT_REFUSAL = "This link's destination can change " + CHANGES_PER_DAY + " times a day. Try again in %s.";
    private static final String LIMIT_UNAVAILABLE = "Changing where a link points isn't available right now. Try again in a minute.";

    private static final String USER_NOT_FOUND = "User not found";

    private final AccountStandingService accountStandingService;
    private final UserRepository userRepository;
    private final SettingsService settingsService;
    private final LinkReviewRepository linkReviewRepository;
    private final LinkDestinationChangeRepository linkDestinationChangeRepository;
    private final AuditLogService auditLogService;
    private final RateLimiter rateLimiter;
    private final UrlCache urlCache;
    private final Clock clock;

    @Override
    public DestinationChangeResponse permissionFor(final Url url, final LinkEditor editor) {
        if (editor.admin()) {
            return new DestinationChangeResponse(true, List.of(), null);
        }
        final var missing = new ArrayList<>(missingFor(url, editor));
        final var used = rateLimiter.count(limitKeyOf(url));
        if (used.isPresent() && used.getAsLong() >= CHANGES_PER_DAY && missing.stream().noneMatch(requirement -> STANDALONE.contains(requirement.code()))) {
            final var from = now().plus(rateLimiter.resetsIn(limitKeyOf(url)).orElse(DAY));
            missing.add(new MissingRequirement(RequirementCode.DAILY_CHANGES_LEFT, NO_CHANGES_LEFT.formatted(MOMENT.format(from)), from));
        }
        final var changesLeft = used.isPresent() ? (int) Math.max(0, CHANGES_PER_DAY - used.getAsLong()) : null;
        return new DestinationChangeResponse(missing.isEmpty(), List.copyOf(missing), changesLeft);
    }

    @Override
    public void requireAllowed(final Url url, final LinkEditor editor) {
        if (editor.admin()) {
            return;
        }
        final var missing = missingFor(url, editor);
        if (missing.isEmpty()) {
            return;
        }
        log.info("Destination change refused urlId={} userId={} missing={}", url.getId(), editor.user().getId(),
                missing.stream().map(requirement -> requirement.code().name()).collect(Collectors.joining(",")));
        throw new DestinationChangeRefusedException(refusalOf(missing), missing);
    }

    @Override
    public void countChange(final Url url, final LinkEditor editor) {
        if (editor.admin()) {
            return;
        }
        final var key = limitKeyOf(url);
        final boolean allowed;
        try {
            allowed = rateLimiter.tryAcquire(key, CHANGES_PER_DAY, DAY);
        } catch (final RateLimiterUnavailableException exception) {
            log.warn("Destination change limit unavailable, refusing the change urlId={}", url.getId());
            throw new ServiceBusyException(LIMIT_UNAVAILABLE, UNAVAILABLE_RETRY);
        }
        if (!allowed) {
            final var wait = rateLimiter.resetsIn(key).orElse(DAY);
            log.info("Destination change refused by the daily limit urlId={}", url.getId());
            throw new RateLimitExceededException(LIMIT_REFUSAL.formatted(waitOf(wait)), wait);
        }
    }

    @Override
    public void record(final Url url, final String oldLongUrl, final LinkEditor editor, final boolean forced) {
        final var changedBy = editor.user().getEmail();
        linkDestinationChangeRepository.save(LinkDestinationChange.builder()
                .urlId(url.getId())
                .oldLongUrl(oldLongUrl)
                .newLongUrl(url.getLongUrl())
                .changedBy(changedBy)
                .via(editor.via())
                .forced(forced)
                .changedAt(now())
                .build());
        auditLogService.log(changedBy, AuditAction.CHANGE_LINK_DESTINATION, TARGET_URL, String.valueOf(url.getId()),
                "from: %s, to: %s, via: %s%s".formatted(oldLongUrl, url.getLongUrl(), editor.via(), forced ? ", forced: true" : ""));
        log.info("Destination changed urlId={} userId={} via={}", url.getId(), editor.user().getId(), editor.via());
        urlCache.evictNowAndAgainLater(UrlCacheKeys.of(url.domainId(), url.getShortUrl()));
    }

    @Override
    public Page<LinkDestinationChangeResponse> history(final long urlId, final String search, final Pageable pageable) {
        var spec = LinkDestinationChangeSpecification.ofLink(urlId);
        if (StringUtils.isNotBlank(search)) {
            spec = spec.and(LinkDestinationChangeSpecification.searchMatches(search.strip()));
        }
        return linkDestinationChangeRepository.findAll(spec, HISTORY_SORTS.apply(pageable)).map(LinkDestinationChangeResponse::from);
    }

    @Override
    public List<DestinationChangeExport> exportFor(final User user) {
        return linkDestinationChangeRepository.exportOf(user);
    }

    private List<MissingRequirement> missingFor(final Url url, final LinkEditor editor) {
        if (url.getOwner() == null) {
            return List.of(MissingRequirement.of(RequirementCode.LINK_WITH_ACCOUNT, NO_ACCOUNT));
        }
        if (!settingsService.isOn(SettingKey.DESTINATION_CHANGES)) {
            return List.of(MissingRequirement.of(RequirementCode.CHANGES_TURNED_ON, TURNED_OFF));
        }
        if (isHeld(url)) {
            return List.of(MissingRequirement.of(RequirementCode.LINK_NOT_BEING_CHECKED, LINK_BEING_CHECKED));
        }
        final var override = overrideOf(editor.user());
        if (override == DestinationChangeOverride.NEVER) {
            return List.of(MissingRequirement.of(RequirementCode.ACCOUNT_TURNED_OFF, ACCOUNT_TURNED_OFF));
        }
        if (url.getDomain() == null) {
            return override == DestinationChangeOverride.ALWAYS ? List.of() : accountStandingService.standingOf(editor.user()).missing();
        }
        final var missing = new ArrayList<MissingRequirement>();
        if (url.getDomain().getStatus() != CustomDomainStatus.ACTIVE) {
            missing.add(MissingRequirement.of(RequirementCode.DOMAIN_ACTIVE, DOMAIN_NOT_ACTIVE));
        }
        if (url.getThreatType() != null || url.getBlockReason() != null) {
            missing.add(MissingRequirement.of(RequirementCode.LINK_NOT_BLOCKED, LINK_BLOCKED));
        }
        if (linkReviewRepository.existsByUrl_IdAndResolvedAtIsNullAndSystemFlaggedTrueAndNoticeFalse(url.getId())) {
            missing.add(MissingRequirement.of(RequirementCode.LINK_NOT_BEING_CHECKED, LINK_BEING_CHECKED));
        }
        return List.copyOf(missing);
    }

    private static boolean isHeld(final Url url) {
        return url.getThreatType() == null && url.getBlockReason() != null && BlockReason.HOLDS.contains(url.getBlockReason());
    }

    @Override
    public DestinationChangeStanding standingOf(final User user) {
        return standing(user, accountStandingService.standingOf(user));
    }

    @Override
    public Map<Long, DestinationChangeStanding> standingsOf(final Collection<User> users) {
        final var standings = accountStandingService.standingsOf(users);
        return users.stream().collect(Collectors.toMap(User::getId, user -> standing(user, standings.get(user.getId()))));
    }

    @Override
    @Transactional(readOnly = true)
    public DestinationChangeStanding standingOf(final long userId) {
        return standingOf(userRepository.findById(userId).orElseThrow(() -> new UserNotFoundException(USER_NOT_FOUND)));
    }

    @Override
    @Transactional
    public DestinationChangeStanding setOverride(final long userId, final DestinationChangeOverride override, final String admin) {
        final var user = userRepository.findById(userId).orElseThrow(() -> new UserNotFoundException(USER_NOT_FOUND));
        final var before = overrideOf(user);
        if (before != override) {
            user.setDestinationChangeOverride(override);
            userRepository.save(user);
            auditLogService.log(admin, AuditAction.SET_DESTINATION_CHANGE_OVERRIDE, TARGET_USER, String.valueOf(userId),
                    "from: %s, to: %s".formatted(before, override));
            log.info("Destination change override set userId={} from={} to={}", userId, before, override);
        }
        return standingOf(user);
    }

    private DestinationChangeStanding standing(final User user, final AccountStanding standing) {
        final var override = overrideOf(user);
        final var allowed = settingsService.isOn(SettingKey.DESTINATION_CHANGES)
                && override != DestinationChangeOverride.NEVER
                && (override == DestinationChangeOverride.ALWAYS || standing.good());
        return new DestinationChangeStanding(override, allowed, standing.good(), standing.missing());
    }

    private static DestinationChangeOverride overrideOf(final User user) {
        return Objects.requireNonNullElse(user.getDestinationChangeOverride(), DestinationChangeOverride.AUTOMATIC);
    }

    private static String refusalOf(final List<MissingRequirement> missing) {
        if (missing.size() == 1 && STANDALONE.contains(missing.getFirst().code())) {
            return missing.getFirst().text() + ".";
        }
        return REFUSED.formatted(missing.stream().map(MissingRequirement::text).collect(Collectors.joining(". ")));
    }

    private static String waitOf(final Duration wait) {
        final var minutes = Math.max(1, (wait.toMillis() + 59_999) / 60_000);
        if (minutes < 60) {
            return minutes == 1 ? "1 minute" : minutes + " minutes";
        }
        final var hours = (minutes + 59) / 60;
        return hours == 1 ? "1 hour" : hours + " hours";
    }

    private static String limitKeyOf(final Url url) {
        return LIMIT_KEY + url.getId();
    }

    private LocalDateTime now() {
        return LocalDateTime.now(clock.withZone(ZoneId.systemDefault()));
    }

}
