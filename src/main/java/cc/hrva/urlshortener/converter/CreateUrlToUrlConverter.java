package cc.hrva.urlshortener.converter;

import cc.hrva.urlshortener.dto.CreateUrlDto;
import cc.hrva.urlshortener.model.Url;
import cc.hrva.urlshortener.util.TagNormalizer;
import java.util.regex.Pattern;
import org.apache.commons.lang3.StringUtils;
import org.springframework.core.convert.converter.Converter;
import org.springframework.stereotype.Component;

@Component
public class CreateUrlToUrlConverter implements Converter<CreateUrlDto, Url> {

  private static final Pattern SCHEME_PREFIX = Pattern.compile(
      "^(?:[a-z][a-z0-9+.-]*:(?!\\d+(?:[/?#]|$))|https?/)", Pattern.CASE_INSENSITIVE);

  @Override
  public Url convert(final CreateUrlDto createUrlDto) {
    return Url.builder()
        .longUrl(normalizeScheme(createUrlDto.getLongUrl()))
        .shortUrl(createUrlDto.getShortUrl())
        .visitLimit(createUrlDto.getVisitLimit())
        .expirationDate(createUrlDto.getExpirationDate())
        .tags(TagNormalizer.normalize(createUrlDto.getTags()))
        .build();
  }

  private static String normalizeScheme(final String longUrl) {
    if (StringUtils.isBlank(longUrl)) {
      return longUrl;
    }
    final var trimmed = longUrl.trim();
    if (SCHEME_PREFIX.matcher(trimmed).find()) {
      return trimmed;
    }
    return "https://" + trimmed;
  }

}
