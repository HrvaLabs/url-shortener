package cc.hrva.urlshortener.mcp.tools;

import cc.hrva.urlshortener.dto.CreateUrlDto;
import cc.hrva.urlshortener.exception.UrlValidationException;
import cc.hrva.urlshortener.util.TagNormalizer;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public final class McpLinks {

    private static final String AI_TAG = "ai";

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

}
