package cc.hrva.urlshortener.service.domain;

import cc.hrva.urlshortener.configuration.properties.AppProperties;
import cc.hrva.urlshortener.model.Url;
import lombok.RequiredArgsConstructor;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class ShortLinks {

    private static final String HTTPS = "https://";
    private static final String SCHEME_SEPARATOR = "://";
    private static final String SLASH = "/";

    private final AppProperties appProperties;

    public String url(final String hostname, final String alias) {
        return (StringUtils.isBlank(hostname) ? serverUrl() : HTTPS + hostname) + SLASH + alias;
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
