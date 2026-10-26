package cc.hrva.urlshortener.converter;

import cc.hrva.urlshortener.exception.UrlNotFoundException;
import cc.hrva.urlshortener.repository.UrlRepository;
import cc.hrva.urlshortener.util.TagNormalizer;
import java.util.LinkedHashSet;
import lombok.RequiredArgsConstructor;
import cc.hrva.urlshortener.dto.UrlUpdateDto;
import cc.hrva.urlshortener.model.Url;
import org.apache.commons.lang3.StringUtils;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class UrlUpdateDtoToUrlConverter implements Converter<UrlUpdateDto, Url> {

    private final UrlRepository urlRepository;
    private final PasswordEncoder passwordEncoder;

    @Override
    public Url convert(final UrlUpdateDto urlUpdateDto) {
        final var existingUrl = urlRepository.findById(urlUpdateDto.getId())
                .orElseThrow(() -> new UrlNotFoundException(String.format("Url with id %d doesn't exist", urlUpdateDto.getId())));
        final var editedUrl = Url.builder()
                .id(existingUrl.getId())
                .longUrl(existingUrl.getLongUrl())
                .tags(existingUrl.getTags() == null ? null : new LinkedHashSet<>(existingUrl.getTags()))
                .passwordHash(existingUrl.getPasswordHash())
                .build();

        if (StringUtils.isNotBlank(urlUpdateDto.getLongUrl())) {
            editedUrl.setLongUrl(CreateUrlToUrlConverter.normalizeScheme(urlUpdateDto.getLongUrl()));
        }

        if (urlUpdateDto.getVisitLimit() == null || urlUpdateDto.getVisitLimit() <= 0) {
            editedUrl.setVisitLimit(null);
        } else {
            editedUrl.setVisitLimit(urlUpdateDto.getVisitLimit());
        }

        if (urlUpdateDto.getExpirationDate() == null) {
            editedUrl.setExpirationDate(null);
        } else {
            editedUrl.setExpirationDate(urlUpdateDto.getExpirationDate());
        }

        if (urlUpdateDto.getTags() != null) {
            editedUrl.setTags(TagNormalizer.normalize(urlUpdateDto.getTags()));
        }

        if (urlUpdateDto.getPassword() != null) {
            editedUrl.setPasswordHash(StringUtils.isBlank(urlUpdateDto.getPassword())
                    ? null
                    : passwordEncoder.encode(urlUpdateDto.getPassword()));
        }
        return editedUrl;
    }

}
