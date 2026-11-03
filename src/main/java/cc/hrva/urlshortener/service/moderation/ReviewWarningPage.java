package cc.hrva.urlshortener.service.moderation;

import cc.hrva.urlshortener.model.Url;
import cc.hrva.urlshortener.repository.UrlRepository;
import cc.hrva.urlshortener.service.SettingsService;
import cc.hrva.urlshortener.service.domain.UrlCache;
import cc.hrva.urlshortener.service.domain.UrlCacheKeys;
import cc.hrva.urlshortener.service.settings.SettingKey;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionOperations;

@Slf4j
@Component
@RequiredArgsConstructor
public class ReviewWarningPage {

    private final UrlRepository urlRepository;
    private final SettingsService settingsService;
    private final UrlCache urlCache;
    private final TransactionOperations transactionOperations;

    public void showWhileInReview(final Url url) {
        if (!settingsService.isOn(SettingKey.REVIEW_WARNING_PAGE)) {
            return;
        }
        urlRepository.findById(url.getId())
                .filter(ReviewWarningPage::waitsWithoutTheWarningPage)
                .ifPresent(link -> {
                    link.setWarningPage(true);
                    link.setWarningPageForReview(true);
                    urlRepository.save(link);
                    log.info("Warning page shown while the link waits for review urlId={}", link.getId());
                    urlCache.evictNowAndAgainLater(cacheKeyOf(link));
                });
    }

    public boolean clearAfterReview(final long urlId) {
        final var cleared = Optional.ofNullable(transactionOperations.execute(status -> urlRepository.findById(urlId)
                .filter(Url::isWarningPageForReview)
                .map(link -> {
                    link.setWarningPage(false);
                    link.setWarningPageForReview(false);
                    return urlRepository.save(link);
                })
                .orElse(null)));
        cleared.ifPresent(link -> {
            log.info("Review warning page cleared urlId={}", link.getId());
            urlCache.evictNowAndAgainLater(cacheKeyOf(link));
        });
        return cleared.isPresent();
    }

    private static boolean waitsWithoutTheWarningPage(final Url url) {
        return url.isActive() && url.getOwner() == null && url.getDomain() == null && !url.isWarningPage();
    }

    private static String cacheKeyOf(final Url url) {
        return UrlCacheKeys.of(url.domainId(), url.getShortUrl());
    }

}
