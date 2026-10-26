package cc.hrva.urlshortener.service.bio;

import cc.hrva.urlshortener.exception.ApiException;
import cc.hrva.urlshortener.exception.BioHandleUnavailableException;
import cc.hrva.urlshortener.model.BioHandleHold;
import cc.hrva.urlshortener.model.BioPage;
import cc.hrva.urlshortener.repository.BioHandleHoldRepository;
import cc.hrva.urlshortener.repository.BioPageRepository;
import cc.hrva.urlshortener.service.ReservedAliasService;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class BioHandles {

    static final Duration HOLD = Duration.ofDays(30);
    static final Set<String> SYSTEM_WORDS = Set.of(
            "abuse", "admin", "administrator", "api", "app", "auth", "bio", "blog", "changelog", "connect", "contact",
            "dashboard", "help", "hrva", "hrvacc", "hrvalabs", "login", "logout", "mail", "mcp", "moderator", "null",
            "official", "privacy", "register", "report", "root", "security", "settings", "staff", "stats", "support",
            "system", "team", "terms", "unavailable", "undefined", "www");
    static final Set<String> SERVICE_NAMES = Set.of("hrva", "hrvacc", "hrvalabs");

    private static final int MIN_LENGTH = 3;
    private static final int MAX_LENGTH = 30;
    private static final Pattern ALLOWED_CHARACTERS = Pattern.compile("[a-z0-9_-]+");
    private static final Pattern LETTER_OR_NUMBER = Pattern.compile("[a-z0-9]");
    private static final String PART_SEPARATORS = "-_";
    private static final String LENGTH = "A handle must be 3 to 30 characters.";
    private static final String CHARACTERS = "Use only letters, numbers, hyphens and underscores.";
    private static final String NO_LETTER_OR_NUMBER = "A handle needs at least one letter or number.";
    private static final String RESERVED = "This handle is reserved. Try another.";
    private static final String TAKEN = "This handle is taken. Try another.";
    private static final String RECENTLY_USED = "This handle was recently used and is kept until %s. Try another.";
    private static final DateTimeFormatter HOLD_END = DateTimeFormatter.ofPattern("MMMM d, yyyy", Locale.ENGLISH);

    private final ReservedAliasService reservedAliasService;
    private final BioPageRepository bioPageRepository;
    private final BioHandleHoldRepository bioHandleHoldRepository;
    private final Clock clock;

    public static String normalize(final String raw) {
        if (raw == null) {
            return "";
        }
        return StringUtils.removeStart(raw.strip(), "@").toLowerCase(Locale.ROOT);
    }

    public static Optional<String> formatError(final String handle) {
        if (handle.length() < MIN_LENGTH || handle.length() > MAX_LENGTH) {
            return Optional.of(LENGTH);
        }
        if (!ALLOWED_CHARACTERS.matcher(handle).matches()) {
            return Optional.of(CHARACTERS);
        }
        if (!LETTER_OR_NUMBER.matcher(handle).find()) {
            return Optional.of(NO_LETTER_OR_NUMBER);
        }
        return Optional.empty();
    }

    public Optional<String> unavailableReason(final String handle, final BioPage page) {
        if (isReserved(handle)) {
            return Optional.of(RESERVED);
        }
        if (isTakenByAnotherPage(handle, page)) {
            return Optional.of(TAKEN);
        }
        return liveHoldForAnotherPage(handle, page).map(BioHandles::heldReason);
    }

    public void requireAvailable(final String handle, final BioPage page) {
        final var formatError = formatError(handle);
        if (formatError.isPresent()) {
            throw new ApiException(formatError.get());
        }
        final var unavailableReason = unavailableReason(handle, page);
        if (unavailableReason.isPresent()) {
            throw new BioHandleUnavailableException(unavailableReason.get());
        }
    }

    public void hold(final String handle, final BioPage pageOrNull) {
        final var now = LocalDateTime.now(clock);
        bioHandleHoldRepository.save(BioHandleHold.builder()
                .handle(handle)
                .page(pageOrNull)
                .heldUntil(now.plus(HOLD))
                .createdAt(now)
                .build());
    }

    public void releaseOwnHold(final String handle, final BioPage page) {
        bioHandleHoldRepository.findById(handle)
                .filter(hold -> isSamePage(hold.getPage(), page))
                .ifPresent(bioHandleHoldRepository::delete);
    }

    private boolean isReserved(final String handle) {
        if (reservedAliasService.isReserved(handle) || SYSTEM_WORDS.contains(handle)) {
            return true;
        }
        final var parts = StringUtils.split(handle, PART_SEPARATORS);
        return parts.length > 0 && SERVICE_NAMES.contains(parts[0]);
    }

    private boolean isTakenByAnotherPage(final String handle, final BioPage page) {
        return bioPageRepository.findByHandle(handle)
                .filter(other -> !isSamePage(other, page))
                .isPresent();
    }

    private Optional<BioHandleHold> liveHoldForAnotherPage(final String handle, final BioPage page) {
        return bioHandleHoldRepository.findByHandleAndHeldUntilAfter(handle, LocalDateTime.now(clock))
                .filter(hold -> !isSamePage(hold.getPage(), page));
    }

    private static String heldReason(final BioHandleHold hold) {
        return hold.getPage() == null ? RECENTLY_USED.formatted(HOLD_END.format(hold.getHeldUntil())) : TAKEN;
    }

    private static boolean isSamePage(final BioPage candidate, final BioPage page) {
        return candidate != null && page != null && page.getId() != null && Objects.equals(candidate.getId(), page.getId());
    }

}
