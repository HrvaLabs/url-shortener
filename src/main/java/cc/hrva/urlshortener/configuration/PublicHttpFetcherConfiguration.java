package cc.hrva.urlshortener.configuration;

import cc.hrva.urlshortener.client.ChannelPoster;
import cc.hrva.urlshortener.client.DestinationProber;
import cc.hrva.urlshortener.client.PublicHttpFetcher;
import cc.hrva.urlshortener.client.RedirectFollower;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class PublicHttpFetcherConfiguration {

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
    public ChannelPoster channelPoster() {
        return publicHttpFetcher()::post;
    }

}
