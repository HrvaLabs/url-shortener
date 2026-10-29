package cc.hrva.urlshortener.service.notification;

import cc.hrva.urlshortener.configuration.properties.AppProperties;
import cc.hrva.urlshortener.controller.UnsubscribeController;
import cc.hrva.urlshortener.model.enums.NotificationType;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class ConsentEmails {

    public static final String LIST_UNSUBSCRIBE = "List-Unsubscribe";
    public static final String LIST_UNSUBSCRIBE_POST = "List-Unsubscribe-Post";
    public static final String ONE_CLICK = "List-Unsubscribe=One-Click";

    private static final String TOKEN_QUERY = "?token=";
    private static final String UNSUBSCRIBE_PAGE = "/unsubscribe";
    private static final String SETTINGS_PAGE = "/dashboard/settings#notifications";

    private final UnsubscribeTokens tokens;
    private final AppProperties appProperties;

    public Map<String, String> headers(final long userId, final NotificationType type) {
        final var headers = new LinkedHashMap<String, String>();
        headers.put(LIST_UNSUBSCRIBE, "<" + base(appProperties.getServerUrl()) + UnsubscribeController.UNSUBSCRIBE_ENDPOINT + TOKEN_QUERY
                + tokens.issue(userId, type) + ">");
        headers.put(LIST_UNSUBSCRIBE_POST, ONE_CLICK);
        return Collections.unmodifiableMap(headers);
    }

    public String unsubscribePage(final long userId, final NotificationType type) {
        return base(appProperties.getFrontendUrl()) + UNSUBSCRIBE_PAGE + TOKEN_QUERY + tokens.issue(userId, type);
    }

    public String settingsPage() {
        return base(appProperties.getFrontendUrl()) + SETTINGS_PAGE;
    }

    private static String base(final String url) {
        return StringUtils.removeEnd(StringUtils.defaultString(url), "/");
    }

}
