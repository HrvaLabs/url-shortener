package cc.hrva.urlshortener.util;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;
import org.apache.commons.lang3.StringUtils;

public final class TagNormalizer {

    private static final int MAX_TAGS = 10;
    private static final int MAX_TAG_LENGTH = 30;

    private TagNormalizer() {}

    public static Set<String> normalize(final Collection<String> tags) {
        if (tags == null) {
            return new LinkedHashSet<>();
        }
        return tags.stream()
                .filter(StringUtils::isNotBlank)
                .map(tag -> tag.trim().toLowerCase(Locale.ROOT))
                .map(tag -> tag.length() > MAX_TAG_LENGTH ? tag.substring(0, MAX_TAG_LENGTH) : tag)
                .distinct()
                .limit(MAX_TAGS)
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

}
