package cc.hrva.urlshortener.service.impl;

import cc.hrva.urlshortener.client.SiteverifyResult;
import cc.hrva.urlshortener.client.TurnstileClient;
import cc.hrva.urlshortener.configuration.properties.AppProperties;
import cc.hrva.urlshortener.exception.HumanCheckUnavailableException;
import cc.hrva.urlshortener.exception.UrlValidationException;
import cc.hrva.urlshortener.service.HumanCheckService;
import cc.hrva.urlshortener.service.SettingsService;
import cc.hrva.urlshortener.service.settings.SettingKey;
import java.net.URI;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class DefaultHumanCheckService implements HumanCheckService {

    private static final String REFUSAL = "Complete the check and try again.";
    private static final String UNAVAILABLE = "We couldn't run the check. Try again in a minute.";
    private static final int MAX_TOKEN_LENGTH = 2048;
    private static final Set<String> FAILURES_NOT_THE_VISITORS = Set.of("missing-input-secret", "invalid-input-secret", "internal-error");

    private final SettingsService settingsService;
    private final TurnstileClient turnstileClient;
    private final AppProperties appProperties;

    @Override
    public void verify(final String token) {
        if (!settingsService.isOnAndAvailable(SettingKey.HUMAN_CHECK)) {
            return;
        }
        if (StringUtils.isBlank(token) || token.length() > MAX_TOKEN_LENGTH) {
            log.info("Human check refused: no usable token");
            throw new UrlValidationException(REFUSAL);
        }
        switch (turnstileClient.verify(token)) {
            case SiteverifyResult.Unreachable unreachable -> {
                log.warn("Human check could not run, refusing the link reason={}", unreachable.reason());
                throw new HumanCheckUnavailableException(UNAVAILABLE);
            }
            case SiteverifyResult.Answered answered when answered.errorCodes().stream().anyMatch(FAILURES_NOT_THE_VISITORS::contains) -> {
                log.error("Human check could not run, refusing the link errorCodes={}", answered.errorCodes());
                throw new HumanCheckUnavailableException(UNAVAILABLE);
            }
            case SiteverifyResult.Answered answered when !answered.success() -> {
                log.info("Human check refused errorCodes={}", answered.errorCodes());
                throw new UrlValidationException(REFUSAL);
            }
            case SiteverifyResult.Answered answered when !isFrontendHost(answered.hostname()) -> {
                log.info("Human check refused: token from another site hostname={}", answered.hostname());
                throw new UrlValidationException(REFUSAL);
            }
            case SiteverifyResult.Answered _ -> log.debug("Human check passed");
        }
    }

    private boolean isFrontendHost(final String hostname) {
        return hostname != null && Strings.CI.equals(hostname, URI.create(appProperties.getFrontendUrl()).getHost());
    }

}
