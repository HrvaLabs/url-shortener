package cc.hrva.urlshortener.service.alias;

import java.util.regex.Pattern;
import cc.hrva.urlshortener.exception.FieldValidationException;
import cc.hrva.urlshortener.exception.ShortUrlAlreadyExistsException;
import cc.hrva.urlshortener.exception.UrlValidationException;
import cc.hrva.urlshortener.service.settings.SettingKey;
import cc.hrva.urlshortener.repository.UrlRepository;
import cc.hrva.urlshortener.service.ReservedAliasService;
import cc.hrva.urlshortener.service.SettingsService;
import cc.hrva.urlshortener.service.SuspiciousUrlService;
import cc.hrva.urlshortener.util.BotProbeNames;
import cc.hrva.urlshortener.validator.UrlValidator;
import lombok.RequiredArgsConstructor;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class AliasRules {

    public static final String CUSTOM_ALIASES_OFF = "Custom aliases are turned off right now.";
    public static final String RESERVED = "This short URL is reserved. Try another.";
    public static final String BRAND = "This name contains the brand name %s, so it can't be used on hrva.cc. Try another.";
    public static final String PATTERN_REFUSAL = "Use 1 to 32 letters, numbers, hyphens or underscores.";

    private static final Pattern ALIAS = Pattern.compile("[a-zA-Z0-9_-]{1,32}");

    private final SettingsService settingsService;
    private final ReservedAliasService reservedAliasService;
    private final SuspiciousUrlService suspiciousUrlService;
    private final UrlRepository urlRepository;

    public AliasDecision decide(final String alias, final Long domainId, final boolean brandExempt) {
        if (domainId == null && !settingsService.isOn(SettingKey.CUSTOM_ALIASES)) {
            return new AliasDecision(AliasStatus.DISABLED, CUSTOM_ALIASES_OFF);
        }
        if (StringUtils.isBlank(alias) || !ALIAS.matcher(alias).matches()) {
            return new AliasDecision(AliasStatus.INVALID, PATTERN_REFUSAL);
        }
        return decideExisting(alias, domainId, brandExempt);
    }

    public AliasDecision decideExisting(final String alias, final Long domainId, final boolean brandExempt) {
        if (reservedAliasService.isReserved(alias) || BotProbeNames.contains(alias)) {
            return new AliasDecision(AliasStatus.RESERVED, RESERVED);
        }
        if (domainId == null && !brandExempt) {
            final var brand = suspiciousUrlService.findBrandInHandle(alias);
            if (brand.isPresent()) {
                return new AliasDecision(AliasStatus.RESERVED, BRAND.formatted(brand.get()));
            }
        }
        if (urlRepository.aliasTakenIgnoringCase(domainId, alias)) {
            return new AliasDecision(AliasStatus.TAKEN, UrlValidator.SHORT_URL_TAKEN);
        }
        return AliasDecision.available();
    }

    public void require(final String alias, final Long domainId, final boolean brandExempt) {
        final var decision = decide(alias, domainId, brandExempt);
        switch (decision.status()) {
            case AVAILABLE -> {
            }
            case DISABLED -> throw new UrlValidationException(decision.reason());
            case TAKEN, RESERVED -> throw new ShortUrlAlreadyExistsException(decision.reason());
            case INVALID -> throw new FieldValidationException("shortUrl", decision.reason());
        }
    }
}
