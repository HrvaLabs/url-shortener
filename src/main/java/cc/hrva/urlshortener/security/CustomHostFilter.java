package cc.hrva.urlshortener.security;

import cc.hrva.urlshortener.configuration.properties.AppProperties;
import cc.hrva.urlshortener.exception.UrlNotFoundException;
import cc.hrva.urlshortener.service.ReservedAliasService;
import cc.hrva.urlshortener.service.UrlService;
import cc.hrva.urlshortener.service.domain.DomainRoute;
import cc.hrva.urlshortener.service.domain.HostRouting;
import com.google.common.base.Ticker;
import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.UriUtils;

@Slf4j
@Component
public class CustomHostFilter extends OncePerRequestFilter {

    private static final Set<String> SERVED_METHODS = Set.of(HttpMethod.GET.name(), HttpMethod.HEAD.name());
    private static final String ROOT = "/";
    private static final String HANDLE_PREFIX = "@";
    private static final String PATH_PARAMETER = ";";
    private static final String NOT_FOUND = "Not found";
    private static final String FAILED = "Something went wrong";
    private static final String NO_STORE = "no-cache, no-store, max-age=0, must-revalidate";
    private static final String NO_SNIFF = "nosniff";
    private static final String X_CONTENT_TYPE_OPTIONS = "X-Content-Type-Options";
    private static final Duration UNKNOWN_HOST_LOG_INTERVAL = Duration.ofHours(1);
    private static final int MAX_LOGGED_HOSTS = 1_000;
    private static final int MAX_HOST_LENGTH = 253;

    private final HostRouting hostRouting;
    private final UrlService urlService;
    private final ClickRequestResolver clickRequestResolver;
    private final AppProperties appProperties;
    private final Cache<String, Boolean> loggedUnknownHosts;

    public CustomHostFilter(final HostRouting hostRouting, final UrlService urlService, final ClickRequestResolver clickRequestResolver,
                            final AppProperties appProperties, final Clock clock) {
        this.hostRouting = hostRouting;
        this.urlService = urlService;
        this.clickRequestResolver = clickRequestResolver;
        this.appProperties = appProperties;
        this.loggedUnknownHosts = CacheBuilder.newBuilder()
                .ticker(tickerOf(clock))
                .expireAfterWrite(UNKNOWN_HOST_LOG_INTERVAL)
                .maximumSize(MAX_LOGGED_HOSTS)
                .build();
    }

    @Override
    protected void doFilterInternal(final HttpServletRequest request, final HttpServletResponse response, final FilterChain chain)
            throws ServletException, IOException {
        switch (hostRouting.classify(request.getServerName())) {
            case HostRouting.Primary _ -> chain.doFilter(request, response);
            case HostRouting.Custom custom -> serveSafely(custom.route(), request, response);
            case HostRouting.Unknown _ -> refuseUnknownHost(request, response);
        }
    }

    @Override
    protected boolean shouldNotFilterErrorDispatch() {
        return false;
    }

    private void serveSafely(final DomainRoute route, final HttpServletRequest request, final HttpServletResponse response) throws IOException {
        try {
            serve(route, request, response);
        } catch (final UrlNotFoundException exception) {
            notFound(response);
        } catch (final RuntimeException exception) {
            log.warn("Custom host redirect failed domainId={} exception={}", route.id(), exception.getClass().getName());
            plain(response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR, FAILED);
        }
    }

    private void serve(final DomainRoute route, final HttpServletRequest request, final HttpServletResponse response) throws IOException {
        if (request.getDispatcherType() != DispatcherType.REQUEST || !SERVED_METHODS.contains(request.getMethod())) {
            notFound(response);
            return;
        }
        final var path = StringUtils.removeStart(request.getRequestURI(), request.getContextPath());
        if (ROOT.equals(path)) {
            redirect(response, homePageOf(appProperties.getFrontendUrl()));
            return;
        }
        final var alias = aliasOf(path);
        if (alias.isEmpty()) {
            notFound(response);
            return;
        }
        redirect(response, urlService.redirectResultUrl(route, alias.get(), clickRequestResolver.resolve(request)).getUrl());
    }

    private static String homePageOf(final String frontendUrl) {
        return frontendUrl.endsWith(ROOT) ? frontendUrl : frontendUrl + ROOT;
    }

    private static Optional<String> aliasOf(final String path) {
        if (!path.startsWith(ROOT) || path.indexOf('/', 1) >= 0) {
            return Optional.empty();
        }
        try {
            return Optional.of(UriUtils.decode(path.substring(1), StandardCharsets.UTF_8))
                    .filter(StringUtils::isNotEmpty)
                    .filter(alias -> !alias.startsWith(HANDLE_PREFIX))
                    .filter(alias -> !alias.contains(PATH_PARAMETER))
                    .filter(alias -> !ReservedAliasService.BUILT_IN_ALIASES.contains(alias.toLowerCase(Locale.ROOT)));
        } catch (final IllegalArgumentException exception) {
            return Optional.empty();
        }
    }

    private void refuseUnknownHost(final HttpServletRequest request, final HttpServletResponse response) throws IOException {
        final var host = StringUtils.left(StringUtils.defaultString(request.getServerName()), MAX_HOST_LENGTH).toLowerCase(Locale.ROOT);
        if (loggedUnknownHosts.asMap().putIfAbsent(host, Boolean.TRUE) == null) {
            log.info("Unknown host refused host={}", StringUtils.replaceChars(host, "\r\n\t", "   "));
        }
        notFound(response);
    }

    private static void redirect(final HttpServletResponse response, final String location) {
        neverCached(response);
        response.setStatus(HttpServletResponse.SC_FOUND);
        response.setHeader(HttpHeaders.LOCATION, location);
    }

    private static void notFound(final HttpServletResponse response) throws IOException {
        plain(response, HttpServletResponse.SC_NOT_FOUND, NOT_FOUND);
    }

    private static void plain(final HttpServletResponse response, final int status, final String body) throws IOException {
        if (response.isCommitted()) {
            return;
        }
        response.reset();
        neverCached(response);
        response.setStatus(status);
        response.setContentType(MediaType.TEXT_PLAIN_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write(body);
    }

    private static void neverCached(final HttpServletResponse response) {
        response.setHeader(HttpHeaders.CACHE_CONTROL, NO_STORE);
        response.setHeader(HttpHeaders.PRAGMA, "no-cache");
        response.setHeader(HttpHeaders.EXPIRES, "0");
        response.setHeader(X_CONTENT_TYPE_OPTIONS, NO_SNIFF);
    }

    private static Ticker tickerOf(final Clock clock) {
        return new Ticker() {
            @Override
            public long read() {
                return TimeUnit.MILLISECONDS.toNanos(clock.millis());
            }
        };
    }

}
