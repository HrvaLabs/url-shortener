package cc.hrva.urlshortener.service.alias;

import java.util.Arrays;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import cc.hrva.urlshortener.exception.FieldValidationException;
import cc.hrva.urlshortener.exception.ShortUrlAlreadyExistsException;
import cc.hrva.urlshortener.exception.UrlValidationException;
import cc.hrva.urlshortener.service.settings.SettingKey;
import cc.hrva.urlshortener.repository.UrlRepository;
import cc.hrva.urlshortener.service.ReservedAliasService;
import cc.hrva.urlshortener.service.SettingsService;
import cc.hrva.urlshortener.service.SuspiciousUrlService;
import cc.hrva.urlshortener.service.bio.BioHandles;
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
    public static final String LOOKS_LIKE_A_PAGE = "This name looks like one of hrva.cc's own pages, so it can't be used. Try another.";
    public static final String PATTERN_REFUSAL = "Use 1 to 32 letters, numbers, hyphens or underscores.";

    private static final Pattern ALIAS = Pattern.compile("[a-zA-Z0-9_-]{1,32}");
    private static final String PART_SEPARATORS = "-_";
    private static final Set<String> ACCOUNT_FLOW_WORDS = Set.of("password", "passwd", "signin", "signup", "login", "logout", "verify",
            "verification", "reset", "resetpassword", "otp", "2fa", "passkey");

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
            if (looksLikeOwnPage(alias)) {
                return new AliasDecision(AliasStatus.RESERVED, LOOKS_LIKE_A_PAGE);
            }
        }
        if (urlRepository.aliasTakenIgnoringCase(domainId, alias)) {
            return new AliasDecision(AliasStatus.TAKEN, UrlValidator.SHORT_URL_TAKEN);
        }
        return AliasDecision.available();
    }

    private static boolean looksLikeOwnPage(final String alias) {
        final var lower = alias.toLowerCase(Locale.ROOT);
        final var parts = StringUtils.split(lower, PART_SEPARATORS);
        final var joined = String.join("", parts);
        return BioHandles.SYSTEM_WORDS.contains(lower) || BioHandles.SYSTEM_WORDS.contains(joined)
                || parts.length > 0 && BioHandles.SERVICE_NAMES.contains(parts[0]) || ACCOUNT_FLOW_WORDS.contains(joined)
                || Arrays.stream(parts).anyMatch(ACCOUNT_FLOW_WORDS::contains);
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
