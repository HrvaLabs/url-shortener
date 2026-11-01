package cc.hrva.urlshortener.configuration;

import cc.hrva.urlshortener.client.BrowserPageFetcher;
import cc.hrva.urlshortener.client.ChannelPoster;
import cc.hrva.urlshortener.client.DestinationProber;
import cc.hrva.urlshortener.client.PageFetcher;
import cc.hrva.urlshortener.client.PublicHttpFetcher;
import cc.hrva.urlshortener.client.RedirectFollower;
import cc.hrva.urlshortener.client.Resolver;
import java.util.Map;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class PublicHttpFetcherConfiguration {

    public static final Map<String, String> BROWSER_HEADERS = Map.of(
            "User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/141.0.0.0 Safari/537.36",
            "Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
            "Accept-Language", "en-US,en;q=0.9");

    @Bean
    public PublicHttpFetcher publicHttpFetcher() {
        return new PublicHttpFetcher(PublicHttpFetcher.httpsPublicOnly());
    }

    @Bean
    public RedirectFollower redirectFollower() {
        return new PublicHttpFetcher(PublicHttpFetcher.httpOrHttpsPublicOnly())::followRedirects;
    }

    @Bean
    public DestinationProber destinationProber() {
        return new PublicHttpFetcher(PublicHttpFetcher.httpOrHttpsPublicOnly())::probe;
    }

    @Bean
    public PageFetcher pageFetcher() {
        final var fetcher = new PublicHttpFetcher(PublicHttpFetcher.httpOrHttpsPublicOnly());
        return (url, maxBytes, timeout) -> fetcher.fetch(url, maxBytes, null, null, timeout);
    }

    @Bean
    public BrowserPageFetcher browserPageFetcher() {
        final var fetcher = new PublicHttpFetcher(PublicHttpFetcher.httpOrHttpsPublicOnly());
        return (url, maxBytes, timeout) -> fetcher.fetchFirst(url, maxBytes, BROWSER_HEADERS, timeout);
    }

    @Bean
    public Resolver resolver() {
        return Resolver.SYSTEM;
    }

    @Bean
    public ChannelPoster channelPoster() {
        return publicHttpFetcher()::post;
    }

}
