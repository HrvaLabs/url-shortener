package cc.hrva.urlshortener.service.linksignal;

import cc.hrva.urlshortener.service.DomainAgeService;
import cc.hrva.urlshortener.service.SettingsService;
import java.time.Clock;
import org.mockito.Mockito;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

@TestConfiguration(proxyBeanMethods = false)
public class AnonymousLinkSignalsTestConfiguration {

    @Bean
    AnonymousLinkSignals anonymousLinkSignals(final SettingsService settingsService) {
        return new AnonymousLinkSignals(new RandomLookingLabels(), Mockito.mock(DomainAgeService.class), settingsService, Clock.systemUTC());
    }

}
