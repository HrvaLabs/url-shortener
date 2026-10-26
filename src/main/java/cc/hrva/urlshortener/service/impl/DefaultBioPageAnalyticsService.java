package cc.hrva.urlshortener.service.impl;

import cc.hrva.urlshortener.dto.bio.BioPageAnalyticsResponse;
import cc.hrva.urlshortener.dto.bio.BioPageAnalyticsResponse.ButtonClicks;
import cc.hrva.urlshortener.dto.bio.BioViewDay;
import cc.hrva.urlshortener.exception.BioPageNotFoundException;
import cc.hrva.urlshortener.exception.NoAuthorizationException;
import cc.hrva.urlshortener.model.BioPage;
import cc.hrva.urlshortener.model.BioPageButton;
import cc.hrva.urlshortener.model.Url;
import cc.hrva.urlshortener.model.User;
import cc.hrva.urlshortener.model.enums.AnalyticsRange;
import cc.hrva.urlshortener.model.enums.BioLinkState;
import cc.hrva.urlshortener.repository.BioPageRepository;
import cc.hrva.urlshortener.repository.BioPageViewStatsRepository;
import cc.hrva.urlshortener.repository.LinkStatsRepository;
import cc.hrva.urlshortener.service.BioPageAnalyticsService;
import cc.hrva.urlshortener.service.UserService;
import cc.hrva.urlshortener.service.analytics.AnalyticsDays;
import cc.hrva.urlshortener.service.analytics.ReferrerClassifier;
import cc.hrva.urlshortener.service.bio.BioPageResponses;
import java.time.LocalDate;
import java.util.List;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class DefaultBioPageAnalyticsService implements BioPageAnalyticsService {

    private static final String NOT_AUTHENTICATED = "Not authenticated";
    private static final String NO_PAGE = "You don't have a bio page yet.";

    private final UserService userService;
    private final BioPageRepository bioPageRepository;
    private final BioPageResponses bioPageResponses;
    private final BioPageViewStatsRepository bioPageViewStatsRepository;
    private final LinkStatsRepository linkStatsRepository;
    private final ReferrerClassifier referrerClassifier;
    private final AnalyticsDays analyticsDays;

    @Override
    public BioPageAnalyticsResponse analytics(final Integer days) {
        final var range = AnalyticsRange.fromDays(days);
        final var page = bioPageRepository.findByOwner(currentUser()).orElseThrow(() -> new BioPageNotFoundException(NO_PAGE));
        final var to = analyticsDays.today();
        final var from = to.minusDays(range.days() - 1L);
        final var daily = zeroFilled(bioPageViewStatsRepository.findDaily(page.getId(), from, to), from, to);

        return new BioPageAnalyticsResponse(
                range.days(),
                from,
                to,
                page.getHandle(),
                daily.stream().mapToLong(BioViewDay::views).sum(),
                daily.stream().mapToLong(BioViewDay::botViews).sum(),
                daily,
                buttonClicksOf(page, from, to));
    }

    private List<ButtonClicks> buttonClicksOf(final BioPage page, final LocalDate from, final LocalDate to) {
        final var buttons = bioPageResponses.buttonsOf(page);
        final var urls = buttons.stream().map(BioPageButton::getUrl).toList();
        final var urlIds = urls.stream().map(Url::getId).toList();
        final var states = bioPageResponses.statesOf(urls);
        final var fromHrvaPages = linkStatsRepository.sumReferrerClicksByUrl(urlIds, referrerClassifier.ownHost(), from, to);
        final var allClicks = linkStatsRepository.sumClicksByUrl(urlIds, from, to);

        return buttons.stream()
                .map(button -> {
                    final var linkState = states.get(button.getUrl().getId());
                    return new ButtonClicks(
                            button.getId(),
                            button.getUrl().getId(),
                            button.getUrl().getShortUrl(),
                            button.getTitle(),
                            button.isVisible(),
                            linkState,
                            button.isVisible() && linkState == BioLinkState.LIVE,
                            fromHrvaPages.getOrDefault(button.getUrl().getId(), 0L),
                            allClicks.getOrDefault(button.getUrl().getId(), 0L));
                })
                .toList();
    }

    private static List<BioViewDay> zeroFilled(final List<BioViewDay> stored, final LocalDate from, final LocalDate to) {
        final var storedByDate = stored.stream().collect(Collectors.toMap(BioViewDay::date, Function.identity()));

        return from.datesUntil(to.plusDays(1))
                .map(date -> storedByDate.getOrDefault(date, new BioViewDay(date, 0, 0)))
                .toList();
    }

    private User currentUser() {
        final var user = userService.getUserFromToken();
        if (user == null) {
            throw new NoAuthorizationException(NOT_AUTHENTICATED);
        }
        return user;
    }

}
