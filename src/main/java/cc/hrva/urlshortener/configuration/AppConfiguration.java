package cc.hrva.urlshortener.configuration;

import cc.hrva.urlshortener.configuration.properties.AppProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(AppProperties.class)
public class AppConfiguration {

}
