package cc.hrva.urlshortener.service;

import cc.hrva.urlshortener.client.TurnstileClient;
import cc.hrva.urlshortener.configuration.properties.AppProperties;
import cc.hrva.urlshortener.service.impl.DefaultHumanCheckService;
import org.mockito.Mockito;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

@TestConfiguration(proxyBeanMethods = false)
public class HumanCheckTestConfiguration {

    private static final String FRONTEND_URL = "https://app.hrva.cc";

    @Bean
    HumanCheckService humanCheckService(final SettingsService settingsService) {
        final var frontend = new AppProperties();
        frontend.setFrontendUrl(FRONTEND_URL);
        return new DefaultHumanCheckService(settingsService, Mockito.mock(TurnstileClient.class), frontend);
    }

}
