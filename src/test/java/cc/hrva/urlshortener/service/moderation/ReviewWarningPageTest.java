package cc.hrva.urlshortener.service.moderation;

import cc.hrva.urlshortener.configuration.properties.AppProperties;
import cc.hrva.urlshortener.model.CustomDomain;
import cc.hrva.urlshortener.model.Url;
import cc.hrva.urlshortener.model.User;
import cc.hrva.urlshortener.repository.UrlRepository;
import cc.hrva.urlshortener.service.domain.UrlCache;
import cc.hrva.urlshortener.service.impl.DefaultSettingsService;
import cc.hrva.urlshortener.service.settings.SettingKey;
import cc.hrva.urlshortener.service.settings.SettingsServiceFixtures;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.cache.concurrent.ConcurrentMapCacheManager;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.transaction.support.TransactionOperations;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(MockitoExtension.class)
class ReviewWarningPageTest {

    private static final Instant NOW = Instant.parse("2026-11-03T10:00:00Z");

    @Mock
    private UrlRepository urlRepository;

    @Mock
    private TaskScheduler taskScheduler;

    private final ConcurrentMapCacheManager cacheManager = new ConcurrentMapCacheManager(UrlCache.CACHE_NAME);
    private final DefaultSettingsService settingsService = SettingsServiceFixtures.defaults(new AppProperties());

    private ReviewWarningPage reviewWarningPage;

    @BeforeEach
    void setUp() {
        reviewWarningPage = new ReviewWarningPage(urlRepository, settingsService, new UrlCache(cacheManager, taskScheduler, Clock.fixed(NOW, ZoneOffset.UTC)),
                TransactionOperations.withoutTransaction());
        lenient().when(urlRepository.save(any(Url.class))).thenAnswer(invocation -> invocation.getArgument(0));
    }

    @Test
    void anAnonymousLinkGetsTheWarningPageWhenItsReviewOpens() {
        final var link = stored(anonymousLink());

        reviewWarningPage.showWhileInReview(Url.builder().id(287L).build());

        assertThat(link).extracting(Url::isWarningPage, Url::isWarningPageForReview).containsExactly(true, true);
        verify(urlRepository).save(link);
    }

    @Test
    void anAccountLinkIsUnchanged() {
        final var link = stored(anonymousLink().owner(User.builder().id(5L).email("ana@hrva.cc").build()));

        reviewWarningPage.showWhileInReview(link);

        assertThat(link).extracting(Url::isWarningPage, Url::isWarningPageForReview).containsExactly(false, false);
        verify(urlRepository, never()).save(any(Url.class));
    }

    @Test
    void aLinkOnACustomDomainIsUnchanged() {
        final var link = stored(anonymousLink().domain(CustomDomain.builder().id(7L).hostname("go.example.com").build()));

        reviewWarningPage.showWhileInReview(link);

        assertThat(link).extracting(Url::isWarningPage, Url::isWarningPageForReview).containsExactly(false, false);
        verify(urlRepository, never()).save(any(Url.class));
    }

    @Test
    void aLinkThatAlreadyHasTheWarningKeepsItsReason() {
        final var link = stored(anonymousLink().warningPage(true));

        reviewWarningPage.showWhileInReview(link);

        assertThat(link).extracting(Url::isWarningPage, Url::isWarningPageForReview).containsExactly(true, false);
        verify(urlRepository, never()).save(any(Url.class));
    }

    @Test
    void theSettingOffLeavesLinksAlone() {
        SettingsServiceFixtures.turnOff(settingsService, SettingKey.REVIEW_WARNING_PAGE);
        final var link = stored(anonymousLink());

        reviewWarningPage.showWhileInReview(link);

        assertThat(link).extracting(Url::isWarningPage, Url::isWarningPageForReview).containsExactly(false, false);
        verify(urlRepository, never()).save(any(Url.class));
        verifyNoInteractions(taskScheduler);
    }

    @Test
    void aLinkThatIsNoLongerActiveIsUnchanged() {
        final var link = stored(anonymousLink().active(false));

        reviewWarningPage.showWhileInReview(Url.builder().id(287L).active(true).build());

        assertThat(link).extracting(Url::isWarningPage, Url::isWarningPageForReview).containsExactly(false, false);
        verify(urlRepository, never()).save(any(Url.class));
    }

    @Test
    void flaggingEvictsTheCachedRedirect() {
        stored(anonymousLink());
        cacheManager.getCache(UrlCache.CACHE_NAME).put("v0DB3", "cached before the review");

        reviewWarningPage.showWhileInReview(Url.builder().id(287L).build());

        assertThat(cacheManager.getCache(UrlCache.CACHE_NAME).get("v0DB3")).isNull();
        final var later = ArgumentCaptor.forClass(Runnable.class);
        verify(taskScheduler).schedule(later.capture(), eq(NOW.plusSeconds(2)));
        cacheManager.getCache(UrlCache.CACHE_NAME).put("v0DB3", "put back by a racing click");
        later.getValue().run();
        assertThat(cacheManager.getCache(UrlCache.CACHE_NAME).get("v0DB3")).isNull();
    }

    @Test
    void okRemovesAReviewWarning() {
        final var link = stored(anonymousLink().warningPage(true).warningPageForReview(true));

        assertThat(reviewWarningPage.clearAfterReview(287L)).isTrue();

        assertThat(link).extracting(Url::isWarningPage, Url::isWarningPageForReview).containsExactly(false, false);
        verify(urlRepository).save(link);
    }

    @Test
    void okKeepsAYoungDomainWarning() {
        final var link = stored(anonymousLink().warningPage(true));

        assertThat(reviewWarningPage.clearAfterReview(287L)).isFalse();

        assertThat(link).extracting(Url::isWarningPage, Url::isWarningPageForReview).containsExactly(true, false);
        verify(urlRepository, never()).save(any(Url.class));
        verifyNoInteractions(taskScheduler);
    }

    @Test
    void okEvictsTheCachedRedirect() {
        stored(anonymousLink().warningPage(true).warningPageForReview(true));
        cacheManager.getCache(UrlCache.CACHE_NAME).put("v0DB3", "cached with the warning page");

        reviewWarningPage.clearAfterReview(287L);

        assertThat(cacheManager.getCache(UrlCache.CACHE_NAME).get("v0DB3")).isNull();
        final var later = ArgumentCaptor.forClass(Runnable.class);
        verify(taskScheduler).schedule(later.capture(), eq(NOW.plusSeconds(2)));
        cacheManager.getCache(UrlCache.CACHE_NAME).put("v0DB3", "put back by a racing click");
        later.getValue().run();
        assertThat(cacheManager.getCache(UrlCache.CACHE_NAME).get("v0DB3")).isNull();
    }

    @Test
    void anUnknownLinkIsLeftAlone() {
        assertThat(reviewWarningPage.clearAfterReview(404L)).isFalse();
        reviewWarningPage.showWhileInReview(Url.builder().id(404L).build());

        verify(urlRepository, never()).save(any(Url.class));
        verifyNoInteractions(taskScheduler);
    }

    private Url stored(final Url.UrlBuilder builder) {
        final var link = builder.build();
        lenient().when(urlRepository.findById(link.getId())).thenReturn(Optional.of(link));
        return link;
    }

    private static Url.UrlBuilder anonymousLink() {
        return Url.builder().id(287L).shortUrl("v0DB3").longUrl("https://findfavoritedate.life/?u=f6yp608").active(true);
    }

}
