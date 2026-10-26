package cc.hrva.urlshortener.service.impl;

import cc.hrva.urlshortener.dto.bio.BioHandleAvailabilityResponse;
import cc.hrva.urlshortener.dto.bio.BioHandleDto;
import cc.hrva.urlshortener.dto.bio.BioPageButtonDto;
import cc.hrva.urlshortener.dto.bio.BioPageCreateDto;
import cc.hrva.urlshortener.dto.bio.BioPageResponse;
import cc.hrva.urlshortener.dto.bio.BioPageUpdateDto;
import cc.hrva.urlshortener.exception.ApiException;
import cc.hrva.urlshortener.exception.BioHandleUnavailableException;
import cc.hrva.urlshortener.exception.BioPageConflictException;
import cc.hrva.urlshortener.exception.BioPageNotFoundException;
import cc.hrva.urlshortener.exception.NoAuthorizationException;
import cc.hrva.urlshortener.exception.RateLimitExceededException;
import cc.hrva.urlshortener.model.BioPage;
import cc.hrva.urlshortener.model.BioPageButton;
import cc.hrva.urlshortener.model.Url;
import cc.hrva.urlshortener.model.User;
import cc.hrva.urlshortener.model.enums.BioAvatarKind;
import cc.hrva.urlshortener.model.enums.BioLinkState;
import cc.hrva.urlshortener.repository.BioPageButtonRepository;
import cc.hrva.urlshortener.repository.BioPageRepository;
import cc.hrva.urlshortener.repository.UrlRepository;
import cc.hrva.urlshortener.service.BioPageReviewService;
import cc.hrva.urlshortener.service.BioPageService;
import cc.hrva.urlshortener.service.UserService;
import cc.hrva.urlshortener.service.bio.BioHandles;
import cc.hrva.urlshortener.service.bio.BioPageLock;
import cc.hrva.urlshortener.service.bio.BioPageResponses;
import cc.hrva.urlshortener.service.bio.BioPageText;
import cc.hrva.urlshortener.service.bio.BioPageTextSignals;
import cc.hrva.urlshortener.service.ratelimit.RateLimiter;
import cc.hrva.urlshortener.service.ratelimit.RateLimiterUnavailableException;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.exception.ExceptionUtils;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionOperations;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class DefaultBioPageService implements BioPageService {

    private static final String NOT_AUTHENTICATED = "Not authenticated";
    private static final String NO_PAGE = "You don't have a bio page yet.";
    private static final String ALREADY_HAS_PAGE = "You already have a bio page.";
    private static final String HANDLE_TAKEN = "This handle is taken. Try another.";
    private static final String PAGE_CHANGED = "This page changed. Reload and try again.";
    private static final String OUTDATED_COPY =
            "Your bio page changed in another tab or window. We loaded the latest version; check your changes and save again.";
    private static final String HIDDEN_PAGE_NOT_DELETABLE =
            "An admin hid this page, so it can't be deleted here. You can still delete your whole account in Settings.";
    private static final String EMOJI_REQUIRED = "Pick one emoji for the avatar.";
    private static final String DUPLICATE_LINK = "Each link can be on your page only once.";
    private static final String NOT_YOUR_LINK = "You can only add your own short links.";
    private static final String LINK_NOT_WORKING = "Only working links can be added. /%s isn't working right now.";
    private static final String SAVES_REFUSED = "You've saved your bio page a lot in a short time. Try again in a few minutes.";
    private static final String HANDLE_CHANGES_REFUSED = "You've changed your handle a lot today. Try again tomorrow.";
    private static final String HANDLE_CHECKS_REFUSED = "Too many handle checks. Try again in a few minutes.";
    private static final String SAVE_LIMIT = "bio-save";
    private static final String HANDLE_LIMIT = "bio-handle";
    private static final String HANDLE_CHECK_LIMIT = "bio-handle-check";
    private static final int SAVES_PER_USER = 60;
    private static final Duration SAVE_WINDOW = Duration.ofHours(1);
    private static final int HANDLE_CHANGES_PER_USER = 5;
    private static final Duration HANDLE_CHANGE_WINDOW = Duration.ofHours(24);
    private static final int HANDLE_CHECKS_PER_USER = 120;
    private static final Duration HANDLE_CHECK_WINDOW = Duration.ofMinutes(10);
    private static final Set<BioLinkState> ADDABLE_STATES = EnumSet.of(BioLinkState.LIVE, BioLinkState.IN_REVIEW);
    private static final String BUTTON_LINK_CONSTRAINT = "uq_bio_page_button_page_url";
    private static final String BUTTON_URL_CONSTRAINT = "fk_bio_page_button_url";
    private static final String HANDLE_HOLD_CONSTRAINT = "pk_bio_handle_hold";

    private final UserService userService;
    private final BioPageRepository bioPageRepository;
    private final BioPageLock bioPageLock;
    private final BioPageButtonRepository bioPageButtonRepository;
    private final UrlRepository urlRepository;
    private final BioHandles bioHandles;
    private final BioPageResponses bioPageResponses;
    private final BioPageTextSignals bioPageTextSignals;
    private final BioPageReviewService bioPageReviewService;
    private final RateLimiter rateLimiter;
    private final TransactionOperations transactionOperations;
    private final Clock clock;

    @Override
    public BioPageResponse mine() {
        return bioPageResponses.editorView(pageOf(currentUser()));
    }

    @Override
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public BioPageResponse create(final BioPageCreateDto bioPageCreateDto) {
        final var user = currentUser();
        if (bioPageRepository.findByOwner(user).isPresent()) {
            throw new BioHandleUnavailableException(ALREADY_HAS_PAGE);
        }
        final var handle = BioHandles.normalize(bioPageCreateDto.getHandle());
        requireHandleFormat(handle);
        bioHandles.requireAvailable(handle, null);

        final BioPage created;
        try {
            created = transactionOperations.execute(status -> {
                final var page = bioPageRepository.saveAndFlush(BioPage.builder().owner(user).handle(handle).build());
                requireWithinLimit(HANDLE_LIMIT, user, HANDLE_CHANGES_PER_USER, HANDLE_CHANGE_WINDOW, HANDLE_CHANGES_REFUSED);
                return page;
            });
        } catch (final DataIntegrityViolationException exception) {
            if (bioPageRepository.findByOwner(user).isPresent()) {
                log.info("Bio page was created by a concurrent request userId={}", user.getId());
                throw new BioHandleUnavailableException(ALREADY_HAS_PAGE);
            }
            log.info("Bio page handle was taken by a concurrent create userId={}", user.getId());
            throw new BioHandleUnavailableException(HANDLE_TAKEN);
        }
        log.info("Bio page created pageId={} userId={}", created.getId(), user.getId());
        flagSuspiciousText(created, StringUtils.EMPTY, StringUtils.EMPTY, List.of());
        return bioPageResponses.editorView(created, List.of());
    }

    @Override
    @Transactional
    public BioPageResponse update(final BioPageUpdateDto bioPageUpdateDto) {
        final var user = currentUser();
        requireWithinLimit(SAVE_LIMIT, user, SAVES_PER_USER, SAVE_WINDOW, SAVES_REFUSED);
        final var page = lockedPageOf(user);
        requireCurrentCopy(page, bioPageUpdateDto.getExpectedUpdatedAt());

        final var displayName = cleaned(bioPageUpdateDto.getDisplayName(), BioPageUpdateDto.MAX_DISPLAY_NAME_LENGTH,
                BioPageUpdateDto.DISPLAY_NAME_TOO_LONG);
        final var bio = limited(BioPageText.cleanBio(bioPageUpdateDto.getBio()), BioPageUpdateDto.MAX_BIO_LENGTH,
                BioPageUpdateDto.BIO_TOO_LONG);
        final var requestedButtons = bioPageUpdateDto.getButtons();
        final var titles = requestedButtons.stream().map(button -> titleOf(button.getTitle())).toList();
        final var avatarEmoji = avatarEmojiOf(bioPageUpdateDto);
        final var links = ownLinks(user, requestedButtons);
        final var existingButtons = bioPageResponses.buttonsOf(page);
        requireNewLinksWork(requestedButtons, links, existingButtons);

        page.setDisplayName(displayName);
        page.setBio(bio);
        page.setAvatarKind(bioPageUpdateDto.getAvatarKind());
        page.setAvatarEmoji(avatarEmoji);
        page.setAccent(bioPageUpdateDto.getAccent());
        page.setTheme(bioPageUpdateDto.getTheme());
        page.setIndexable(Boolean.TRUE.equals(bioPageUpdateDto.getIndexable()));
        page.setUpdatedAt(BioPage.micros(LocalDateTime.now(clock)));
        final List<BioPageButton> buttons;
        final BioPage saved;
        try {
            buttons = reconcile(page, existingButtons, requestedButtons, titles, links);
            saved = bioPageRepository.saveAndFlush(page);
        } catch (final DataIntegrityViolationException exception) {
            if (!violates(exception, BUTTON_LINK_CONSTRAINT) && !violates(exception, BUTTON_URL_CONSTRAINT)) {
                throw exception;
            }
            log.info("Bio page buttons were changed by a concurrent request pageId={}", page.getId());
            throw new BioPageConflictException(PAGE_CHANGED);
        } catch (final OptimisticLockingFailureException exception) {
            log.info("Bio page button was removed by a concurrent request pageId={}", page.getId());
            throw new BioPageConflictException(PAGE_CHANGED);
        }
        log.info("Bio page saved pageId={} buttons={}", saved.getId(), buttons.size());
        flagSuspiciousText(saved, displayName, bio, titles);
        return bioPageResponses.editorView(saved, buttons);
    }

    @Override
    @Transactional
    public BioPageResponse changeHandle(final BioHandleDto bioHandleDto) {
        final var user = currentUser();
        final var page = lockedPageOf(user);
        final var handle = BioHandles.normalize(bioHandleDto.getHandle());
        if (handle.equals(page.getHandle())) {
            return bioPageResponses.editorView(page);
        }
        requireHandleFormat(handle);
        bioHandles.requireAvailable(handle, page);

        bioHandles.releaseOwnHold(handle, page);
        final var oldHandle = page.getHandle();
        if (oldHandle != null) {
            bioHandles.hold(oldHandle, page);
        }
        page.setHandle(handle);
        page.setHandleRevokedReason(null);
        try {
            bioPageRepository.saveAndFlush(page);
        } catch (final DataIntegrityViolationException exception) {
            log.info("Bio page handle was taken by a concurrent request pageId={}", page.getId());
            throw new BioHandleUnavailableException(HANDLE_TAKEN);
        }
        requireWithinLimit(HANDLE_LIMIT, user, HANDLE_CHANGES_PER_USER, HANDLE_CHANGE_WINDOW, HANDLE_CHANGES_REFUSED);
        log.info("Bio page handle changed pageId={}", page.getId());
        final var buttons = bioPageResponses.buttonsOf(page);
        flagSuspiciousText(page, page.getDisplayName(), page.getBio(), buttons.stream().map(BioPageButton::getTitle).toList());
        return bioPageResponses.editorView(page, buttons);
    }

    @Override
    @Transactional
    public void delete() {
        final var page = lockedPageOf(currentUser());
        if (page.getHiddenAt() != null) {
            log.info("Bio page delete refused because an admin hid the page pageId={}", page.getId());
            throw new BioPageConflictException(HIDDEN_PAGE_NOT_DELETABLE);
        }
        if (page.getHandle() != null) {
            bioHandles.hold(page.getHandle(), null);
        }
        try {
            bioPageRepository.delete(page);
            bioPageRepository.flush();
        } catch (final OptimisticLockingFailureException exception) {
            throw deletedByAConcurrentRequest(page);
        } catch (final DataIntegrityViolationException exception) {
            if (!violates(exception, HANDLE_HOLD_CONSTRAINT)) {
                throw exception;
            }
            throw deletedByAConcurrentRequest(page);
        }
        log.info("Bio page deleted pageId={}", page.getId());
    }

    @Override
    public BioHandleAvailabilityResponse availability(final String handle) {
        final var user = currentUser();
        requireWithinLimit(HANDLE_CHECK_LIMIT, user, HANDLE_CHECKS_PER_USER, HANDLE_CHECK_WINDOW, HANDLE_CHECKS_REFUSED);
        final var normalized = BioHandles.normalize(handle);
        final var reason = BioHandles.formatError(normalized)
                .or(() -> bioHandles.unavailableReason(normalized, bioPageRepository.findByOwner(user).orElse(null)));
        return new BioHandleAvailabilityResponse(normalized, reason.isEmpty(), reason.orElse(null));
    }

    private void flagSuspiciousText(final BioPage page, final String displayName, final String bio, final List<String> titles) {
        final var reasons = bioPageTextSignals.reasonsFor(page.getHandle(), displayName, bio, titles);
        if (!reasons.isEmpty()) {
            bioPageReviewService.flag(page, reasons);
        }
    }

    private User currentUser() {
        final var user = userService.getUserFromToken();
        if (user == null) {
            throw new NoAuthorizationException(NOT_AUTHENTICATED);
        }
        return user;
    }

    private BioPage pageOf(final User user) {
        return bioPageRepository.findByOwner(user).orElseThrow(() -> new BioPageNotFoundException(NO_PAGE));
    }

    private BioPage lockedPageOf(final User user) {
        final var page = pageOf(user);
        return bioPageLock.lock(page).orElseThrow(() -> deletedByAConcurrentRequest(page));
    }

    private static BioPageNotFoundException deletedByAConcurrentRequest(final BioPage page) {
        log.info("Bio page was deleted by a concurrent request pageId={}", page.getId());
        return new BioPageNotFoundException(NO_PAGE);
    }

    private static void requireCurrentCopy(final BioPage page, final LocalDateTime expectedUpdatedAt) {
        if (expectedUpdatedAt == null) {
            return;
        }
        final var stored = page.getUpdatedAt() == null ? null : BioPage.micros(page.getUpdatedAt());
        if (!BioPage.micros(expectedUpdatedAt).equals(stored)) {
            log.info("Bio page save refused because it was made from an outdated copy pageId={}", page.getId());
            throw new BioPageConflictException(OUTDATED_COPY);
        }
    }

    private static boolean violates(final DataIntegrityViolationException exception, final String constraint) {
        final var violation = ExceptionUtils.throwableOfType(exception, ConstraintViolationException.class);
        return violation != null && StringUtils.containsIgnoreCase(violation.getConstraintName(), constraint);
    }

    private static void requireHandleFormat(final String handle) {
        final var formatError = BioHandles.formatError(handle);
        if (formatError.isPresent()) {
            throw new ApiException(formatError.get());
        }
    }

    private void requireWithinLimit(final String name, final User user, final int limit, final Duration window, final String refusal) {
        if (!withinLimit(name, user, limit, window)) {
            log.info("Bio page request refused by the rate limit limit={} userId={}", name, user.getId());
            throw new RateLimitExceededException(refusal);
        }
    }

    private boolean withinLimit(final String name, final User user, final int limit, final Duration window) {
        try {
            return rateLimiter.tryAcquire(name + ":user:" + user.getId(), limit, window);
        } catch (final RateLimiterUnavailableException exception) {
            log.warn("Bio page rate limit unavailable, allowing the request limit={}", name);
            return true;
        }
    }

    private static String cleaned(final String text, final int maxLength, final String tooLong) {
        return limited(BioPageText.clean(text), maxLength, tooLong);
    }

    private static String limited(final String cleaned, final int maxLength, final String tooLong) {
        if (cleaned.length() > maxLength) {
            throw new ApiException(tooLong);
        }
        return cleaned;
    }

    private static String titleOf(final String title) {
        final var cleaned = cleaned(title, BioPageButtonDto.MAX_TITLE_LENGTH, BioPageButtonDto.TITLE_TOO_LONG);
        if (cleaned.isEmpty()) {
            throw new ApiException(BioPageButtonDto.TITLE_REQUIRED);
        }
        return cleaned;
    }

    private static String avatarEmojiOf(final BioPageUpdateDto bioPageUpdateDto) {
        if (bioPageUpdateDto.getAvatarKind() != BioAvatarKind.EMOJI) {
            return null;
        }
        final var emoji = BioPageText.clean(bioPageUpdateDto.getAvatarEmoji());
        if (!BioPageText.isSingleEmoji(emoji)) {
            throw new ApiException(EMOJI_REQUIRED);
        }
        return emoji;
    }

    private Map<Long, Url> ownLinks(final User user, final List<BioPageButtonDto> requestedButtons) {
        final var urlIds = requestedButtons.stream().map(BioPageButtonDto::getUrlId).toList();
        if (new HashSet<>(urlIds).size() != urlIds.size()) {
            throw new ApiException(DUPLICATE_LINK);
        }
        if (urlIds.isEmpty()) {
            return Map.of();
        }
        final var found = urlRepository.findAllById(urlIds);
        if (found.stream().anyMatch(url -> !isOwnedBy(url, user))) {
            throw new ApiException(NOT_YOUR_LINK);
        }
        if (found.size() != urlIds.size()) {
            log.info("Bio page save refused because a link on it was deleted userId={}", user.getId());
            throw new BioPageConflictException(PAGE_CHANGED);
        }
        return found.stream().collect(Collectors.toMap(Url::getId, Function.identity()));
    }

    private static boolean isOwnedBy(final Url url, final User user) {
        return url.getOwner() != null && Objects.equals(url.getOwner().getId(), user.getId());
    }

    private void requireNewLinksWork(final List<BioPageButtonDto> requestedButtons, final Map<Long, Url> links,
                                     final List<BioPageButton> existingButtons) {
        final var onPage = existingButtons.stream().map(button -> button.getUrl().getId()).collect(Collectors.toSet());
        final var newLinks = requestedButtons.stream()
                .map(BioPageButtonDto::getUrlId)
                .filter(urlId -> !onPage.contains(urlId))
                .map(links::get)
                .toList();
        final var states = bioPageResponses.statesOf(newLinks);
        newLinks.stream()
                .filter(url -> !ADDABLE_STATES.contains(states.get(url.getId())))
                .findFirst()
                .ifPresent(url -> {
                    throw new ApiException(LINK_NOT_WORKING.formatted(url.getShortUrl()));
                });
    }

    private List<BioPageButton> reconcile(final BioPage page, final List<BioPageButton> existingButtons,
                                          final List<BioPageButtonDto> requestedButtons, final List<String> titles,
                                          final Map<Long, Url> links) {
        final var existingByUrlId = existingButtons.stream()
                .collect(Collectors.toMap(button -> button.getUrl().getId(), Function.identity(), (first, second) -> first, LinkedHashMap::new));
        final var buttons = new ArrayList<BioPageButton>();
        final var added = new ArrayList<BioPageButton>();
        for (var index = 0; index < requestedButtons.size(); index++) {
            final var requested = requestedButtons.get(index);
            var button = existingByUrlId.remove(requested.getUrlId());
            if (button == null) {
                button = BioPageButton.builder().page(page).url(links.get(requested.getUrlId())).build();
                added.add(button);
            }
            button.setTitle(titles.get(index));
            button.setIcon(requested.getIcon());
            button.setVisible(!Boolean.FALSE.equals(requested.getVisible()));
            button.setSortOrder(index);
            buttons.add(button);
        }
        final var removed = List.copyOf(existingByUrlId.values());
        if (!removed.isEmpty()) {
            bioPageButtonRepository.deleteAllInBatch(removed);
            bioPageButtonRepository.flush();
        }
        if (!added.isEmpty()) {
            bioPageButtonRepository.saveAll(added);
        }
        return buttons;
    }

}
