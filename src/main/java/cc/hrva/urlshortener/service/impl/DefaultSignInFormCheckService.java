package cc.hrva.urlshortener.service.impl;

import cc.hrva.urlshortener.client.BrowserPageFetcher;
import cc.hrva.urlshortener.configuration.AsyncExecutorConfiguration;
import cc.hrva.urlshortener.exception.UrlValidationException;
import cc.hrva.urlshortener.model.Url;
import cc.hrva.urlshortener.repository.UrlRepository;
import cc.hrva.urlshortener.security.AdminTwoStep;
import cc.hrva.urlshortener.service.AccountCheckOutcome;
import cc.hrva.urlshortener.service.ModerationService;
import cc.hrva.urlshortener.service.ReviewQueueService;
import cc.hrva.urlshortener.service.SettingsService;
import cc.hrva.urlshortener.service.SignInFormCheckService;
import cc.hrva.urlshortener.service.settings.SettingKey;
import cc.hrva.urlshortener.service.signin.CredentialForms;
import cc.hrva.urlshortener.service.signin.NewAccounts;
import cc.hrva.urlshortener.service.signin.RealSignInHosts;
import cc.hrva.urlshortener.util.DomainNames;
import cc.hrva.urlshortener.util.ReviewReasons;
import java.io.IOException;
import java.nio.file.Files;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jsoup.Jsoup;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionOperations;

@Slf4j
@Service
@RequiredArgsConstructor
public class DefaultSignInFormCheckService implements SignInFormCheckService {

    private static final long MAX_PAGE_BYTES = 512L * 1024L;
    private static final Duration TIMEOUT = Duration.ofSeconds(5);
    private static final String WARNING_PAGE_STAYS = "the warning page stays";
    private static final String LINK_STAYS = "the link stays as it is";

    private final UrlRepository urlRepository;
    private final BrowserPageFetcher browserPageFetcher;
    private final ModerationService moderationService;
    private final ReviewQueueService reviewQueueService;
    private final RealSignInHosts realSignInHosts;
    private final NewAccounts newAccounts;
    private final SettingsService settingsService;
    private final TransactionOperations transactionOperations;

    @Override
    public boolean check(final long urlId) {
        final var url = urlRepository.findById(urlId).filter(link -> link.isActive() && link.getOwner() == null && link.isWarningPage());
        if (url.isEmpty()) {
            log.info("Link no longer needs its page checked for a sign-in form urlId={}", urlId);
            return false;
        }
        final var longUrl = url.get().getLongUrl();
        final var form = pageOf(urlId, longUrl, WARNING_PAGE_STAYS).flatMap(Page::form);
        if (form.isEmpty()) {
            return false;
        }
        final var held = moderationService.holdForSignInForm(urlId, longUrl, form.get());
        log.info("Sign-in form on a warning-page link urlId={} domain={} held={}", urlId, domainOf(longUrl), held);
        return held;
    }

    @Async(AsyncExecutorConfiguration.REDIRECT_CHECK_EXECUTOR)
    @Override
    public void checkLater(final long urlId) {
        check(urlId);
    }

    @Override
    public AccountCheckOutcome checkAccountLink(final long urlId) {
        if (!settingsService.isOn(SettingKey.SIGN_IN_FORM_ACCOUNT_LINKS)) {
            log.info("Sign-in form check on links from accounts is off urlId={}", urlId);
            return AccountCheckOutcome.SKIPPED;
        }
        final var link = Optional.ofNullable(transactionOperations.execute(status -> urlRepository.findById(urlId)
                .filter(DefaultSignInFormCheckService::needsAccountCheck)
                .orElse(null)));
        if (link.isEmpty()) {
            log.info("Link from an account no longer needs its page checked for a sign-in form urlId={}", urlId);
            return AccountCheckOutcome.SKIPPED;
        }
        final var longUrl = link.get().getLongUrl();
        final var page = pageOf(urlId, longUrl, LINK_STAYS);
        if (page.isEmpty()) {
            return AccountCheckOutcome.FETCH_FAILED;
        }
        final var form = page.get().form();
        if (form.isEmpty()) {
            return AccountCheckOutcome.NO_FORM;
        }
        final var finalHost = DomainNames.hostOf(page.get().finalUrl());
        if (finalHost.filter(realSignInHosts::isRealSignInHost).isPresent()) {
            log.info("Sign-in form on a real sign-in page urlId={} domain={} finalDomain={}", urlId, domainOf(longUrl), finalHost.get());
            return AccountCheckOutcome.REAL_SIGN_IN_PAGE;
        }
        return newAccounts.isNew(link.get().getOwner())
                ? held(urlId, longUrl, form.get())
                : noticed(link.get());
    }

    @Async(AsyncExecutorConfiguration.SIGN_IN_CHECK_EXECUTOR)
    @Override
    public void checkAccountLinkLater(final long urlId) {
        checkAccountLink(urlId);
    }

    private static boolean needsAccountCheck(final Url url) {
        return url.isActive() && url.getBlockReason() == null && url.getThreatType() == null && url.getOwner() != null
                && !AdminTwoStep.hasAdminPowers(url.getOwner());
    }

    private AccountCheckOutcome held(final long urlId, final String longUrl, final String form) {
        final var held = moderationService.holdOwnedLinkForSignInForm(urlId, longUrl, form, ReviewReasons.SIGN_IN_FORM_NEW_ACCOUNT);
        log.info("Sign-in form on a link from a new account urlId={} domain={} held={}", urlId, domainOf(longUrl), held);
        return held ? AccountCheckOutcome.HELD : AccountCheckOutcome.SKIPPED;
    }

    private AccountCheckOutcome noticed(final Url link) {
        reviewQueueService.notice(link, List.of(ReviewReasons.SIGN_IN_FORM_ACCOUNT));
        log.info("Sign-in form on a link from an account, a review notice is added urlId={} domain={}", link.getId(), domainOf(link.getLongUrl()));
        return AccountCheckOutcome.NOTICED;
    }

    private Optional<Page> pageOf(final long urlId, final String longUrl, final String outcome) {
        try {
            final var page = browserPageFetcher.fetch(longUrl, MAX_PAGE_BYTES, TIMEOUT);
            try {
                return Optional.of(new Page(CredentialForms.find(Jsoup.parse(page.body().toFile(), null, longUrl)), page.finalUrl()));
            } finally {
                Files.deleteIfExists(page.body());
            }
        } catch (final UrlValidationException exception) {
            log.info("Could not check the page for a sign-in form, {} urlId={} domain={} reason={}", outcome, urlId, domainOf(longUrl),
                    exception.getMessage());
        } catch (final IOException | RuntimeException exception) {
            log.warn("Could not check the page for a sign-in form, {} urlId={} domain={} exception={}", outcome, urlId, domainOf(longUrl),
                    exception.getClass().getName());
        }
        return Optional.empty();
    }

    private static String domainOf(final String url) {
        return DomainNames.hostOf(url).orElse("-");
    }

    private record Page(Optional<String> form, String finalUrl) {}

}
