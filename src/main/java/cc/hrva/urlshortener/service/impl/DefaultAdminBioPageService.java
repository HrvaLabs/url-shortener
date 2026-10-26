package cc.hrva.urlshortener.service.impl;

import cc.hrva.urlshortener.dto.ReservedAliasDto;
import cc.hrva.urlshortener.dto.bio.AdminBioPageResponse;
import cc.hrva.urlshortener.dto.bio.BioHandleDto;
import cc.hrva.urlshortener.dto.bio.BioPageReasonDto;
import cc.hrva.urlshortener.dto.bio.BioPageReviewResolveDto;
import cc.hrva.urlshortener.exception.ApiException;
import cc.hrva.urlshortener.exception.BioHandleUnavailableException;
import cc.hrva.urlshortener.exception.BioPageNotFoundException;
import cc.hrva.urlshortener.exception.NoAuthorizationException;
import cc.hrva.urlshortener.exception.ShortUrlAlreadyExistsException;
import cc.hrva.urlshortener.model.BioPage;
import cc.hrva.urlshortener.model.enums.BioPageReviewResolution;
import cc.hrva.urlshortener.repository.BioButtonCount;
import cc.hrva.urlshortener.repository.BioPageButtonRepository;
import cc.hrva.urlshortener.repository.BioPageRepository;
import cc.hrva.urlshortener.repository.specification.BioPageSpecification;
import cc.hrva.urlshortener.service.AdminBioPageService;
import cc.hrva.urlshortener.service.BioPageReviewService;
import cc.hrva.urlshortener.service.ReservedAliasService;
import cc.hrva.urlshortener.service.UserService;
import cc.hrva.urlshortener.service.bio.BioHandles;
import cc.hrva.urlshortener.service.bio.BioPageLock;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class DefaultAdminBioPageService implements AdminBioPageService {

    private static final String NOT_AUTHENTICATED = "Not authenticated";
    private static final String PAGE_NOT_FOUND = "Bio page not found";
    private static final String NO_HANDLE = "This page has no handle.";
    private static final String NOT_IN_QUEUE = "This bio page is no longer in the review queue.";
    private static final String REASON_REQUIRED = "Reason is required";
    private static final String HANDLE_TAKEN = "This handle is taken. Try another.";
    private static final String REVOKED_HANDLE_REASON = "Revoked bio page handle: ";
    private static final int MAX_RESERVATION_REASON_LENGTH = 200;
    private static final Sort NEWEST_FIRST = Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id"));

    private final BioPageRepository bioPageRepository;
    private final BioPageLock bioPageLock;
    private final BioPageButtonRepository bioPageButtonRepository;
    private final BioPageReviewService bioPageReviewService;
    private final BioHandles bioHandles;
    private final ReservedAliasService reservedAliasService;
    private final UserService userService;
    private final Clock clock;

    @Override
    public Page<AdminBioPageResponse> search(final String search, final boolean hiddenOnly, final Pageable pageable) {
        var spec = Specification.<BioPage>unrestricted();
        if (StringUtils.isNotBlank(search)) {
            spec = spec.and(BioPageSpecification.searchMatches(search.strip()));
        }
        if (hiddenOnly) {
            spec = spec.and(BioPageSpecification.isHidden());
        }
        final var newestFirst = pageable.isPaged()
                ? PageRequest.of(pageable.getPageNumber(), pageable.getPageSize(), NEWEST_FIRST)
                : Pageable.unpaged(NEWEST_FIRST);
        final var pages = bioPageRepository.findAll(spec, newestFirst);
        if (pages.isEmpty()) {
            return pages.map(page -> AdminBioPageResponse.from(page, 0, 0, false));
        }
        final var pageIds = pages.getContent().stream().map(BioPage::getId).toList();
        final var counts = buttonCountsOf(pageIds);
        final var openReviews = bioPageReviewService.openPageIds(pageIds);
        return pages.map(page -> responseOf(page, counts, openReviews));
    }

    @Override
    @Transactional
    public AdminBioPageResponse hide(final long id, final BioPageReasonDto bioPageReasonDto) {
        final var page = lockedPage(id);
        hide(page, bioPageReasonDto.reason(), adminEmail());
        return responseOf(page);
    }

    @Override
    @Transactional
    public AdminBioPageResponse unhide(final long id) {
        final var page = lockedPage(id);
        page.setHiddenAt(null);
        page.setHiddenReason(null);
        page.setHiddenBy(null);
        bioPageRepository.saveAndFlush(page);
        log.info("Bio page unhidden by an admin pageId={}", page.getId());
        return responseOf(page);
    }

    @Override
    @Transactional
    public AdminBioPageResponse changeHandle(final long id, final BioHandleDto bioHandleDto) {
        final var page = lockedPage(id);
        final var handle = BioHandles.normalize(bioHandleDto.getHandle());
        if (handle.equals(page.getHandle())) {
            return responseOf(page);
        }
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
        log.info("Bio page handle changed by an admin pageId={}", page.getId());
        return responseOf(page);
    }

    @Override
    @Transactional
    public AdminBioPageResponse revokeHandle(final long id, final BioPageReasonDto bioPageReasonDto) {
        final var page = lockedPage(id);
        final var handle = page.getHandle();
        if (handle == null) {
            throw new ApiException(NO_HANDLE);
        }
        final var reason = bioPageReasonDto.reason().strip();
        page.setHandle(null);
        page.setHandleRevokedReason(reason);
        bioPageRepository.saveAndFlush(page);
        reserve(page, handle, reason);
        log.info("Bio page handle revoked by an admin pageId={}", page.getId());
        return responseOf(page);
    }

    @Override
    @Transactional
    public void resolveReview(final long pageId, final BioPageReviewResolveDto bioPageReviewResolveDto) {
        final var page = lockedPage(pageId);
        if (!bioPageReviewService.openPageIds(List.of(page.getId())).contains(page.getId())) {
            throw new ApiException(NOT_IN_QUEUE);
        }
        switch (bioPageReviewResolveDto.action()) {
            case OK -> bioPageReviewService.resolveOpen(page, BioPageReviewResolution.OK, adminEmail());
            case HIDE -> {
                if (StringUtils.isBlank(bioPageReviewResolveDto.reason())) {
                    throw new ApiException(REASON_REQUIRED);
                }
                hide(page, bioPageReviewResolveDto.reason(), adminEmail());
            }
        }
    }

    private void hide(final BioPage page, final String reason, final String admin) {
        page.setHiddenAt(LocalDateTime.now(clock));
        page.setHiddenReason(reason.strip());
        page.setHiddenBy(admin);
        bioPageRepository.saveAndFlush(page);
        bioPageReviewService.resolveOpen(page, BioPageReviewResolution.HIDDEN, admin);
        log.info("Bio page hidden by an admin pageId={}", page.getId());
    }

    private void reserve(final BioPage page, final String handle, final String reason) {
        if (reservedAliasService.isReserved(handle)) {
            log.info("Revoked bio page handle was already reserved pageId={}", page.getId());
            return;
        }
        try {
            reservedAliasService.reserve(
                    new ReservedAliasDto(handle, StringUtils.left(REVOKED_HANDLE_REASON + reason, MAX_RESERVATION_REASON_LENGTH)), adminEmail());
        } catch (final ShortUrlAlreadyExistsException exception) {
            log.info("Revoked bio page handle was reserved by a concurrent request pageId={}", page.getId());
        }
    }

    private BioPage lockedPage(final long id) {
        return bioPageRepository.findById(id)
                .flatMap(bioPageLock::lock)
                .orElseThrow(() -> new BioPageNotFoundException(PAGE_NOT_FOUND));
    }

    private String adminEmail() {
        final var admin = userService.getUserFromToken();
        if (admin == null) {
            throw new NoAuthorizationException(NOT_AUTHENTICATED);
        }
        return admin.getEmail();
    }

    private AdminBioPageResponse responseOf(final BioPage page) {
        final var pageIds = List.of(page.getId());
        return responseOf(page, buttonCountsOf(pageIds), bioPageReviewService.openPageIds(pageIds));
    }

    private Map<Long, BioButtonCount> buttonCountsOf(final Collection<Long> pageIds) {
        return bioPageButtonRepository.countByPageIds(pageIds).stream()
                .collect(Collectors.toMap(BioButtonCount::pageId, Function.identity()));
    }

    private static AdminBioPageResponse responseOf(final BioPage page, final Map<Long, BioButtonCount> counts, final Set<Long> openReviews) {
        final var count = counts.get(page.getId());
        return AdminBioPageResponse.from(
                page,
                count != null ? count.buttons() : 0,
                count != null ? count.visibleButtons() : 0,
                openReviews.contains(page.getId()));
    }

}
