package cc.hrva.urlshortener.validator.impl;

import cc.hrva.urlshortener.security.AdminTwoStep;
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
import cc.hrva.urlshortener.model.enums.BlockReason;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
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
    private static final String SAFE_BROWSING_REASON = "Google Safe Browsing: ";

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
    public void checkIfLongUrlIsNotOwnLink(final Url url) {
        if (suspiciousUrlService.isOwnLink(url.getLongUrl())) {
            throw new UrlValidationException("Links to hrva.cc can't be shortened again. Use the final destination instead.");
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

    @Override
    public LinkVerdict destinationVerdict(final Url url) {
        final var verdict = isCurrentUserAdmin() && linkCheckService.isAllowListed(url.getLongUrl())
                ? LinkVerdict.clean()
                : linkCheckService.check(url.getLongUrl());
        final var threats = settingsService.isOn(SettingKey.SAFE_BROWSING_CHECK) ? safeBrowsingService.checkUrlsForThreats(url.getLongUrl()) : List.<String>of();
        if (threats.isEmpty()) {
            return verdict;
        }
        final var reasons = new ArrayList<>(verdict.reasons());
        threats.stream().map(threat -> SAFE_BROWSING_REASON + threat).forEach(reasons::add);
        return new LinkVerdict(LinkVerdict.Outcome.BLOCK, List.copyOf(reasons), BlockReason.SAFE_BROWSING, String.join(REASON_SEPARATOR, threats));
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
    public void checkIfShortUrlIsFreeToReactivate(final Long domainId, final String shortUrl) {
        if (urlRepository.aliasActive(domainId, shortUrl)) {
            throw new ShortUrlAlreadyExistsException("Another active link already uses this short URL.");
        }
    }

    @Override
    public void checkIfUrlNotBlocked(final Url url) {
        if (isCurrentUserAdmin()) {
            return;
        }
        if (url.getThreatType() == null && url.getBlockReason() != null && BlockReason.HOLDS.contains(url.getBlockReason())) {
            throw new UrlValidationException("This link is being checked. It comes back on its own if it looks fine.");
        }
        if (url.getThreatType() != null || url.getBlockReason() != null) {
            throw new UrlValidationException("This link was blocked as unsafe. Contact support if you think that's a mistake.");
        }
    }

    @Override
    public LinkVerdict checkIfDestinationNotBlocked(final Url url) {
        final var admin = isCurrentUserAdmin();
        if (admin && linkCheckService.isAllowListed(url.getLongUrl())) {
            return LinkVerdict.clean();
        }
        final var verdict = linkCheckService.check(url.getLongUrl());
        if (verdict.outcome() == LinkVerdict.Outcome.BLOCK) {
            throw new UrlValidationException(admin ? STILL_BLOCKED + String.join(REASON_SEPARATOR, verdict.reasons()) : BLOCKLISTED);
        }
        return verdict;
    }

    @Override
    public void verifyUserAdminOrOwner(final Url url) {
        final var currentUser = userService.getUserFromToken();
        if (currentUser == null) {
            throw new NoAuthorizationException("You don't have authorization for this action");
        }

        final var owner = url.getOwner();
        final var isOwner = owner != null && currentUser.getId() != null && currentUser.getId().equals(owner.getId());
        if (!isOwner && !AdminTwoStep.hasAdminPowers(currentUser)) {
            throw new NoAuthorizationException(AdminTwoStep.refusalFor(currentUser, "You don't have authorization for this action"));
        }
    }

    private boolean isCurrentUserAdmin() {
        return AdminTwoStep.hasAdminPowers(userService.getUserFromToken());
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

