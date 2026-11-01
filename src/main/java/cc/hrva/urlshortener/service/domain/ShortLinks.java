package cc.hrva.urlshortener.service.domain;

import cc.hrva.urlshortener.configuration.properties.AppProperties;
import cc.hrva.urlshortener.model.Url;
import java.net.URI;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;
import org.springframework.web.util.UriComponentsBuilder;

@Component
@RequiredArgsConstructor
public class ShortLinks {

    private static final String HTTPS = "https://";
    private static final String SCHEME_SEPARATOR = "://";
    private static final String SLASH = "/";
    private static final String PEEK_PATH = "/api/v1/urls/{alias}/peek";
    private static final String DOMAIN_PARAMETER = "domain";

    private final AppProperties appProperties;

    public String url(final String hostname, final String alias) {
        return (StringUtils.isBlank(hostname) ? serverUrl() : HTTPS + hostname) + SLASH + alias;
    }

    public URI peekLocation(final String hostname, final String alias) {
        return UriComponentsBuilder.fromUriString(serverUrl())
                .path(PEEK_PATH)
                .queryParamIfPresent(DOMAIN_PARAMETER, Optional.ofNullable(hostname).filter(StringUtils::isNotBlank))
                .buildAndExpand(alias)
                .encode()
                .toUri();
    }

    public String display(final String hostname, final String alias) {
        return host(hostname) + SLASH + alias;
    }

    public String host(final String hostname) {
        if (StringUtils.isNotBlank(hostname)) {
            return hostname;
        }
        final var serverUrl = serverUrl();
        return serverUrl.contains(SCHEME_SEPARATOR) ? StringUtils.substringAfter(serverUrl, SCHEME_SEPARATOR) : serverUrl;
    }

    public static String hostnameOf(final Url url) {
        return url.getDomain() == null ? null : url.getDomain().getHostname();
    }

    private String serverUrl() {
        return StringUtils.removeEnd(StringUtils.defaultString(appProperties.getServerUrl()), SLASH);
    }

}
