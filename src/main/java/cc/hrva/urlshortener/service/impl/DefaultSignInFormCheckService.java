package cc.hrva.urlshortener.service.impl;

import cc.hrva.urlshortener.client.BrowserPageFetcher;
import cc.hrva.urlshortener.configuration.AsyncExecutorConfiguration;
import cc.hrva.urlshortener.exception.UrlValidationException;
import cc.hrva.urlshortener.repository.UrlRepository;
import cc.hrva.urlshortener.service.ModerationService;
import cc.hrva.urlshortener.service.SignInFormCheckService;
import cc.hrva.urlshortener.service.signin.CredentialForms;
import cc.hrva.urlshortener.util.DomainNames;
import java.io.IOException;
import java.nio.file.Files;
import java.time.Duration;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jsoup.Jsoup;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class DefaultSignInFormCheckService implements SignInFormCheckService {

    private static final long MAX_PAGE_BYTES = 512L * 1024L;
    private static final Duration TIMEOUT = Duration.ofSeconds(5);

    private final UrlRepository urlRepository;
    private final BrowserPageFetcher browserPageFetcher;
    private final ModerationService moderationService;

    @Override
    public boolean check(final long urlId) {
        final var url = urlRepository.findById(urlId).filter(link -> link.isActive() && link.getOwner() == null && link.isWarningPage());
        if (url.isEmpty()) {
            log.info("Link no longer needs its page checked for a sign-in form urlId={}", urlId);
            return false;
        }
        final var longUrl = url.get().getLongUrl();
        final var form = credentialFormOn(urlId, longUrl);
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

    private Optional<String> credentialFormOn(final long urlId, final String longUrl) {
        try {
            final var page = browserPageFetcher.fetch(longUrl, MAX_PAGE_BYTES, TIMEOUT);
            try {
                return CredentialForms.find(Jsoup.parse(page.body().toFile(), null, longUrl));
            } finally {
                Files.deleteIfExists(page.body());
            }
        } catch (final UrlValidationException exception) {
            log.info("Could not check the page for a sign-in form, the warning page stays urlId={} domain={} reason={}", urlId, domainOf(longUrl),
                    exception.getMessage());
        } catch (final IOException | RuntimeException exception) {
            log.warn("Could not check the page for a sign-in form, the warning page stays urlId={} domain={} exception={}", urlId,
                    domainOf(longUrl), exception.getClass().getName());
        }
        return Optional.empty();
    }

    private static String domainOf(final String url) {
        return DomainNames.hostOf(url).orElse("-");
    }

}
