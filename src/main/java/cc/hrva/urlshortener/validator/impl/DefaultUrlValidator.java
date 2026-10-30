package cc.hrva.urlshortener.validator.impl;

import cc.hrva.urlshortener.dto.LinkVerdict;
import cc.hrva.urlshortener.exception.*;
import cc.hrva.urlshortener.model.Url;
import cc.hrva.urlshortener.repository.UrlRepository;
import cc.hrva.urlshortener.service.LinkCheckService;
import cc.hrva.urlshortener.service.ReservedAliasService;
import cc.hrva.urlshortener.service.SafeBrowsingService;
import cc.hrva.urlshortener.service.SettingsService;
import cc.hrva.urlshortener.service.SuspiciousUrlService;
import cc.hrva.urlshortener.service.UserService;
import cc.hrva.urlshortener.service.settings.SettingKey;
import cc.hrva.urlshortener.util.DomainNames;
import cc.hrva.urlshortener.validator.UrlValidator;
import java.net.URI;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;
import org.springframework.web.util.UriComponentsBuilder;

import java.time.LocalDateTime;

@Component
@RequiredArgsConstructor
public class DefaultUrlValidator implements UrlValidator {

    private final UserService userService;
    private final UrlRepository urlRepository;
    private final SafeBrowsingService safeBrowsingService;
    private final SuspiciousUrlService suspiciousUrlService;
    private final LinkCheckService linkCheckService;
    private final ReservedAliasService reservedAliasService;
    private final SettingsService settingsService;

    private static final String BLOCKLISTED = "This destination is on a blocklist and can't be shortened. If you think that's a mistake, contact us.";
    private static final String STILL_BLOCKED = "This destination is still blocked: ";
    private static final String REASON_SEPARATOR = ", ";

    private static final int MAX_HOST_LENGTH = 253;
    private static final Pattern WEB_ADDRESS_PREFIX = Pattern.compile("^https?://[^/]", Pattern.CASE_INSENSITIVE);

    @Override
    public void longUrlInUrl(final Url url) {
        if (url.getLongUrl() == null) {
            throw new LongUrlNotSpecifiedException("URL is required");
        }
    }

    @Override
    public void checkIfLongUrlIsValid(final Url url) {
        if (StringUtils.length(url.getLongUrl()) > MAX_LONG_URL_LENGTH) {
            throw new UrlValidationException(LONG_URL_TOO_LONG);
        }
        if (!isWebAddress(url.getLongUrl())) {
            throw new UrlValidationException("That doesn't look like a valid web address. Check the URL and try again.");
        }
    }

    @Override
    public void checkIfLongUrlIsNotAShortener(final Url url) {
        if (suspiciousUrlService.isShortener(url.getLongUrl())) {
            throw new UrlValidationException("Links to other URL shorteners can't be shortened. Use the final destination instead.");
        }
    }

    @Override
    public LinkVerdict checkIfLongUrlIsNotBlocklisted(final Url url) {
        final var verdict = linkCheckService.check(url.getLongUrl());
        if (verdict.outcome() == LinkVerdict.Outcome.BLOCK) {
            throw new UrlValidationException(BLOCKLISTED);
        }
        return verdict;
    }

    private static boolean isWebAddress(final String longUrl) {
        if (longUrl == null || StringUtils.containsWhitespace(longUrl) || !WEB_ADDRESS_PREFIX.matcher(longUrl).find()) {
            return false;
        }
        try {
            URI.create(longUrl);
            final var host = UriComponentsBuilder.fromUriString(longUrl, UriComponentsBuilder.ParserType.WHAT_WG)
                    .build()
                    .getHost();
            return StringUtils.isNotEmpty(host) && (host.startsWith("[") || isHostName(host));
        } catch (final IllegalArgumentException exception) {
            return false;
        }
    }

    private static boolean isHostName(final String host) {
        return DomainNames.HOST_NAME.matcher(host).matches() && StringUtils.removeEnd(host, ".").length() <= MAX_HOST_LENGTH;
    }

    @Override
    public void checkIfShortUrlIsUnique(final Long domainId, final String shortUrl) {
        if (urlRepository.aliasTaken(domainId, shortUrl)) {
            throw new ShortUrlAlreadyExistsException(SHORT_URL_TAKEN);
        }
    }

    @Override
    public void checkIfShortUrlIsFreeToReactivate(final Long domainId, final String shortUrl) {
        if (urlRepository.aliasActive(domainId, shortUrl)) {
            throw new ShortUrlAlreadyExistsException("Another active link already uses this short URL.");
        }
    }

    @Override
    public void checkIfUrlNotBlocked(final Url url) {
        if ((url.getThreatType() != null || url.getBlockReason() != null) && !isCurrentUserAdmin()) {
            throw new UrlValidationException("This link was blocked as unsafe. Contact support if you think that's a mistake.");
        }
    }

    @Override
    public void checkIfDestinationNotBlocked(final Url url) {
        final var admin = isCurrentUserAdmin();
        if (admin && linkCheckService.isAllowListed(url.getLongUrl())) {
            return;
        }
        final var verdict = linkCheckService.check(url.getLongUrl());
        if (verdict.outcome() == LinkVerdict.Outcome.BLOCK) {
            throw new UrlValidationException(admin ? STILL_BLOCKED + String.join(REASON_SEPARATOR, verdict.reasons()) : BLOCKLISTED);
        }
    }

    @Override
    public void checkIfShortUrlIsReserved(final String shortUrl) {
        if (reservedAliasService.isReserved(shortUrl)) {
            throw new ShortUrlAlreadyExistsException("This short URL is reserved. Try another.");
        }
    }

    @Override
    public void verifyUserAdminOrOwner(final Url url) {
        final var currentUser = userService.getUserFromToken();
        if (currentUser == null) {
            throw new NoAuthorizationException("You don't have authorization for this action");
        }

        final var isCurrentUserAdmin = currentUser.getAuthorities().stream()
                .anyMatch(authorities -> authorities.getName().equals("ROLE_ADMIN"));

        final var owner = url.getOwner();
        final var isOwner = owner != null && currentUser.getId() != null && currentUser.getId().equals(owner.getId());
        if (!isOwner && !isCurrentUserAdmin) {
            throw new NoAuthorizationException("You don't have authorization for this action");
        }
    }

    @Override
    public void verifyUserAdmin() {
        if (!isCurrentUserAdmin()) {
            throw new NoAuthorizationException("Only admins can change where a link points");
        }
    }

    private boolean isCurrentUserAdmin() {
        final var currentUser = userService.getUserFromToken();
        return currentUser != null && currentUser.getAuthorities().stream()
                .anyMatch(authorities -> authorities.getName().equals("ROLE_ADMIN"));
    }

    @Override
    public void checkIfUrlExpirationDateIsInThePast(final Url url) {
        if (url.getExpirationDate() != null && LocalDateTime.now().isAfter(url.getExpirationDate())) {
            throw new UrlValidationException("Expiration date can't be in the past");
        }
    }

    @Override
    public void checkIfUrlSafe(final Url url) {
        if (!settingsService.isOn(SettingKey.SAFE_BROWSING_CHECK)) {
            return;
        }
        if (!safeBrowsingService.checkUrlsForThreats(url.getLongUrl()).isEmpty()) {
            throw new UrlValidationException("This URL was flagged as unsafe and cannot be shortened");
        }
    }

    @Override
    public void checkIfAnonymousUrlCreationEnabled() {
        if (!settingsService.isOn(SettingKey.ANONYMOUS_LINKS)) {
            throw new ApiException("Sign in to create short URLs");
        }
    }

}

