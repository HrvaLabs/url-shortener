package cc.hrva.urlshortener.service.settings;

import cc.hrva.urlshortener.configuration.properties.AppProperties;
import cc.hrva.urlshortener.service.SettingsService;
import org.mockito.Mockito;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

@TestConfiguration(proxyBeanMethods = false)
public class DefaultSettingsTestConfiguration {

    @Bean
    SettingsService settingsService(final ObjectProvider<AppProperties> appProperties) {
        final var configured = appProperties.getIfAvailable(AppProperties::new);
        return SettingsServiceFixtures.defaults(Mockito.mockingDetails(configured).isMock() ? new AppProperties() : configured);
    }

}
