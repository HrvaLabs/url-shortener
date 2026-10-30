package cc.hrva.urlshortener.security;

import cc.hrva.urlshortener.configuration.properties.AppProperties;
import cc.hrva.urlshortener.service.UrlService;
import cc.hrva.urlshortener.service.domain.HostRouting;
import java.time.Clock;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@TestConfiguration
public class AnyHostIsPrimary {

    @Bean
    CustomHostFilter customHostFilter() {
        final var hostRouting = mock(HostRouting.class);
        when(hostRouting.classify(any())).thenReturn(new HostRouting.Primary());
        return new CustomHostFilter(hostRouting, mock(UrlService.class), mock(ClickRequestResolver.class), new AppProperties(), Clock.systemUTC());
    }

}
