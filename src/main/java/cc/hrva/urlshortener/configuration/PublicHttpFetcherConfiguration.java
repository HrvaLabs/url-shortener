package cc.hrva.urlshortener.configuration;

import cc.hrva.urlshortener.client.PublicHttpFetcher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class PublicHttpFetcherConfiguration {

    @Bean
    public PublicHttpFetcher publicHttpFetcher() {
        return new PublicHttpFetcher(PublicHttpFetcher.httpsPublicOnly());
    }

}
