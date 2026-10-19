package cc.hrva.urlshortener.configuration;

import cc.hrva.urlshortener.configuration.properties.GoogleApiProperties;
import com.google.api.client.http.javanet.NetHttpTransport;
import com.google.api.client.json.gson.GsonFactory;
import com.google.api.services.safebrowsing.v5.Safebrowsing;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class SafeBrowsingConfiguration {

    @Bean
    public Safebrowsing safebrowsing(final GoogleApiProperties googleApiProperties) {
        final var httpTransport = new NetHttpTransport();
        final var jsonFactory = GsonFactory.getDefaultInstance();

        return new Safebrowsing.Builder(httpTransport, jsonFactory, null)
                .setApplicationName(googleApiProperties.getClientId())
                .build();
    }

}
