package cc.hrva.urlshortener.validator.impl;

import cc.hrva.urlshortener.configuration.properties.AppProperties;
import cc.hrva.urlshortener.exception.*;
import cc.hrva.urlshortener.model.Url;
import cc.hrva.urlshortener.repository.UrlRepository;
import cc.hrva.urlshortener.service.SafeBrowsingService;
import cc.hrva.urlshortener.service.UserService;
import cc.hrva.urlshortener.validator.UrlValidator;
import java.util.List;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;
import org.springframework.web.util.InvalidUrlException;
import org.springframework.web.util.UriComponentsBuilder;

import java.time.LocalDateTime;

@Component
@RequiredArgsConstructor
public class DefaultUrlValidator implements UrlValidator {

    private final UserService userService;
    private final UrlRepository urlRepository;
    private final AppProperties appProperties;
    private final SafeBrowsingService safeBrowsingService;

    private static final List<String> RESERVED_WORDS = List.of(
            "api", "login", "logout", "oauth2", "actuator", "error",
            "swagger-ui", "v3", "webjars", "auth");

    private static final Pattern WEB_ADDRESS_PREFIX = Pattern.compile("^https?://[^/]", Pattern.CASE_INSENSITIVE);

    @Override
    public void longUrlInUrl(final Url url) {
        if (url.getLongUrl() == null) {
            throw new LongUrlNotSpecifiedException("URL is required");
        }
    }

    @Override
    public void checkIfLongUrlIsValid(final Url url) {
        if (!isWebAddress(url.getLongUrl())) {
            throw new UrlValidationException("That doesn't look like a valid web address. Check the URL and try again.");
        }
    }

    private static boolean isWebAddress(final String longUrl) {
        if (longUrl == null || StringUtils.containsWhitespace(longUrl) || !WEB_ADDRESS_PREFIX.matcher(longUrl).find()) {
            return false;
        }
        try {
            final var host = UriComponentsBuilder.fromUriString(longUrl, UriComponentsBuilder.ParserType.WHAT_WG)
                    .build()
                    .getHost();
            return StringUtils.isNotEmpty(host) && (host.contains(".") || host.startsWith("["));
        } catch (final InvalidUrlException exception) {
            return false;
        }
    }

    @Override
    public void checkIfShortUrlIsUnique(final String shortUrl) {
        if (urlRepository.existsUrlByShortUrlAndActiveTrue(shortUrl)) {
            throw new ShortUrlAlreadyExistsException("This short URL is already taken. Try another.");
        }
    }

    @Override
    public void checkIfShortUrlIsReserved(final String shortUrl) {
        if (shortUrl != null && RESERVED_WORDS.contains(shortUrl.toLowerCase())) {
            throw new ShortUrlAlreadyExistsException("This short URL is reserved. Try another.");
        }
    }

    @Override
    public void verifyUserAdminOrOwner(final Url url) {
        final var currentUser = userService.getUserFromToken();
        final var isCurrentUserAdmin = currentUser.getAuthorities().stream()
                .anyMatch(authorities -> authorities.getName().equals("ROLE_ADMIN"));

        if (!url.getOwner().equals(currentUser) && !isCurrentUserAdmin) {
            throw new NoAuthorizationException("You don't have authorization for this action");
        }
    }

    @Override
    public void checkIfUrlExpirationDateIsInThePast(final Url url) {
        if (url.getExpirationDate() != null && LocalDateTime.now().isAfter(url.getExpirationDate())) {
            throw new UrlValidationException("Expiration date can't be in the past");
        }
    }

    @Override
    public void checkIfUrlSafe(final Url url) {
        if (!safeBrowsingService.checkUrlsForThreats(url.getLongUrl()).isEmpty()) {
            throw new UrlValidationException("This URL was flagged as unsafe and cannot be shortened");
        }
    }

    @Override
    public void checkIfAnonymousUrlCreationEnabled() {
        if (!appProperties.isAnonymousUrlCreationEnabled()) {
            throw new ApiException("Sign in to create short URLs");
        }
    }

}

