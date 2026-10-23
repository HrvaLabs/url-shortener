package cc.hrva.urlshortener.service.analytics;

import cc.hrva.urlshortener.configuration.properties.AppProperties;
import cc.hrva.urlshortener.dto.ClickRequest;
import cc.hrva.urlshortener.util.DomainNames;
import java.util.Locale;
import lombok.RequiredArgsConstructor;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class ReferrerClassifier {

    private static final String UNKNOWN = "unknown";
    private static final String DIRECT = "direct";
    private static final String OTHER = "other";
    private static final String APP_PREFIX = "app:";
    private static final String ANDROID_APP_SCHEME = "android-app://";
    private static final String HTTP_SCHEME = "http://";
    private static final String HTTPS_SCHEME = "https://";
    private static final String WWW = "www.";
    private static final int MAX_LENGTH = 255;

    private final AppProperties appProperties;

    public String classify(final ClickRequest click) {
        if (!click.referrerKnown()) {
            return UNKNOWN;
        }
        if (StringUtils.isBlank(click.referer())) {
            return DIRECT;
        }

        try {
            return referrerOf(click.referer());
        } catch (final Exception exception) {
            return OTHER;
        }
    }

    private String referrerOf(final String referer) {
        final var lowerCase = referer.toLowerCase(Locale.ROOT);
        final var app = lowerCase.startsWith(ANDROID_APP_SCHEME);
        if (!app && !StringUtils.startsWithAny(lowerCase, HTTP_SCHEME, HTTPS_SCHEME)) {
            return OTHER;
        }

        final var host = hostWithoutWww(referer);
        if (!DomainNames.HOST_NAME.matcher(host).matches() || DomainNames.isIpLiteral(host)) {
            return OTHER;
        }

        final var referrer = app ? APP_PREFIX + host : ownHostOr(host);
        return referrer.length() > MAX_LENGTH ? OTHER : referrer;
    }

    private String ownHostOr(final String host) {
        final var serverHost = hostWithoutWww(appProperties.getServerUrl());
        if (host.equals(serverHost) || host.equals(hostWithoutWww(appProperties.getFrontendUrl()))) {
            return serverHost;
        }

        return host;
    }

    private static String hostWithoutWww(final String url) {
        return DomainNames.hostOf(url)
                .map(host -> StringUtils.removeStart(host.toLowerCase(Locale.ROOT), WWW))
                .orElse("");
    }

}
