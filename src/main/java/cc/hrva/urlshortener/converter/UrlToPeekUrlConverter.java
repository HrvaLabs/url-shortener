package cc.hrva.urlshortener.converter;

import cc.hrva.urlshortener.model.Url;
import cc.hrva.urlshortener.model.PeekUrl;
import org.springframework.core.convert.converter.Converter;
import org.springframework.stereotype.Component;

@Component
public class UrlToPeekUrlConverter implements Converter<Url, PeekUrl> {

    @Override
    public PeekUrl convert(final Url url) {
        final var passwordProtected = url.getPasswordHash() != null;
        return PeekUrl.builder()
                .longUrl(passwordProtected ? null : url.getLongUrl())
                .shortUrl(url.getShortUrl())
                .createDate(url.getCreateDate())
                .passwordProtected(passwordProtected)
                .build();
    }

}
