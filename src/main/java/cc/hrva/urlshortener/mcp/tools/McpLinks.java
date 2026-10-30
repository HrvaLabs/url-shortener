package cc.hrva.urlshortener.mcp.tools;

import cc.hrva.urlshortener.dto.CreateUrlDto;
import cc.hrva.urlshortener.dto.DomainRef;
import cc.hrva.urlshortener.exception.UrlValidationException;
import cc.hrva.urlshortener.service.domain.CustomDomainHostnames;
import cc.hrva.urlshortener.service.domain.ShortLinks;
import cc.hrva.urlshortener.util.TagNormalizer;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.apache.commons.lang3.StringUtils;

public final class McpLinks {

    private static final String AI_TAG = "ai";
    private static final String SLASH = "/";
    private static final Pattern SCHEME = Pattern.compile("^[a-zA-Z][a-zA-Z0-9+.-]*://");

    private McpLinks() {}

    public static Set<String> withAiTag(final List<String> tags) {
        final var requested = Objects.requireNonNullElse(tags, List.<String>of());
        return TagNormalizer.normalize(Stream.concat(Stream.of(AI_TAG), requested.stream()).toList());
    }

    public static void validate(final Validator validator, final CreateUrlDto createUrlDto) {
        final var violations = validator.validate(createUrlDto);
        if (!violations.isEmpty()) {
            throw new UrlValidationException(violations.stream()
                    .map(ConstraintViolation::getMessage)
                    .sorted()
                    .collect(Collectors.joining("; ")));
        }
    }

    public static String shortLink(final String issuer, final String alias) {
        return "%s/%s".formatted(issuer, alias);
    }

    public static String shortLink(final String issuer, final ShortLinks shortLinks, final String hostname, final String alias) {
        return StringUtils.isBlank(hostname) ? shortLink(issuer, alias) : shortLinks.url(hostname, alias);
    }

    public record RequestedLink(String hostname, String alias, boolean hostGiven) {

        public boolean matches(final DomainRef domain) {
            if (!hostGiven) {
                return true;
            }
            if (hostname == null) {
                return domain == null;
            }
            return domain != null && CustomDomainHostnames.hostnameKey(domain.hostname()).filter(hostname::equals).isPresent();
        }

    }

    public static RequestedLink requestedLink(final String link, final String issuerHost) {
        final var withoutScheme = SCHEME.matcher(StringUtils.trimToEmpty(link)).replaceFirst("");
        if (StringUtils.equalsIgnoreCase(withoutScheme, issuerHost)) {
            return new RequestedLink(null, "", true);
        }
        if (StringUtils.startsWithIgnoreCase(withoutScheme, issuerHost + SLASH)) {
            return new RequestedLink(null, StringUtils.strip(withoutScheme.substring(issuerHost.length() + 1), SLASH), true);
        }
        final var slash = withoutScheme.indexOf(SLASH);
        final var alias = slash > 0 ? StringUtils.strip(withoutScheme.substring(slash + 1), SLASH) : "";
        if (alias.isEmpty()) {
            return new RequestedLink(null, StringUtils.strip(withoutScheme, SLASH), false);
        }
        final var host = withoutScheme.substring(0, slash);
        return new RequestedLink(CustomDomainHostnames.hostnameKey(host).orElse(host.toLowerCase(Locale.ROOT)), alias, true);
    }

}
